//! Apple Music 官方 TTML（取词链的第 1 优先源）。
//!
//! 链路照抄 Lyricify-Lyrics-Helper：
//! 1. 抠**开发者 access token** —— 抓 `https://music.apple.com/us/browse` 的 HTML，
//!    找到 `assets/index*.js`，在里面正文里搜 JWT，按 header `kid=WebPlayKid` /
//!    payload `iss=AMPWebPlay` 打分挑最像的（并跳过已过期的）。
//! 2. 取 **storefront / language** —— 有个人 token 时问 `/v1/me/storefront`，失败回退 `us` / `en-US`。
//! 3. 取词 —— `/v1/catalog/{storefront}/songs/{id}?include[songs]=lyrics,syllable-lyrics&l=..&extend=ttmlLocalizations`。
//!    两个 include 都要：`syllable-lyrics` 给**逐字**（字段 `ttmlLocalizations`），
//!    `lyrics` 给**逐行**（字段是 `ttml`）—— 后者是逐字拿不到时的官方回落，白拿的。
//!
//! 🔴 实测（2026-10-07）**只有开发者 token 是拿不到歌词的**：请求 200，但 `relationships`
//! 里只有 `albums` / `artists`，没有任何 `*-lyrics`。歌词属于 Apple 订阅权益，必须带
//! `media-user-token`（用户 Apple Music 账号的登录令牌，与网易 cookie 无关）。
//! 所以这一级没配 token 时直接跳过，不算失败。

use base64::Engine as _;
use once_cell::sync::Lazy;
use regex_lite::Regex;
use serde_json::Value;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::engine;

const BROWSE_URL: &str = "https://music.apple.com/us/browse";
const AMP_API: &str = "https://amp-api.music.apple.com";

/// 请求歌词时用的 `l=` 语种。
///
/// 🔴 这个参数决定**返回哪一份翻译**，不是"界面语言"。参考实现（Lyricify）直接写死
/// `zh-hans-cn`；不能用 storefront 的默认语言（美区账号会拿到 `en-US`，中文翻译就没了）。
/// 显式配置的语言优先于它。
const DEFAULT_LYRIC_LANGUAGE: &str = "zh-hans-cn";

/// 配置 + 缓存（access token / storefront）。access token 抓一次能用一阵，别每首歌都抓。
#[derive(Default)]
struct AppleState {
    /// 用户提供的 Apple Music 登录令牌（明文只在这里，不下发）。
    media_user_token: Option<String>,
    /// 显式配置的 storefront / language；留空则自动探测。
    storefront_override: Option<String>,
    language_override: Option<String>,
    /// 已抓到的开发者 token 与其到期时间（秒）。
    access_token: Option<String>,
    access_token_exp: u64,
    /// 已探测到的 storefront / language。
    resolved_storefront: Option<String>,
    resolved_language: Option<String>,
}

static STATE: Lazy<Mutex<AppleState>> = Lazy::new(|| Mutex::new(AppleState::default()));

/// 一次 Apple 取词的结果。
pub struct AppleFetch {
    /// TTML 文本；空串表示这次没拿到（原因见 `reason`）。
    pub ttml: String,
    /// 没拿到时的原因，给日志用：`no_media_user_token` / `no_lyrics` / `invalid_ttml`。
    pub reason: String,
}

/// 配置 Apple 凭据。`media_user_token` 为空则等于关闭这一级。
pub fn set_config(
    media_user_token: String,
    storefront: Option<String>,
    language: Option<String>,
) {
    let token = Some(media_user_token).filter(|s| !s.trim().is_empty());
    let storefront = storefront.filter(|s| !s.trim().is_empty());
    let language = language.filter(|s| !s.trim().is_empty());

    let mut st = STATE.lock().unwrap();
    let changed = st.media_user_token != token
        || st.storefront_override != storefront
        || st.language_override != language;

    st.media_user_token = token;
    st.storefront_override = storefront;
    st.language_override = language;
    if changed {
        // 凭据或区域变了，之前探测出的 storefront / 语言都不再可信
        st.resolved_storefront = None;
        st.resolved_language = None;
    }
}

/// 是否已配置凭据（Kotlin 侧用来决定要不要走这一级）。
pub fn is_configured() -> bool {
    STATE.lock().unwrap().media_user_token.is_some()
}

