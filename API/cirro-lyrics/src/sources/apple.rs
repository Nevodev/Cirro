//! Apple Music 官方 TTML（取词链的第 1 优先源）。
//!
//! 实现对齐 **Lyricify-Lyrics-Helper** 的 `Providers/Web/AppleMusic/{Api,Response}.cs`
//! 与 `Providers/Web/BaseApi.cs`（2026-10-07 版本），**未登录那条路**对齐参考实现
//! `am_search_lyrics.py`（2026-10 实测）：
//!
//! 1. **开发者 access token** —— 抓 `https://music.apple.com/us/browse` 的 HTML，
//!    找到 `assets/index*.js`，在正文里扫 JWT，按 header `kid=WebPlayKid` /
//!    payload `iss=AMPWebPlay` 打分挑最像的（已过期的直接判死）。**与账号无关**。
//!
//!    🔴 **拿到后连同 `exp` 一起落盘**（目录由 Kotlin 下发，见 [`set_cache_dir`]）。
//!    下次（含冷启动）先看缓存：没过期就直接复用，不再抓那一百多 KB 的网页 + JS。
//!    缓存里那个「没过期但服务端不认」时不猜 —— 请求被判死（401 / 空 403）就
//!    [`invalidate_access_token`] 作废它（内存 + 磁盘一起）再重抓一次，见各调用点。
//! 2. **storefront / language** —— 已登录时带 `media-user-token` 问 `/v1/me/storefront`；
//!    失败回落 `us` / `en-US`（与原项目 `EnsureInitAsync` 的 catch 分支一致）。
//! 3. **搜索** —— `/v1/catalog/{storefront}/search?term=&types=songs&limit=&l=`（[`search`]）。
//!    这是**自己找 Apple id** 的入口：AMLL 只覆盖网易曲库一小部分，没命中就没有 `appleMusicId`，
//!    官方取词那一级直接"跳过(无 appleId)"。搜索不挑歌，候选交给 Kotlin 侧打分。
//!    🔴 **只要开发者 token 就能搜**（+ `Origin` 头），**未登录也能用**。
//! 4. **取词** —— 两条路，按有没有登录分岔（[`fetch`]）：
//!    - 已登录 → [`fetch_official`]：`/v1/catalog/{storefront}/songs/{id}?include[songs]=syllable-lyrics&l=zh-hans-cn&extend=ttmlLocalizations`
//!      🔴 **只要 `syllable-lyrics`**：逐字 TTML 在 `attributes.ttmlLocalizations`。
//!      原项目把「用 storefront 语言」那版注释掉了、写死 `zh-hans-cn`，这里照做。
//!    - 未登录 → [`fetch_third_party`]：走 `lyrics.paxsenix.org`（Lyrically API），
//!      按曲目 id 拿**同一份 Apple 同源 TTML**，零凭据。所以未登录也有官方级逐字词。
//!
//! 🔴 实测（2026-10-07）**只有开发者 token 是拿不到官方歌词的**：请求 200，但 `relationships`
//! 里只有 `albums` / `artists`，没有任何 `*-lyrics`。官方歌词属 Apple 订阅权益、必须带
//! `media-user-token`（用户 Apple Music 账号的登录令牌，与网易 cookie 无关）—— 所以
//! **未登录时改用第三方端点绕开这个凭据要求**（见 [`fetch_third_party`]）。
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
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::engine;

const BROWSE_URL: &str = "https://music.apple.com/us/browse";
const AMP_API: &str = "https://amp-api.music.apple.com";

/// 第三方取词（Lyrically API）：按 Apple 曲目 id 直接拿 Apple 同源 TTML，**不需要任何凭据**。
///
/// 这是**未登录 Apple Music 时的歌词来源**。官方歌词端点属订阅权益、必须
/// `media-user-token`（见模块头注释），而这个第三方端点只用曲目 id 就能拿到同一份
/// Apple TTML（带逐字时间轴）。端点与用法对齐参考实现 `am_search_lyrics.py`：
///   `GET /apple-music/lyrics?id={trackId}&ttml=true` → `{"type":"TTML","content":"<tt .../>"}`
///
/// ⚠️ 该服务对**未缓存**的曲目首次请求大概率 503（服务端现拉歌词），要重试。
const THIRD_PARTY_LYRICS: &str = "https://lyrics.paxsenix.org/apple-music/lyrics";

