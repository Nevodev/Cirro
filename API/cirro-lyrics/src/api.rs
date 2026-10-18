//! 返回给 Kotlin 的 JSON 门面。
//!
//! 这里是**唯一**把结果包成 `{"ok":...}` 信封的地方；JNI 层只做字符串搬运。
//! 约定与 `ncm-android` 一致：成败都走正常返回，异常不跨 JNI 边界。
//!
//! 分两个字段域：
//! - `ok:false` + `message` —— 请求本身失败（网络、超时、上游报错）；
//! - `ok:true` 但内容为空 / `found:false` —— 这一级就是没有词，**属于正常**，调用方往下回落。

use serde_json::{json, Value};

use crate::engine;
use crate::sources::{amll, apple, lrclib, netease};

fn fail(message: String) -> Value {
    json!({ "ok": false, "message": message })
}

// ---------------------------------------------------------------------------
// 凭据
// ---------------------------------------------------------------------------

/// 设置网易登录 cookie（与 `NcmBridge.setCookie` 同进同退）。
pub fn set_cookie(cookie: String) -> i32 {
    if cookie.trim().is_empty() {
        return -1;
    }
    engine::set_ncm_cookie(cookie);
    0
}

/// 清空网易登录态。
pub fn clear_cookie() {
    engine::clear_ncm_cookie();
}

/// 配置 Apple Music 凭据。`media_user_token` 传空串等于关闭 Apple 这一级。
///
/// 只有 token —— storefront / language 由原生层问 `/v1/me/storefront` 自己拿，
/// 不开放给上层（对齐原项目：那边对外也只有 `SetMediaUserToken`）。
pub fn set_apple_token(media_user_token: String) -> i32 {
    apple::set_config(media_user_token);
    0
}

/// 是否已配置 Apple 凭据。
pub fn apple_configured() -> bool {
    apple::is_configured()
}

/// 下发**开发者 token 的落盘缓存目录**（App 启动时调一次，值来自 `context.filesDir`）。
///
/// 开发者 token 与账号无关，抓一次能用几小时；把它连同 `exp` 缓存下来，是为了让
/// **冷启动**也不必再抓那个一百多 KB 的网页 + JS。空串 = 不落盘（只在进程内存里缓存）。
pub fn set_apple_cache_dir(dir: String) {
    apple::set_cache_dir(dir);
}

// ---------------------------------------------------------------------------
// 四个取词源
// ---------------------------------------------------------------------------

/// 网易 LRC（只逐行）。
pub async fn netease_lyric(id: String) -> Value {
    match netease::fetch(&id).await {
        Ok(l) => json!({
            "ok": true,
            "empty": l.is_empty(),
            "lrc": l.lrc,
            "translation": l.translation,
            "roman": l.roman,
        }),
        Err(e) => fail(e),
    }
}

/// AMLL TTML DB：一次请求同时拿 TTML 与 `appleMusicId`。
pub async fn amll_ttml(ncm_id: String) -> Value {
    match amll::fetch(&ncm_id).await {
        Ok(None) => json!({ "ok": true, "found": false }),
        Ok(Some(hit)) => json!({
            "ok": true,
            "found": true,
            "ttml": hit.ttml,
            "base": hit.base,
            "appleMusicIds": amll::apple_music_ids(&hit.ttml),
        }),
        Err(e) => fail(e),
    }
}

/// Apple Music 取词。已登录走官方直连、未登录走第三方（`lyrics.paxsenix.org`），
/// 由原生层按有没有 `media-user-token` 自己分岔 —— 调用方不必关心。
///
/// 一并带上 `via`（`official` / `paxsenix`）、`storefront` / `storefrontProbed`：
/// 区域取错会让官方那条路**每一首都空手**，而 `reason` 只会写 `no_lyrics` ——
/// 没有这些字段就分不清"这首歌没有官方词"和"区域取错了"。
pub async fn apple_ttml(am_id: String) -> Value {
    match apple::fetch(&am_id).await {
        Ok(f) => json!({
            "ok": true,
            "ttml": f.ttml,
            "reason": f.reason,
            "via": f.via,
            "storefront": f.storefront,
            "storefrontProbed": f.storefront_probed,
        }),
        Err(e) => fail(e),
    }
}