/// 取一首歌的 Apple 官方歌词。
pub async fn fetch(am_id: &str) -> Result<AppleFetch, String> {
    let media_token = {
        let st = STATE.lock().unwrap();
        st.media_user_token.clone()
    };
    let Some(media_token) = media_token else {
        return Ok(AppleFetch {
            ttml: String::new(),
            reason: "no_media_user_token".into(),
        });
    };

    let place = resolve_place(&media_token).await;
    let url = format!(
        "{AMP_API}/v1/catalog/{}/songs/{am_id}\
         ?include[songs]=lyrics,syllable-lyrics&l={}&extend=ttmlLocalizations",
        place.storefront,
        urlencoding::encode(&place.lyric_language)
    );

    let (status, body) = get_authorized(&url, &media_token, &place.accept_language).await?;

    // 401/403 往往是开发者 token 过期 —— 打掉重抓一次再试
    let (status, body) = if matches!(status, 401 | 403) {
        invalidate_access_token();
        get_authorized(&url, &media_token, &place.accept_language).await?
    } else {
        (status, body)
    };

    if !(200..300).contains(&status) {
        return Err(format!("Apple catalog 返回 HTTP {status}"));
    }

    let value: Value = serde_json::from_str(&body).map_err(|e| format!("Apple 响应不是 JSON: {e}"))?;
    match extract_ttml(&value) {
        Some(ttml) => Ok(AppleFetch {
            ttml,
            reason: String::new(),
        }),
        None => Ok(AppleFetch {
            ttml: String::new(),
            reason: "no_lyrics".into(),
        }),
    }
}

/// 带两个 token 发一次 catalog 请求。
async fn get_authorized(
    url: &str,
    media_token: &str,
    accept_language: &str,
) -> Result<(u16, String), String> {
    let access = ensure_access_token().await?;
    let resp = engine::HTTP
        .get(url)
        .header("Origin", "https://music.apple.com")
        .header("Accept", "application/json")
        .header("Accept-Language", accept_language)
        .header("Authorization", format!("Bearer {access}"))
        .header("media-user-token", media_token)
        .send()
        .await
        .map_err(|e| format!("Apple 请求失败: {e}"))?;
    let status = resp.status().as_u16();
    let body = resp.text().await.unwrap_or_default();
    Ok((status, body))
}

/// 解析出的「去哪儿取 / 要什么语种」。
struct Place {
    /// 商店区域，用来拼 catalog 路径。
    storefront: String,
    /// 请求头 `Accept-Language`（跟账号区域语言走，只是礼节性声明）。
    accept_language: String,
    /// `l=` 参数：**要哪一份翻译**，见 [DEFAULT_LYRIC_LANGUAGE]。
    lyric_language: String,
}

/// 拿到可用域名与语言：显式配置 > `/v1/me/storefront` > `us` / `en-US`。
async fn resolve_place(media_token: &str) -> Place {
    {
        let st = STATE.lock().unwrap();
        if let (Some(sf), Some(lang)) = (&st.resolved_storefront, &st.resolved_language) {
            return Place {
                storefront: sf.clone(),
                accept_language: format!("{lang},en;q=0.9"),
                lyric_language: st
                    .language_override
                    .clone()
                    .unwrap_or_else(|| DEFAULT_LYRIC_LANGUAGE.to_string()),
            };
        }
    }

    let mut storefront = None;
    let mut account_language = None;
    if let Ok(access) = ensure_access_token().await {
        if let Ok(resp) = engine::HTTP
            .get(format!("{AMP_API}/v1/me/storefront"))
            .header("Origin", "https://music.apple.com")
            .header("Accept", "application/json")
            .header("Authorization", format!("Bearer {access}"))
            .header("media-user-token", media_token)
            .send()
            .await
        {
            if resp.status().is_success() {
                if let Ok(v) = resp.json::<Value>().await {
                    let first = v.get("data").and_then(|d| d.as_array()).and_then(|a| a.first());
                    storefront = first
                        .and_then(|d| d.get("id"))
                        .and_then(|x| x.as_str())
                        .map(str::to_string);
                    account_language = first
                        .and_then(|d| d.get("attributes"))
                        .and_then(|a| a.get("defaultLanguageTag"))
                        .and_then(|x| x.as_str())
                        .map(str::to_string);
                }
            }
        }
    }

    let (st_override, lang_override) = {
        let st = STATE.lock().unwrap();
        (st.storefront_override.clone(), st.language_override.clone())
    };

    let storefront = storefront.or(st_override).unwrap_or_else(|| "us".to_string());
    let account_language = account_language.unwrap_or_else(|| "en-US".to_string());
    let lyric_language = lang_override.unwrap_or_else(|| DEFAULT_LYRIC_LANGUAGE.to_string());

    let place = Place {
        storefront,
        accept_language: format!("{account_language},en;q=0.9"),
        lyric_language,
    };

    let mut st = STATE.lock().unwrap();
    st.resolved_storefront = Some(place.storefront.clone());
    st.resolved_language = Some(account_language);
    place
}

/// 拿到可用的开发者 access token，必要时现抓。
async fn ensure_access_token() -> Result<String, String> {
    {
        let st = STATE.lock().unwrap();
        if let Some(t) = &st.access_token {
            if st.access_token_exp > now_secs() + 60 {
                return Ok(t.clone());
            }
        }
    }
    let token = scrape_access_token().await?;
    let exp = jwt_exp(&token).unwrap_or(now_secs() + 3600);
    let mut st = STATE.lock().unwrap();
    st.access_token = Some(token.clone());
    st.access_token_exp = exp;
    Ok(token)
}