/// 第三方端点的最大尝试次数（含首次）。
///
/// 参考实现用 6 次 / 累计约 54 秒，那是 CLI；取词链上不能这么等（它会挡住后面的
/// AMLL / 网易回落）。这里收紧到 4 次、退避 2/4/6 秒 —— 覆盖「首次 503、第二次成功」
/// 这个主流情形，最坏多等约 12 秒。
const THIRD_PARTY_MAX_ATTEMPTS: usize = 4;

/// 未登录时默认搜索的商店区域。
///
/// 🔴 本项目实测：catalog 按 storefront 分区，**中文歌的官方词只在 `cn` 区有**，
/// 区域不对时这一级每一首都空手。未登录问不到账号区域，所以以 `cn` 起手；
/// 调用方（Kotlin 侧匹配器）判定「无够格候选」时会再传 `us` 兜一次。
const SEARCH_STOREFRONT_DEFAULT: &str = "cn";

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

/// 开发者 token 在磁盘上的缓存文件名（放在 Kotlin 下发的缓存目录里，见 [`set_cache_dir`]）。
const TOKEN_CACHE_FILE: &str = "apple_dev_token.json";

/// 复用缓存 token 时保留的安全余量（秒）。
///
/// 快到期（余量之内）就不再用它 —— 一个请求本身要在路上耗几秒，卡在边界上只会白白多一次
/// 「401 → 作废 → 重抓」的往返。
const TOKEN_MARGIN_SECS: u64 = 60;

/// JWT 里读不出 `exp` 时的兜底有效期（秒）。Apple 实际签发的是数小时，取 1 小时保守些。
const TOKEN_FALLBACK_TTL_SECS: u64 = 3600;

/// 配置 + 缓存（access token / storefront）。access token 抓一次能用一阵，别每首歌都抓。
#[derive(Default)]
struct AppleState {
    /// 用户提供的 Apple Music 登录令牌（明文只在这里，不下发）。
    media_user_token: Option<String>,
    /// 已抓到的开发者 token 与其到期时间（秒）。
    access_token: Option<String>,
    access_token_exp: u64,
    /// 开发者 token 的落盘目录（Kotlin 侧下发）。`None` = 不落盘，只在进程内存里缓存。
    cache_dir: Option<PathBuf>,
    /// 已**成功探测到**的 storefront / language。探测失败一律不写（见 [`resolve_place`]）。
    resolved_storefront: Option<String>,
    resolved_language: Option<String>,
}

static STATE: Lazy<Mutex<AppleState>> = Lazy::new(|| Mutex::new(AppleState::default()));

/// 抓开发者 token 的**单飞锁**。
///
/// 冷启动时可能有好几个取词请求同时进来（当前这首 + 预取的下一首），而每一次
/// [`ensure_access_token`] 落空都要抓一百多 KB 的网页 + JS。这把异步锁把并发收敛成一次
/// 抓取，后来者等锁拿到之后直接读缓存。
///
/// ⚠️ 用 `tokio::sync::Mutex` 而不是 `std` 的：这里必须**跨 await 持锁**。不额外增重 ——
/// `tokio` 的 `sync` 特性本来就被 `ncm-api-rs` 的 `features = ["full"]` 打开了。
static TOKEN_FETCH_GUARD: Lazy<tokio::sync::Mutex<()>> =
    Lazy::new(|| tokio::sync::Mutex::new(()));

/// 一次 Apple 取词的结果。
pub struct AppleFetch {
    /// TTML 文本；空串表示这次没拿到（原因见 `reason`）。
    pub ttml: String,
    /// 没拿到时的原因，给日志用：`no_lyrics`。
    pub reason: String,
    /// 这次实际用的 storefront。**排障关键**：区域取错是「每一首都空手」的头号原因
    /// —— 实测同一首中文歌在 `cn` 区有逐字词，在 `us` 区是零歌词。
    ///
    /// ⚠️ 走第三方（[`fetch_third_party`]）时为空串 —— 那条路只用曲目 id、与区域无关。
    pub storefront: String,
    /// storefront 是**探测出来的**（`true`）还是回落值（`false`）。回落意味着
    /// `/v1/me/storefront` 没成功，那种情况下几乎必然零歌词。日志里 `sf=us!` 的叹号指这个。
    pub storefront_probed: bool,
    /// 这份歌词的来源：`official`（带 `media-user-token` 直连 amp-api）或
    /// `paxsenix`（未登录时用第三方端点）。日志用。
    pub via: String,
}

