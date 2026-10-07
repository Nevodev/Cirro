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
pub fn set_apple_token(media_user_token: String, storefront: String, language: String) -> i32 {
    apple::set_config(
        media_user_token,
        Some(storefront).filter(|s| !s.trim().is_empty()),
        Some(language).filter(|s| !s.trim().is_empty()),
    );
    0
}

/// 是否已配置 Apple 凭据。
pub fn apple_configured() -> bool {
    apple::is_configured()
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

/// Apple Music 官方 TTML（需 media-user-token）。
pub async fn apple_ttml(am_id: String) -> Value {
    match apple::fetch(&am_id).await {
        Ok(f) => json!({ "ok": true, "ttml": f.ttml, "reason": f.reason }),
        Err(e) => fail(e),
    }
}

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