fn invalidate_access_token() {
    let mut st = STATE.lock().unwrap();
    st.access_token = None;
    st.access_token_exp = 0;
}

/// 抓网页播放器的开发者 token。
async fn scrape_access_token() -> Result<String, String> {
    let html = engine::HTTP
        .get(BROWSE_URL)
        .send()
        .await
        .map_err(|e| format!("抓 Apple browse 页失败: {e}"))?
        .text()
        .await
        .unwrap_or_default();

    let scripts = find_index_scripts(&html);
    if scripts.is_empty() {
        return Err("Apple browse 页里没找到 index*.js".into());
    }

    for url in scripts.iter().take(4) {
        let js = match engine::HTTP.get(url).send().await {
            Ok(r) => r.text().await.unwrap_or_default(),
            Err(_) => continue,
        };
        if let Some(t) = find_bearer_jwt(&js) {
            return Ok(t);
        }
    }
    Err("Apple index*.js 里没找到可用的开发者 token".into())
}

/// 从 TTML 响应里抽 `syllable-lyrics`（逐字），退 `lyrics`（逐行）。
///
/// 两份都要求文本里带 `begin=` / `end=` —— 没时间轴的 TTML 要了也没用。
fn extract_ttml(value: &Value) -> Option<String> {
    let rel = value
        .get("data")
        .and_then(|d| d.as_array())
        .and_then(|a| a.first())
        .and_then(|s| s.get("relationships"))?;

    for key in ["syllable-lyrics", "lyrics"] {
        let attrs = rel
            .get(key)
            .and_then(|c| c.get("data"))
            .and_then(|d| d.as_array())
            .and_then(|a| a.first())
            .and_then(|x| x.get("attributes"));
        let Some(attrs) = attrs else { continue };

        for field in ["ttmlLocalizations", "ttml"] {
            if let Some(text) = attrs.get(field).and_then(|v| v.as_str()) {
                if !text.trim().is_empty() && text.contains("begin=") && text.contains("end=") {
                    return Some(text.to_string());
                }
            }
        }
    }
    None
}

// ---------------------------------------------------------------------------
// 开发者 token 的定位
// ---------------------------------------------------------------------------

/// 从 browse HTML 里找 `assets/index*.js`（手工扫描，避开正则前瞻的语法要求）。
fn find_index_scripts(html: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let needle = "assets/index";
    let mut rest = html;
    while let Some(pos) = rest.find(needle) {
        rest = &rest[pos..];
        let tail: String = rest
            .chars()
            .take_while(|c| {
                !c.is_whitespace() && *c != '"' && *c != '\'' && *c != '<' && *c != '>'
            })
            .collect();
        if tail.ends_with(".js") {
            let mut url = tail.clone();
            if !url.starts_with("http") {
                url = format!("https://music.apple.com/{}", url.trim_start_matches('/'));
            }
            if !url.contains("index-legacy") && !out.contains(&url) {
                out.push(url);
            }
        }
        rest = &rest[needle.len()..];
    }
    out
}

/// 在 JS 正文里找最像 Apple 开发者 token 的 JWT。
fn find_bearer_jwt(js: &str) -> Option<String> {
    let re = Regex::new(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+").ok()?;
    let mut best: Option<(i32, String)> = None;
    for m in re.find_iter(js) {
        let candidate = m.as_str().to_string();
        let score = jwt_score(&candidate);
        if score < 0 {
            continue;
        }
        if best.as_ref().map(|(s, _)| score > *s).unwrap_or(true) {
            best = Some((score, candidate));
        }
    }
    best.map(|(_, t)| t)
}

/// 给 JWT 打分：过期的直接判死，`kid=WebPlayKid` / `iss=AMPWebPlay` 各加 100。
fn jwt_score(token: &str) -> i32 {
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() < 2 {
        return -1;
    }
    let Some(payload) = b64url_json(parts[1]) else {
        return -1;
    };
    let header = b64url_json(parts[0]).unwrap_or_default();

    if let Some(exp) = jwt_exp(token) {
        if now_secs() + 60 >= exp {
            return -1;
        }
    }

    let mut score = 0;
    if header.contains("\"kid\":\"WebPlayKid\"") {
        score += 100;
    }
    if payload.contains("\"iss\":\"AMPWebPlay\"") {
        score += 100;
    }
    if payload.contains("root_https_origin") {
        score += 10;
    }
    score
}

/// 读 JWT payload 里的 `exp`（秒）。
fn jwt_exp(token: &str) -> Option<u64> {
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() < 2 {
        return None;
    }
    let payload = b64url_json(parts[1])?;
    let pos = payload.find("\"exp\"")?;
    let num: String = payload[pos..]
        .chars()
        .skip_while(|c| !c.is_ascii_digit())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    num.parse().ok()
}

/// base64url 解出 JSON 明文段。
fn b64url_json(segment: &str) -> Option<String> {
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(segment)
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(segment))
        .ok()?;
    String::from_utf8(bytes).ok()
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