/// 下发开发者 token 的落盘目录（App 启动时由 Kotlin 调一次）。
///
/// 目录来自 `context.filesDir`（原生层拿不到 Context，只能由上层给）。给完立刻把磁盘上的
/// 缓存**读进内存** —— 这样冷启动第一次取词就直接命中，不用再抓网页。
///
/// 空串 = 不落盘（只在内存里缓存），用于测试。
///
/// 目录不存在时会在首次写入时创建；读不到 / 解不开 / 结构不对一律当没有，**绝不因此报错**
/// —— 缓存坏了最多多抓一次 token，不该影响取词。
pub fn set_cache_dir(dir: String) {
    let dir = PathBuf::from(dir.trim());
    if dir.as_os_str().is_empty() {
        return;
    }
    let cached = read_token_cache(&dir);
    let mut st = STATE.lock().unwrap();
    st.cache_dir = Some(dir);
    // 内存里已经有更新的 token 就别被磁盘上的旧值盖掉。
    if st.access_token.is_none() {
        if let Some((token, exp)) = cached.filter(|(_, exp)| is_fresh(*exp)) {
            st.access_token = Some(token);
            st.access_token_exp = exp;
        }
    }
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

/// 取一首歌的 Apple 歌词。
///
/// 两条路，按**有没有登录 Apple Music** 分岔（对齐参考实现 `am_search_lyrics.py`）：
/// - 登录了（有 `media-user-token`）→ [`fetch_official`]：直连 amp-api，逐字 TTML，质量最高；
/// - 没登录 → [`fetch_third_party`]：走第三方端点 `lyrics.paxsenix.org`，同一份 Apple TTML，
///   但需要重试（服务端现拉）。
///
/// 注意：**没登录也能取到 Apple 词**是这次新增的能力 —— 在此之前这一级没凭据就直接跳过。
pub async fn fetch(am_id: &str) -> Result<AppleFetch, String> {
    let media_token = {
        let st = STATE.lock().unwrap();
        st.media_user_token.clone()
    };
    match media_token {
        Some(token) => fetch_official(am_id, &token).await,
        None => fetch_third_party(am_id).await,
    }
}

/// 已登录：直连 amp-api 的官方逐字 TTML（需 `media-user-token`）。
async fn fetch_official(am_id: &str, media_token: &str) -> Result<AppleFetch, String> {
    let place = resolve_place(media_token).await;
    let url = format!(
        "{AMP_API}/v1/catalog/{}/songs/{am_id}\
         ?include[songs]=syllable-lyrics&l={}&extend=ttmlLocalizations",
        place.storefront,
        urlencoding::encode(LYRIC_LANGUAGE)
    );

    let mut resp = get_authorized(&url, Some(media_token), &place.accept_language).await?;

    // 开发者 token 过期的话，打掉重抓一次再试（原项目 `GetAsyncWithAccessTokenRetry`）
    if should_refresh_access_token(&resp) {
        invalidate_access_token();
        resp = get_authorized(&url, Some(media_token), &place.accept_language).await?;
    }

    if !(200..300).contains(&resp.status) {
        return Err(format!(
            "Apple catalog 返回 HTTP {}（sf={}）",
            resp.status, place.storefront
        ));
    }

    let value: Value =
        serde_json::from_str(&resp.body).map_err(|e| format!("Apple 响应不是 JSON: {e}"))?;
    Ok(match extract_ttml(&value) {
        Some(ttml) => AppleFetch {
            ttml,
            reason: String::new(),
            storefront: place.storefront,
            storefront_probed: place.probed,
            via: "official".into(),
        },
        None => AppleFetch {
            ttml: String::new(),
            reason: "no_lyrics".into(),
            storefront: place.storefront,
            storefront_probed: place.probed,
            via: "official".into(),
        },
    })
}

/// 未登录：按曲目 id 问第三方端点要 Apple 同源 TTML。
///
/// 只认 `ttml=true` 那一档（`{"type":"TTML","content":...}`）—— 非 TTML 的那档是
/// paxsenix 自己的 JSON 结构，解析它还得再写一套模型，不值得：我们需要的就是 TTML。
///
/// 状态码处理对齐参考实现：503 = 服务端忙（现拉歌词），退避重试；404 = 上游确实没有这首歌的词。
/// 与参考实现的一处**有意收紧**：最多 [`THIRD_PARTY_MAX_ATTEMPTS`] 次、退避 2/4/6 秒
/// （参考脚本是 6 次 / 最长 54 秒），因为取词链不能被这一级长时间挡住。
async fn fetch_third_party(am_id: &str) -> Result<AppleFetch, String> {
    let miss = |reason: &str| AppleFetch {
        ttml: String::new(),
        reason: reason.to_string(),
        storefront: String::new(),
        storefront_probed: false,
        via: "paxsenix".into(),
    };

    for attempt in 0..THIRD_PARTY_MAX_ATTEMPTS {
        let resp = engine::HTTP
            .get(THIRD_PARTY_LYRICS)
            .query(&[("id", am_id), ("ttml", "true")])
            .header("User-Agent", APPLE_UA)
            .timeout(FETCH_TIMEOUT)
            .send()
            .await
            .map_err(|e| format!("第三方歌词请求失败: {e}"))?;

        let status = resp.status().as_u16();
        match status {
            200 => {
                let body = resp
                    .text()
                    .await
                    .map_err(|e| format!("读第三方歌词失败: {e}"))?;
                let value: Value = serde_json::from_str(&body)
                    .map_err(|e| format!("第三方歌词响应不是 JSON: {e}"))?;
                let is_ttml = value.get("type").and_then(|t| t.as_str()) == Some("TTML");
                let ttml = value.get("content").and_then(|c| c.as_str()).unwrap_or("");
                // 与官方那条同判据：没有 begin=/end= 的时间轴等于没词。
                if is_ttml && ttml.contains("begin=") && ttml.contains("end=") {
                    return Ok(AppleFetch {
                        ttml: ttml.to_string(),
                        reason: String::new(),
                        storefront: String::new(),
                        storefront_probed: false,
                        via: "paxsenix".into(),
                    });
                }
                return Ok(miss("no_lyrics"));
            }
            // 上游没有这首歌的 TTML（华语区覆盖有限，欧美主流歌基本都有）
            404 => return Ok(miss("no_lyrics")),
            503 => {
                if attempt + 1 < THIRD_PARTY_MAX_ATTEMPTS {
                    let wait = 2 * (attempt as u64 + 1);
                    tokio::time::sleep(Duration::from_secs(wait)).await;
                    continue;
                }
                return Err(format!(
                    "第三方歌词服务忙（连续 {THIRD_PARTY_MAX_ATTEMPTS} 次 503）"
                ));
            }
            other => return Err(format!("第三方歌词返回 HTTP {other}")),
        }
    }
    Err("第三方歌词重试耗尽".into())
}

/// 一次请求的原始结果。带 content-type / length 是为了判 403 的性质。
struct AppleResponse {
    status: u16,
    body: String,
    content_type: String,
    content_length: u64,
}

/// 发一次请求。
///
/// 请求头对齐原项目：固定 UA + `Referer: https://music.apple.com/` + `Origin` + ...
/// 原项目的 `BaseApi.SetRequestHeaders` 对每个请求都挂 UA / Referer，
/// 抓 browse 和 JS 那两跳也不例外。
///
/// `media_token` 为 `None` 表示**未登录**：这时只带开发者 token + `Origin`
/// （与 `music.apple.com` 网页播放器同款），catalog 搜索照样可用。
async fn get_authorized(
    url: &str,
    media_token: Option<&str>,
    accept_language: &str,
) -> Result<AppleResponse, String> {
    let access = ensure_access_token().await?;
    let mut req = engine::HTTP
        .get(url)
        .header("User-Agent", APPLE_UA)
        .header("Referer", APPLE_REFERER)
        .header("Origin", "https://music.apple.com")
        .header("Accept", "application/json")
        .header("Accept-Language", accept_language)
        .header("Authorization", format!("Bearer {access}"));
    if let Some(token) = media_token {
        req = req.header("media-user-token", token);
    }
    let resp = req
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

impl Place {
    /// 未登录时的默认区域：问不到账号区域，按项目实测以 `cn` 起手（见
    /// [`SEARCH_STOREFRONT_DEFAULT`]）。
    ///
    /// `probed = false` —— 它不是探测出来的。但含义与 [`resolve_place`] 的回落值不同：
    /// 那是「本该探测成功却失败了」，这里是「本来就没有账号可探」，属正常。
    fn unlogged_default() -> Place {
        Place::for_storefront(SEARCH_STOREFRONT_DEFAULT)
    }

    /// 调用方**指定**的商店区域（未登录时 `cn` 搜不到够格候选、拿 `us` 兜一次用）。
    ///
    /// 界面语言按区域配（见 [`search_language`]）—— 搜索的 `l=` 只影响结果的本地化与排序，
    /// 与取词的 `l=` 是两回事（后者写死 `zh-hans-cn`）。
    fn for_storefront(storefront: &str) -> Place {
        let language = search_language(storefront);
        Place {
            storefront: storefront.to_string(),
            accept_language: format!("{language},en;q=0.9"),
            language: language.to_string(),
            probed: false,
        }
    }
}

/// 给 storefront 配一个界面语言，**只用于搜索的 `l=`**。
///
/// ⚠️ 取词的 `l=` 不用这个（那边写死 `zh-hans-cn` 是为了拿中文翻译，不能用美区的 `en-US`）。
fn search_language(storefront: &str) -> &'static str {
    match storefront {
        "cn" => "zh-Hans-CN",
        "tw" => "zh-Hant-TW",
        "hk" => "zh-Hant-HK",
        "jp" => "ja-JP",
        "kr" => "ko-KR",
        "gb" => "en-GB",
        _ => "en-US",
    }
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
    let url = format!("{AMP_API}/v1/me/storefront");
    if let Ok(resp) = get_authorized(&url, Some(media_token), "en-US").await {
        // 这个端点也会被开发者 token 拖累：缓存里那份「论时间没过期」但服务端已经判死时，
        // 这里同样要作废重抓 —— 不然探测直接落空，取词被钉死在回落区（见函数注释）。
        let resp = if should_refresh_access_token(&resp) {
            invalidate_access_token();
            get_authorized(&url, Some(media_token), "en-US")
                .await
                .unwrap_or(resp)
        } else {
            resp
        };
        if (200..300).contains(&resp.status) {
            if let Ok(v) = serde_json::from_str::<Value>(&resp.body) {
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

/// 拿到可用的开发者 access token：先吃缓存，落空才现抓。
///
/// 缓存顺序是「进程内存 → 磁盘（[`set_cache_dir`] 落的那份）」。磁盘缓存让**冷启动**也
/// 不用再抓一百多 KB 的网页 + JS。
///
/// 单飞：并发调用只会真抓一次，其余的在这把锁上等，拿到锁后直接读缓存返回。
async fn ensure_access_token() -> Result<String, String> {
    if let Some(token) = cached_access_token() {
        return Ok(token);
    }

    let _guard = TOKEN_FETCH_GUARD.lock().await;
    // 等锁这段时间里可能已经有人抓好并写进缓存了 —— 不复查就会重复抓一遍。
    if let Some(token) = cached_access_token() {
        return Ok(token);
    }

    let token = scrape_access_token().await?;
    let exp = jwt_exp(&token).unwrap_or_else(|| now_secs() + TOKEN_FALLBACK_TTL_SECS);
    // 先落内存（立刻可用），再落盘；文件 IO 不持 STATE 锁。
    let dir = {
        let mut st = STATE.lock().unwrap();
        st.access_token = Some(token.clone());
        st.access_token_exp = exp;
        st.cache_dir.clone()
    };
    let _ = write_token_cache(dir.as_deref(), &token, exp);
    Ok(token)
}

/// 内存里缓存的 token（还没到余量线才算数）。`None` = 得现抓。
fn cached_access_token() -> Option<String> {
    let st = STATE.lock().unwrap();
    match &st.access_token {
        Some(token) if is_fresh(st.access_token_exp) => Some(token.clone()),
        _ => None,
    }
}

/// `exp` 是否还留有余量（见 [`TOKEN_MARGIN_SECS`]）。
fn is_fresh(exp: u64) -> bool {
    exp > now_secs() + TOKEN_MARGIN_SECS
}

/// 作废开发者 token：**内存 + 磁盘一起清**。
///
/// 调用点都是「这个 token 论过期时间还能用、但服务端不认了」（401 / 空 403，见
/// [`should_refresh_access_token`]）。磁盘那份必须一起删 —— 否则下次冷启动又把同一个
/// 坏 token 读回来，白跑一轮。
fn invalidate_access_token() {
    let dir = {
        let mut st = STATE.lock().unwrap();
        st.access_token = None;
        st.access_token_exp = 0;
        st.cache_dir.clone()
    };
    if let Some(dir) = dir {
        let _ = std::fs::remove_file(dir.join(TOKEN_CACHE_FILE));
    }
}

/// 读磁盘上的 token 缓存。没文件 / 坏 JSON / 缺字段一律当没有（返回 `None`）。
fn read_token_cache(dir: &Path) -> Option<(String, u64)> {
    let text = std::fs::read_to_string(dir.join(TOKEN_CACHE_FILE)).ok()?;
    let value: Value = serde_json::from_str(&text).ok()?;
    let token = value.get("token")?.as_str()?.trim().to_string();
    let exp = value.get("exp")?.as_u64()?;
    (!token.is_empty()).then_some((token, exp))
}

/// 写 token 缓存。**先写临时文件再 rename** —— 写一半被杀掉也不会留下半截 JSON
/// 被下次当成有效缓存（rename 在同一目录内是原子的）。
///
/// `dir` 为 `None`（上层没下发目录）时什么都不做，且**不算错误**。
fn write_token_cache(dir: Option<&Path>, token: &str, exp: u64) -> Result<(), String> {
    let Some(dir) = dir else {
        return Ok(());
    };
    std::fs::create_dir_all(dir).map_err(|e| format!("建 token 缓存目录失败: {e}"))?;
    // 存 `savedAt` 只为排障时肉眼能看出来这份缓存是什么时候落的。
    let body = serde_json::json!({ "token": token, "exp": exp, "savedAt": now_secs() });
    let tmp = dir.join(format!("{TOKEN_CACHE_FILE}.tmp"));
    std::fs::write(&tmp, body.to_string()).map_err(|e| format!("写 token 缓存失败: {e}"))?;
    std::fs::rename(&tmp, dir.join(TOKEN_CACHE_FILE)).map_err(|e| format!("替换 token 缓存失败: {e}"))
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
    pub artists: Vec<AppleArtist>,
}

pub struct AppleArtist {
    pub id: String,
    pub name: String,
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
/// 🔴 **不需要 `media-user-token`**：只靠从网页抓来的开发者 token + `Origin` 头即可，
/// 与 `music.apple.com` 网页播放器同源（参考实现 `am_search_lyrics.py` 实测）。
/// 所以**未登录也能搜**，这也是这次新增能力的关键。
///
/// `storefront`：
/// - `Some("cn")` 等 —— 调用方指定区域（未登录时 `cn` 搜不到够格候选、拿 `us` 兜一次用）；
/// - `None` / 空串 —— 自动：已登录问账号区域（[`resolve_place`]），未登录用
///   [`SEARCH_STOREFRONT_DEFAULT`]。
///
/// ⚠️ 这里**不替调用方挑歌**：返回多少个候选就原样交给 Kotlin 侧打分。匹配是纯计算，
/// 放在上层才好调（改一次不用重编 `.so`、不用重装 APK）。
#[derive(Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistHeader {
    pub video_url: Option<String>,
    pub name_logo: Option<ArtistNameLogo>,
}

#[derive(serde::Serialize)]
pub struct ArtistNameLogo {
    pub url: String,
    pub width: i32,
    pub height: i32,
}

pub async fn artist_header(id: &str, storefront: &str) -> Result<ArtistHeader, String> {
    if id.is_empty() || !id.bytes().all(|c| c.is_ascii_digit()) {
        return Err("Apple 艺人 id 无效".into());
    }
    let media_token = STATE.lock().unwrap().media_user_token.clone();
    let place = Place::for_storefront(storefront);
    let url = format!(
        "{AMP_API}/v1/catalog/{}/artists/{id}?extend=editorialVideo,editorialArtwork",
        place.storefront,
    );
    let mut response = get_authorized(&url, media_token.as_deref(), &place.accept_language).await?;
    if should_refresh_access_token(&response) {
        invalidate_access_token();
        response = get_authorized(&url, media_token.as_deref(), &place.accept_language).await?;
    }
    if response.status == 404 {
        return Ok(ArtistHeader::default());
    }
    if !(200..300).contains(&response.status) {
        return Err(format!("Apple 艺人资料返回 HTTP {}", response.status));
    }
    let value: Value = serde_json::from_str(&response.body)
        .map_err(|error| format!("Apple 艺人响应不是 JSON: {error}"))?;
    Ok(ArtistHeader {
        video_url: parse_artist_video(&value),
        name_logo: parse_artist_name_logo(&value),
    })
}

fn parse_artist_video(value: &Value) -> Option<String> {
    value
        .pointer("/data/0/attributes/editorialVideo/motionArtistSquare1x1/video")
        .and_then(Value::as_str)
        .filter(|url| url.starts_with("https://"))
        .map(str::to_string)
}

fn parse_artist_name_logo(value: &Value) -> Option<ArtistNameLogo> {
    let artwork = value.pointer("/data/0/attributes/editorialArtwork/musicContentColorLogoTrimmed")?;
    let url = artwork.get("url")?.as_str()?.to_string();
    let width = i32::try_from(artwork.get("width")?.as_u64()?).ok()?;
    let height = i32::try_from(artwork.get("height")?.as_u64()?).ok()?;
    if !url.starts_with("https://") || width <= 0 || height <= 0 {
        return None;
    }
    Some(ArtistNameLogo { url, width, height })
}

pub async fn search(
    term: &str,
    limit: usize,
    storefront: Option<&str>,
) -> Result<AppleSearchHit, String> {
    search_catalog(term, limit, storefront, false).await
}

pub async fn search_with_artists(
    term: &str,
    limit: usize,
    storefront: Option<&str>,
) -> Result<AppleSearchHit, String> {
    search_catalog(term, limit, storefront, true).await
}

async fn search_catalog(
    term: &str,
    limit: usize,
    storefront: Option<&str>,
    include_artists: bool,
) -> Result<AppleSearchHit, String> {
    let media_token = {
        let st = STATE.lock().unwrap();
        st.media_user_token.clone()
    };

    let place = match storefront.map(str::trim).filter(|s| !s.is_empty()) {
        Some(sf) => Place::for_storefront(sf),
        None => match &media_token {
            Some(token) => resolve_place(token).await,
            None => Place::unlogged_default(),
        },
    };

    let mut url = format!(
        "{AMP_API}/v1/catalog/{}/search?term={}&types=songs&limit={}&l={}",
        place.storefront,
        urlencoding::encode(term),
        limit,
        urlencoding::encode(&place.language),
    );
    if include_artists {
        url.push_str("&include[songs]=artists");
    }

    let mut resp = get_authorized(&url, media_token.as_deref(), &place.accept_language).await?;
    if should_refresh_access_token(&resp) {
        invalidate_access_token();
        resp = get_authorized(&url, media_token.as_deref(), &place.accept_language).await?;
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
                artists: item
                    .pointer("/relationships/artists/data")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .filter_map(|artist| {
                        if artist.get("type").and_then(Value::as_str) != Some("artists") {
                            return None;
                        }
                        let id = artist.get("id").and_then(Value::as_str)?;
                        if id.is_empty() || !id.bytes().all(|c| c.is_ascii_digit()) {
                            return None;
                        }
                        Some(AppleArtist {
                            id: id.to_string(),
                            name: artist
                                .pointer("/attributes/name")
                                .and_then(Value::as_str)
                                .unwrap_or("")
                                .to_string(),
                        })
                    })
                    .collect(),
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

#[cfg(test)]
mod artist_search_tests {
    use super::*;
    use serde_json::json;

    #[test]
    #[ignore = "Inspects live Apple artist motion artwork"]
    fn live_artist_motion_metadata() {
        crate::engine::RUNTIME.block_on(async {
            let header = artist_header("1065981054", "us").await.unwrap();
            let video = header.video_url.unwrap();
            let response = crate::engine::HTTP.get(&video).send().await.unwrap();
            assert!(response.status().is_success());
            let playlist = response.text().await.unwrap();
            assert!(playlist.starts_with("#EXTM3U"));
            assert!(playlist.contains("RESOLUTION="));
            println!("Square artist video: HLS master playlist available");
        });
    }

    #[test]
    #[ignore = "Inspects live Apple artist name logos"]
    fn live_artist_name_logo_metadata() {
        crate::engine::RUNTIME.block_on(async {
            let header = artist_header("159260351", "us").await.unwrap();
            let logo = header.name_logo.unwrap();
            assert!(logo.url.contains("{w}") && logo.url.contains("{h}"));
            let scale = (480.0 / logo.width as f64).min(160.0 / logo.height as f64).min(1.0);
            let width = (logo.width as f64 * scale).round() as u32;
            let height = (logo.height as f64 * scale).round() as u32;
            let (base, _) = logo.url.rsplit_once('.').unwrap();
            let image_url = format!("{base}.png").replace("{w}", &width.to_string()).replace("{h}", &height.to_string());
            let response = crate::engine::HTTP.get(&image_url).send().await.unwrap();
            assert!(response.status().is_success());
            let bytes = response.bytes().await.unwrap();
            assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");
            let image_width = u32::from_be_bytes(bytes[16..20].try_into().unwrap());
            let image_height = u32::from_be_bytes(bytes[20..24].try_into().unwrap());
            assert!(image_width <= 480 && image_height <= 160);
            println!("Artist name logo: original {}x{}, requested {width}x{height}, PNG {image_width}x{image_height}, {} bytes, color type {}",
                logo.width, logo.height, bytes.len(), bytes[25]);
        });
    }

    #[test]
    fn only_accepts_square_motion_artwork() {
        let response = json!({"data":[{"attributes":{"editorialVideo":{
            "motionArtistWide16x9":{"video":"https://example.com/wide.m3u8"},
            "motionArtistSquare1x1":{"video":"https://example.com/square.m3u8"}
        }}}]});
        assert_eq!(parse_artist_video(&response).as_deref(), Some("https://example.com/square.m3u8"));
        let wide_only = json!({"data":[{"attributes":{"editorialVideo":{
            "motionArtistWide16x9":{"video":"https://example.com/wide.m3u8"}
        }}}]});
        assert!(parse_artist_video(&wide_only).is_none());
        assert!(parse_artist_video(&json!({"data":[]})).is_none());
    }

    #[test]
    fn parses_trimmed_color_logo_independently_of_video() {
        let response = json!({"data":[{"attributes":{"editorialArtwork":{
            "musicContentColorLogoTrimmed": {
                "url":"https://example.com/logo/{w}x{h}.{f}",
                "width": 30000, "height": 5000
            },
            "musicContentWhiteLogo": {
                "url":"https://example.com/other.png", "width":100, "height":100
            }
        }}}]});
        let logo = parse_artist_name_logo(&response).unwrap();
        assert_eq!(logo.width, 30000);
        assert_eq!(logo.height, 5000);
        assert_eq!(logo.url, "https://example.com/logo/{w}x{h}.{f}");
        assert!(parse_artist_video(&response).is_none());
        let mut invalid = response.clone();
        invalid["data"][0]["attributes"]["editorialArtwork"]["musicContentColorLogoTrimmed"]["width"] = json!(0);
        assert!(parse_artist_name_logo(&invalid).is_none());
        assert!(parse_artist_name_logo(&json!({"data":[]})).is_none());
    }

    #[test]
    fn preserves_artist_ids_and_names_without_requiring_them_for_lyrics() {
        let response = json!({"results":{"songs":{"data":[{
            "id":"100", "attributes":{"name":"Song", "artistName":"Artist"},
            "relationships":{"artists":{"data":[
                {"id":"42","type":"artists","attributes":{"name":"Artist"}},
                {"id":"43","type":"artists"},
                {"id":"bad","type":"artists"},
                {"id":"44","type":"albums"}
            ]}}
        }, {"id":"101", "attributes":{"name":"Legacy song"}}]}}});
        let songs = parse_candidates(&response);
        assert_eq!(songs.len(), 2);
        assert_eq!(songs[0].artists.len(), 2);
        assert_eq!(songs[0].artists[0].id, "42");
        assert_eq!(songs[0].artists[0].name, "Artist");
        assert_eq!(songs[0].artists[1].name, "");
        assert!(songs[1].artists.is_empty());
    }

    #[test]
    #[ignore = "Queries the live Apple Music catalog"]
    fn live_search_includes_artist_relationships() {
        crate::engine::RUNTIME.block_on(async {
            let mut common = std::collections::HashSet::new();
            for (index, term) in ["bad guy Billie Eilish", "Happier Than Ever Billie Eilish"]
                .iter()
                .enumerate()
            {
                let hit = search_with_artists(term, 12, Some("us")).await.unwrap();
                let artists: std::collections::HashSet<_> = hit
                    .candidates
                    .iter()
                    .flat_map(|song| &song.artists)
                    .filter(|artist| artist.name == "Billie Eilish")
                    .map(|artist| artist.id.clone())
                    .collect();
                assert!(!artists.is_empty(), "No named artist relationships for {term}");
                println!("{term}: storefront={}, artist IDs={artists:?}", hit.storefront);
                common = if index == 0 {
                    artists
                } else {
                    common.intersection(&artists).cloned().collect()
                };
            }
            assert_eq!(common.len(), 1);
        });
    }
}
