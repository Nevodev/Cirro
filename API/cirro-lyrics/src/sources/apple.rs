//! Apple Music 官方 TTML（取词链的第 1 优先源）。
//!
//! 实现对齐 **Lyricify-Lyrics-Helper** 的 `Providers/Web/AppleMusic/{Api,Response}.cs`
//! 与 `Providers/Web/BaseApi.cs`（2026-10-07 版本）：
//!
//! 1. **开发者 access token** —— 抓 `https://music.apple.com/us/browse` 的 HTML，
//!    找到 `assets/index*.js`，在正文里扫 JWT，按 header `kid=WebPlayKid` /
//!    payload `iss=AMPWebPlay` 打分挑最像的（已过期的直接判死）。
//! 2. **storefront / language** —— 带 `media-user-token` 问 `/v1/me/storefront`；
//!    失败回落 `us` / `en-US`（与原项目 `EnsureInitAsync` 的 catch 分支一致）。
//! 3. **取词** —— `/v1/catalog/{storefront}/songs/{id}?include[songs]=syllable-lyrics&l=zh-hans-cn&extend=ttmlLocalizations`
//!    🔴 **只要 `syllable-lyrics`**：逐字 TTML 在 `attributes.ttmlLocalizations`。
//!    原项目把「用 storefront 语言」那版注释掉了、写死 `zh-hans-cn`，这里照做。
//! 4. **搜索** —— `/v1/catalog/{storefront}/search?term=&types=songs&limit=&l=`（[`search`]）。
//!    这是**自己找 Apple id** 的入口：AMLL 只覆盖网易曲库一小部分，没命中就没有 `appleMusicId`，
//!    官方取词那一级直接"跳过(无 appleId)"。搜索不挑歌，候选交给 Kotlin 侧打分。
//!
//! 🔴 实测（2026-10-07）**只有开发者 token 是拿不到歌词的**：请求 200，但 `relationships`
//! 里只有 `albums` / `artists`，没有任何 `*-lyrics`。歌词属于 Apple 订阅权益，必须带
//! `media-user-token`（用户 Apple Music 账号的登录令牌，与网易 cookie 无关）。
//! 所以这一级没配 token 时直接跳过，不算失败。
//!
//! 🔴 实测（2026-10-07）**区域也必须对**：catalog 按 storefront 分区，同一首歌
//! 在 `cn` 区有 `syllable-lyrics`，在 `us` / `jp` / `hk` / `tw` 区是**零歌词**
//! （响应只剩 1.5KB 的裸歌曲对象）。所以区域一旦取错，这一级是每一首都空手。
//!
//! 与原项目**唯一一处有意偏离**：[`resolve_place`] 探测失败时回落 `us` 但
//! **不把这个回落值写进缓存** —— 理由见该函数的注释。

use base64::Engine as _;
use once_cell::sync::Lazy;
use regex_lite::Regex;
use serde_json::Value;
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::engine;

const BROWSE_URL: &str = "https://music.apple.com/us/browse";
const AMP_API: &str = "https://amp-api.music.apple.com";

/// 原项目 `BaseApi.UserAgent`，原样搬过来。
///
/// 我们引擎的默认 UA 是 Chrome 128，但 Apple 这条链路对齐原项目 —— 这个 UA 是
/// 原项目在真实用户量下跑通的组合，不自己"升级"。
const APPLE_UA: &str = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 \
     (KHTML, like Gecko) Chrome/63.0.3239.132 Safari/537.36";

/// 原项目 `Api.HttpRefer`。**每个** Apple 请求都带（含抓 browse / JS 那几跳）。
const APPLE_REFERER: &str = "https://music.apple.com/";

/// 请求歌词时用的 `l=` 语种。原项目写死 `zh-hans-cn`。
///
/// 🔴 这个参数决定**返回哪一份翻译**，不是"界面语言"。不能用 storefront 的默认语言
/// （美区账号会拿到 `en-US`，中文翻译就没了）—— 原项目把用语言的那版注释掉了。
const LYRIC_LANGUAGE: &str = "zh-hans-cn";