/// Apple Music 搜索：用「歌名 歌手」换候选曲目，**不依赖 AMLL**。
///
/// 存在的理由：Apple 官方取词需要一个 Apple 歌曲 id，而之前这个 id 只能从 AMLL 的
/// `amll:meta` 里拿 —— AMLL 索引只有网易曲库的一小部分，没命中的歌就永远够不到官方词。
///
/// 🔴 **不需要登录**：只要从网页抓来的开发者 token（[`apple::search`]），未登录照样能搜。
///
/// `limit` / `storefront` 都是字符串（JNI 侧一律传字符串）：`limit` 空/非法走默认值；
/// `storefront` 空 = 自动（已登录用账号区域、未登录用默认区域），非空 = 显式指定
/// （未登录时 `cn` 搜不到够格候选、拿 `us` 兜一次用）。
pub async fn apple_search(term: String, limit: String, storefront: String) -> Value {
    apple_search_request(term, limit, storefront, false).await
}

pub async fn apple_artist_header(id: String, storefront: String) -> Value {
    match apple::artist_header(&id, &storefront).await {
        Ok(header) => json!({"ok": true, "header": header}),
        Err(error) => fail(error),
    }
}

pub async fn apple_artist_search(term: String, limit: String, storefront: String) -> Value {
    apple_search_request(term, limit, storefront, true).await
}

async fn apple_search_request(
    term: String,
    limit: String,
    storefront: String,
    include_artists: bool,
) -> Value {
    let limit = limit
        .trim()
        .parse::<usize>()
        .unwrap_or(SEARCH_LIMIT_DEFAULT)
        .clamp(1, SEARCH_LIMIT_MAX);
    let storefront = storefront.trim();
    let storefront = if storefront.is_empty() {
        None
    } else {
        Some(storefront)
    };

    let result = if include_artists {
        apple::search_with_artists(&term, limit, storefront).await
    } else {
        apple::search(&term, limit, storefront).await
    };
    match result {
        Ok(hit) => json!({
            "ok": true,
            "storefront": hit.storefront,
            "storefrontProbed": hit.storefront_probed,
            "songs": hit
                .candidates
                .iter()
                .map(|c| {
                    json!({
                        "id": c.id,
                        "name": c.name,
                        "artist": c.artist,
                        "album": c.album,
                        "durationMs": c.duration_ms,
                        "hasLyrics": c.has_lyrics,
                        "artists": c.artists.iter().map(|artist| json!({
                            "id": artist.id,
                            "name": artist.name,
                        })).collect::<Vec<_>>(),
                    })
                })
                .collect::<Vec<_>>(),
        }),
        Err(e) => fail(e),
    }
}

/// 搜索默认取几个候选。匹配是本地打分，多给几个不影响耗时，但少了容易漏。
const SEARCH_LIMIT_DEFAULT: usize = 12;
const SEARCH_LIMIT_MAX: usize = 25;

/// lrclib 兜底。`duration_sec` 是秒的字符串，空串 / 非法值按「不带时长查」处理。
pub async fn lrclib_lyric(
    track_name: String,
    artist_name: String,
    album_name: String,
    duration_sec: String,
) -> Value {
    let duration = duration_sec.trim().parse::<f64>().ok();
    match lrclib::fetch(&track_name, &artist_name, &album_name, duration).await {
        Ok(None) => json!({ "ok": true, "found": false }),
        Ok(Some(hit)) => json!({
            "ok": true,
            "found": true,
            "empty": hit.is_empty(),
            "id": hit.id,
            "trackName": hit.track_name,
            "artistName": hit.artist_name,
            "albumName": hit.album_name,
            "duration": hit.duration_sec,
            "instrumental": hit.instrumental,
            "syncedLyrics": hit.synced,
            "plainLyrics": hit.plain,
        }),
        Err(e) => fail(e),
    }
}