/// 抓 browse 页时只取前 N 字节。
///
/// 实测（2026-10-07）`assets/index*.js` 的引用在**偏移 30KB** 处，而整页有 **2MB** ——
/// 只取前 64KB 就够，省下 97% 的流量。抓 token 在冷启动路径上，移动网络下这几秒是成败之别。
/// 服务器不支持 Range 时会回 200 全量，代码照样正确（只是在完整内容里找）。
const BROWSE_SCAN_BYTES: u64 = 64 * 1024;

/// 扫 JS bundle 时每段的字节数。JWT 埋在 bundle 前段（实测 872KB / 3.3MB）。
const JS_SEGMENT_BYTES: u64 = 1 << 20;

/// 最多扫几段（防 bundle 里根本没有 JWT 时反复拉整个文件）。
const JS_MAX_SEGMENTS: u64 = 4;

/// 段与段之间保留的重叠字节，防一个 JWT 正好被段边界切开（JWT 实测 268 字节）。
const JWT_OVERLAP: usize = 512;

/// 抓 token 那条路径的单次请求超时。
///
/// 比 [`engine::HTTP`] 的全局 15 秒宽松：要下 MB 级的 JS bundle，即便退化成整份下
/// （服务器不支持 Range），移动网络下 15 秒也不够。原项目是 .NET 默认的 100 秒。
const FETCH_TIMEOUT: Duration = Duration::from_secs(30);

/// 配置 + 缓存（access token / storefront）。access token 抓一次能用一阵，别每首歌都抓。
#[derive(Default)]
struct AppleState {
    /// 用户提供的 Apple Music 登录令牌（明文只在这里，不下发）。
    media_user_token: Option<String>,
    /// 已抓到的开发者 token 与其到期时间（秒）。
    access_token: Option<String>,
    access_token_exp: u64,
    /// 已**成功探测到**的 storefront / language。探测失败一律不写（见 [`resolve_place`]）。
    resolved_storefront: Option<String>,
    resolved_language: Option<String>,
}

static STATE: Lazy<Mutex<AppleState>> = Lazy::new(|| Mutex::new(AppleState::default()));

/// 一次 Apple 取词的结果。
pub struct AppleFetch {
    /// TTML 文本；空串表示这次没拿到（原因见 `reason`）。
    pub ttml: String,
    /// 没拿到时的原因，给日志用：`no_media_user_token` / `no_lyrics`。
    pub reason: String,
    /// 这次实际用的 storefront。**排障关键**：区域取错是「每一首都空手」的头号原因
    /// —— 实测同一首中文歌在 `cn` 区有逐字词，在 `us` 区是零歌词。
    pub storefront: String,
    /// storefront 是**探测出来的**（`true`）还是回落值（`false`）。回落意味着
    /// `/v1/me/storefront` 没成功，那种情况下几乎必然零歌词。日志里 `sf=us!` 的叹号指这个。
    pub storefront_probed: bool,
}

/// 配置 Apple 凭据。`media_user_token` 为空串则等于关闭这一级。
///
/// 只有 token 变了才作废已探测的 storefront —— 换账号会换区域。
pub fn set_config(media_user_token: String) {
    let token = Some(media_user_token).filter(|s| !s.trim().is_empty());
    let mut st = STATE.lock().unwrap();
    if st.media_user_token != token {
        st.media_user_token = token;
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
            storefront: String::new(),
            storefront_probed: false,
        });
    };

    let place = resolve_place(&media_token).await;
    let url = format!(
        "{AMP_API}/v1/catalog/{}/songs/{am_id}\
         ?include[songs]=syllable-lyrics&l={}&extend=ttmlLocalizations",
        place.storefront,
        urlencoding::encode(LYRIC_LANGUAGE)
    );

    let mut resp = get_authorized(&url, &media_token, &place.accept_language).await?;

    // 开发者 token 过期的话，打掉重抓一次再试（原项目 `GetAsyncWithAccessTokenRetry`）
    if should_refresh_access_token(&resp) {
        invalidate_access_token();
        resp = get_authorized(&url, &media_token, &place.accept_language).await?;
    }

    if !(200..300).contains(&resp.status) {
        return Err(format!(
            "Apple catalog 返回 HTTP {}（sf={}）",
            resp.status, place.storefront
        ));
    }

    let value: Value =
        serde_json::from_str(&resp.body).map_err(|e| format!("Apple 响应不是 JSON: {e}"))?;
    match extract_ttml(&value) {
        Some(ttml) => Ok(AppleFetch {
            ttml,
            reason: String::new(),
            storefront: place.storefront,
            storefront_probed: place.probed,
        }),
        None => Ok(AppleFetch {
            ttml: String::new(),
            reason: "no_lyrics".into(),
            storefront: place.storefront,
            storefront_probed: place.probed,
        }),
    }
}

/// 一次请求的原始结果。带 content-type / length 是为了判 403 的性质。
struct AppleResponse {
    status: u16,
    body: String,
    content_type: String,
    content_length: u64,
}

/// 带两个 token 发一次请求。
///
/// 请求头对齐原项目：固定 UA + `Referer: https://music.apple.com/` + `Origin` + ...
/// 原项目的 `BaseApi.SetRequestHeaders` 对每个请求都挂 UA / Referer，
/// 抓 browse 和 JS 那两跳也不例外。
async fn get_authorized(
    url: &str,
    media_token: &str,
    accept_language: &str,
) -> Result<AppleResponse, String> {
    let access = ensure_access_token().await?;
    let resp = engine::HTTP
        .get(url)
        .header("User-Agent", APPLE_UA)
        .header("Referer", APPLE_REFERER)
        .header("Origin", "https://music.apple.com")
        .header("Accept", "application/json")
        .header("Accept-Language", accept_language)
        .header("Authorization", format!("Bearer {access}"))
        .header("media-user-token", media_token)
        .send()
        .await
        .map_err(|e| format!("Apple 请求失败: {e}"))?;

    let status = resp.status().as_u16();
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    let content_length = resp.content_length().unwrap_or(0);
    let body = resp.text().await.unwrap_or_default();

    Ok(AppleResponse {
        status,
        body,
        content_type,
        content_length,
    })
}

/// 这次响应是否说明开发者 token 该换了（原项目 `ShouldRefreshAccessToken`）。
///
/// 401 一律算；403 只有「`application/octet-stream` + 长度为 0」那种空响应才算 ——
/// 403 还有别的含义，一律重抓只会白跑一趟。
fn should_refresh_access_token(r: &AppleResponse) -> bool {
    match r.status {
        401 => true,
        403 => r.content_type.starts_with("application/octet-stream") && r.content_length == 0,
        _ => false,
    }
}

/// 解析出的「去哪儿取 / 带什么语言头」。
struct Place {
    /// 商店区域，用来拼 catalog 路径。
    storefront: String,
    /// 请求头 `Accept-Language`（跟随账号区域语言，只是礼节性声明）。
    accept_language: String,
    /// 账号区域语言的**原值**（`accept_language` 是它加 `,en;q=0.9` 得来的）。
    ///
    /// 🔴 搜索的 `l=` 用它，取词的 `l=` **不用**（那边写死 `zh-hans-cn`，是为了拿中文翻译，
    /// 不能用美区账号的 `en-US`）—— 两个参数同名但语义不同，别混。
    language: String,
    /// 上面那个 storefront 是探测出来的还是回落值。回落值几乎必然零歌词，
    /// 所以要能一路带到日志里（见 `AppleFetch::storefront_probed`）。
    probed: bool,
}

/// storefront / language：带 `media-user-token` 问 `/v1/me/storefront`，
/// 失败回落 `us` / `en-US`（对齐原项目 `EnsureInitAsync`）。
///
/// 🔴 **唯一一处有意偏离原项目**：回落值**不写进缓存**（原项目写完会置 `_inited = true`）。
/// 理由是实测出来的：`us` 区对中文歌是**零歌词**，而缓存是进程级的 —— 一旦某次探测
/// 因网络抖动失败，整个进程的官方取词就被永久钉死在 `us`，要等 App 重启，且重启后
/// 大概率复现。不缓存的话，下一次请求会重新探测，网络恢复即自愈。
/// 代价只是「探测一直失败时，每次取词都多一次 `/v1/me/storefront` 请求」（约 0.6s）。
async fn resolve_place(media_token: &str) -> Place {
    {
        let st = STATE.lock().unwrap();
        if let (Some(sf), Some(lang)) = (&st.resolved_storefront, &st.resolved_language) {
            return Place {
                storefront: sf.clone(),
                accept_language: format!("{lang},en;q=0.9"),
                language: lang.clone(),
                probed: true,
            };
        }
    }

    let mut probed: Option<(String, Option<String>)> = None;
    if let Ok(access) = ensure_access_token().await {
        if let Ok(resp) = engine::HTTP
            .get(format!("{AMP_API}/v1/me/storefront"))
            .header("User-Agent", APPLE_UA)
            .header("Referer", APPLE_REFERER)
            .header("Origin", "https://music.apple.com")
            .header("Accept", "application/json")
            .header("Authorization", format!("Bearer {access}"))
            .header("media-user-token", media_token)
            .send()
            .await
        {
            if resp.status().is_success() {
                if let Ok(v) = resp.json::<Value>().await {
                    let first = v
                        .get("data")
                        .and_then(|d| d.as_array())
                        .and_then(|a| a.first());
                    if let Some(sf) = first
                        .and_then(|d| d.get("id"))
                        .and_then(|x| x.as_str())
                        .filter(|s| !s.trim().is_empty())
                    {
                        let lang = first
                            .and_then(|d| d.get("attributes"))
                            .and_then(|a| a.get("defaultLanguageTag"))
                            .and_then(|x| x.as_str())
                            .map(str::to_string);
                        probed = Some((sf.to_string(), lang));
                    }
                }
            }
        }
    }

    match probed {
        Some((storefront, language)) => {
            let language = language.unwrap_or_else(|| "en-US".to_string());
            let mut st = STATE.lock().unwrap();
            st.resolved_storefront = Some(storefront.clone());
            st.resolved_language = Some(language.clone());
            Place {
                storefront,
                accept_language: format!("{language},en;q=0.9"),
                language,
                probed: true,
            }
        }
        // 探测失败：用回落值，但**不缓存**（见函数注释）
        None => Place {
            storefront: "us".to_string(),
            accept_language: "en-US,en;q=0.9".to_string(),
            language: "en-US".to_string(),
            probed: false,
        },
    }
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
///
/// 全程**尽量少下**：browse 页只取前 [BROWSE_SCAN_BYTES]（`assets/index` 的引用在
/// 30KB 处，整页 2MB），JS bundle 按 [JS_SEGMENT_BYTES] 分段取、命中即止
/// （JWT 在 872KB 处，整包 3.3MB）。实测 5.3MB → 约 1.1MB。
async fn scrape_access_token() -> Result<String, String> {
    let mut html = fetch_browse(Some(BROWSE_SCAN_BYTES)).await?;
    let mut scripts = find_index_scripts(&html);
    if scripts.is_empty() {
        // 前段没找到：Apple 可能把 script 挪到页面后部了，退回整份再找一次
        html = fetch_browse(None).await?;
        scripts = find_index_scripts(&html);
    }
    if scripts.is_empty() {
        return Err("Apple browse 页里没找到 index*.js".into());
    }

    for url in scripts.iter().take(4) {
        if let Ok(Some(t)) = scan_js_for_jwt(url).await {
            return Ok(t);
        }
    }
    Err("Apple index*.js 里没找到可用的开发者 token".into())
}

/// 抓 browse 页；`limit` 为 `Some(n)` 时只取前 n 字节（HTTP Range）。
async fn fetch_browse(limit: Option<u64>) -> Result<String, String> {
    let mut req = engine::HTTP
        .get(BROWSE_URL)
        .header("User-Agent", APPLE_UA)
        .header("Referer", APPLE_REFERER)
        .timeout(FETCH_TIMEOUT);
    if let Some(n) = limit {
        req = req.header("Range", format!("bytes=0-{}", n - 1));
    }
    req.send()
        .await
        .map_err(|e| format!("抓 Apple browse 页失败: {e}"))?
        .text()
        .await
        .map_err(|e| format!("读 Apple browse 页失败: {e}"))
}

/// 分段把一个 JS bundle 拉下来扫 JWT，命中就停。
///
/// 段间留 [JWT_OVERLAP] 字节重叠，防一个 JWT 正好横跨段边界。
/// 服务器不支持 Range（回 200 全量）时一次就拿全，扫完没有就是真没有。
async fn scan_js_for_jwt(url: &str) -> Result<Option<String>, String> {
    let mut carried = String::new();
    let mut offset: u64 = 0;

    for _ in 0..JS_MAX_SEGMENTS {
        let resp = engine::HTTP
            .get(url)
            .header("User-Agent", APPLE_UA)
            .header("Referer", APPLE_REFERER)
            .header(
                "Range",
                format!("bytes={offset}-{}", offset + JS_SEGMENT_BYTES - 1),
            )
            .timeout(FETCH_TIMEOUT)
            .send()
            .await
            .map_err(|e| format!("抓 {url} 失败: {e}"))?;

        let whole = resp.status().as_u16() == 200;
        let text = resp.text().await.unwrap_or_default();
        if text.is_empty() {
            return Ok(None);
        }

        let mut buf = String::with_capacity(carried.len() + text.len());
        buf.push_str(&carried);
        buf.push_str(&text);
        if let Some(t) = find_bearer_jwt(&buf) {
            return Ok(Some(t));
        }
        if whole {
            return Ok(None);
        }

        carried = tail_chars(&buf, JWT_OVERLAP);
        offset += JS_SEGMENT_BYTES;
    }
    Ok(None)
}

/// 取字符串结尾的 n 个字符（按 char 边界切，不切坏 UTF-8）。
fn tail_chars(s: &str, n: usize) -> String {
    if s.len() <= n {
        return s.to_string();
    }
    let mut start = s.len() - n;
    while start < s.len() && !s.is_char_boundary(start) {
        start += 1;
    }
    s[start..].to_string()
}

/// 从 catalog 响应里取逐字 TTML。
///
/// 只认 `syllable-lyrics`（对齐原项目 `LyricResponse.NormalizeTtml()`）：
/// `attributes.ttmlLocalizations` 优先，空则退 `attributes.ttml`，
/// 最后校验文本里含 `begin=` / `end=` —— 没时间轴的 TTML 要了也没用。
fn extract_ttml(value: &Value) -> Option<String> {
    let attrs = value
        .get("data")
        .and_then(|d| d.as_array())
        .and_then(|a| a.first())
        .and_then(|s| s.get("relationships"))
        .and_then(|r| r.get("syllable-lyrics"))
        .and_then(|c| c.get("data"))
        .and_then(|d| d.as_array())
        .and_then(|a| a.first())
        .and_then(|x| x.get("attributes"))?;

    for field in ["ttmlLocalizations", "ttml"] {
        if let Some(text) = attrs.get(field).and_then(|v| v.as_str()) {
            if !text.trim().is_empty() && text.contains("begin=") && text.contains("end=") {
                return Some(text.to_string());
            }
        }
    }
    None
}

// ---------------------------------------------------------------------------
// 搜索：自己找 Apple id（不依赖 AMLL）
// ---------------------------------------------------------------------------

/// 搜索命中的一个候选曲目。
pub struct AppleCandidate {
    /// Apple Music 歌曲 id，直接喂给 [`fetch`]。
    pub id: String,
    pub name: String,
    pub artist: String,
    pub album: String,
    /// 时长（毫秒）。`0` = Apple 没给这个字段。
    pub duration_ms: u64,
    /// Apple 标称这首歌有歌词。
    ///
    /// ⚠️ 只表示"有歌词"，**不保证有逐字**（`syllable-lyrics`）—— 那要真去 [`fetch`]
    /// 一次才知道。所以它只能当一个**加权项**，不能当"有词"的判据。
    pub has_lyrics: bool,
}

/// 一次搜索的结果。
pub struct AppleSearchHit {
    /// 候选曲目，顺序即 Apple 的相关度排序。
    pub candidates: Vec<AppleCandidate>,
    /// 这次实际用的 storefront（排障用）。
    pub storefront: String,
    /// storefront 是探测出来的（`true`）还是回落值（`false`）。
    pub storefront_probed: bool,
}

/// 用关键词搜 Apple Music catalog，拿候选曲目。
///
/// 这是**不依赖 AMLL 的 Apple id 入口**。AMLL TTML DB 的索引只覆盖网易曲库的一小部分
/// （实测 3586 条），绝大多数歌在那里查不到、也就没有 `appleMusicId` —— 于是 Apple
/// 那一级永远"跳过(无 appleId)"（真机日志实测）。改成自己搜，覆盖面交给 Apple 曲库本身。
///
/// 端点与参数对齐原项目 `Api.Search`：
/// `/v1/catalog/{storefront}/search?term=&types=songs&limit=&l=`。
/// `l=` 用**账号区域语言**（不是取词那个写死的 `zh-hans-cn`）—— 它影响结果的本地化与排序。
///
/// ⚠️ 这里**不替调用方挑歌**：返回多少个候选就原样交给 Kotlin 侧打分。匹配是纯计算，
/// 放在上层才好调（改一次不用重编 `.so`、不用重装 APK）。
pub async fn search(term: &str, limit: usize) -> Result<AppleSearchHit, String> {
    let media_token = {
        let st = STATE.lock().unwrap();
        st.media_user_token.clone()
    };
    let Some(media_token) = media_token else {
        return Err("no_media_user_token".into());
    };

    let place = resolve_place(&media_token).await;
    let url = format!(
        "{AMP_API}/v1/catalog/{}/search?term={}&types=songs&limit={}&l={}",
        place.storefront,
        urlencoding::encode(term),
        limit,
        urlencoding::encode(&place.language),
    );

    let mut resp = get_authorized(&url, &media_token, &place.accept_language).await?;
    if should_refresh_access_token(&resp) {
        invalidate_access_token();
        resp = get_authorized(&url, &media_token, &place.accept_language).await?;
    }

    if !(200..300).contains(&resp.status) {
        return Err(format!(
            "Apple 搜索返回 HTTP {}（sf={}）",
            resp.status, place.storefront
        ));
    }

    let value: Value =
        serde_json::from_str(&resp.body).map_err(|e| format!("Apple 搜索响应不是 JSON: {e}"))?;

    Ok(AppleSearchHit {
        candidates: parse_candidates(&value),
        storefront: place.storefront,
        storefront_probed: place.probed,
    })
}

/// 从 `/search` 响应里抠候选曲目（`results.songs.data`）。
///
/// 连 `attributes` 都没有的条目直接跳过 —— 那些是在当前区域不可播的占位结果，
/// 既没有歌名也没法匹配。
fn parse_candidates(value: &Value) -> Vec<AppleCandidate> {
    let Some(items) = value
        .get("results")
        .and_then(|r| r.get("songs"))
        .and_then(|s| s.get("data"))
        .and_then(|d| d.as_array())
    else {
        return Vec::new();
    };

    items
        .iter()
        .filter_map(|item| {
            let id = item.get("id").and_then(|v| v.as_str())?.to_string();
            let attrs = item.get("attributes")?;
            Some(AppleCandidate {
                id,
                name: str_attr(attrs, "name"),
                artist: str_attr(attrs, "artistName"),
                album: str_attr(attrs, "albumName"),
                duration_ms: attrs
                    .get("durationInMillis")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0),
                has_lyrics: attrs
                    .get("hasLyrics")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
            })
        })
        .collect()
}

/// 读 attributes 里的字符串字段；缺了给空串（Apple 对某些区域会省字段）。
fn str_attr(attrs: &Value, key: &str) -> String {
    attrs
        .get(key)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string()
}

// ---------------------------------------------------------------------------
// 开发者 token 的定位
// ---------------------------------------------------------------------------

/// 从 browse HTML 里找 `assets/index*.js`。
///
/// 两级：先排除 `index-legacy`，一个都没有才放宽到全部 —— 对齐原项目
/// `FindIndexScriptUrls`（legacy 是给老浏览器的降级包，有正常的就用正常的）。
fn find_index_scripts(html: &str) -> Vec<String> {
    let strict = scan_index_scripts(html, true);
    if !strict.is_empty() {
        strict
    } else {
        scan_index_scripts(html, false)
    }
}

fn scan_index_scripts(html: &str, exclude_legacy: bool) -> Vec<String> {
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
            if (!exclude_legacy || !url.contains("index-legacy")) && !out.contains(&url) {
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
