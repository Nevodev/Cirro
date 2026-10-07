//! lrclib（取词链的最后一级兜底）。
//!
//! 接口（实测 2026-10-07）：`GET https://lrclib.net/api/get?track_name=&artist_name=&album_name=&duration=`
//!
//! 响应键：`id / trackName / artistName / albumName / duration / instrumental /
//! syncedLyrics / plainLyrics / hasWordSync / lyricsfile`。
//!
//! ⚠️ `syncedLyrics` **可能是空的**（实测「祝福 / YOASOBI」「夜に駆ける / YOASOBI」都只有
//! `plainLyrics`，而「Hello / Adele」有 2523 字节的同步歌词）。只有 `plainLyrics` 时
//! 就没有任何时间轴，落到模型里是 `SyncLevel.PLAIN`，由渲染层按静态文本处理。

use serde_json::Value;

use crate::engine;

const BASE: &str = "https://lrclib.net/api";

/// 一次命中。
#[derive(Debug, Clone, Default)]
pub struct LrclibHit {
    pub id: i64,
    pub track_name: String,
    pub artist_name: String,
    pub album_name: String,
    pub duration_sec: f64,
    pub instrumental: bool,
    /// 带时间轴的 LRC；可能为空。
    pub synced: String,
    /// 纯文本歌词；可能为空。
    pub plain: String,
}

/// 按曲目信息取词。查不到（404）返回 `Ok(None)` —— 这不是错误。
pub async fn fetch(
    track_name: &str,
    artist_name: &str,
    album_name: &str,
    duration_sec: Option<f64>,
) -> Result<Option<LrclibHit>, String> {
    let mut url = format!(
        "{BASE}/get?track_name={}&artist_name={}",
        urlencoding::encode(track_name),
        urlencoding::encode(artist_name)
    );
    if !album_name.trim().is_empty() {
        url.push_str("&album_name=");
        url.push_str(&urlencoding::encode(album_name));
    }
    if let Some(d) = duration_sec {
        if d > 0.0 {
            url.push_str(&format!("&duration={d}"));
        }
    }

    let resp = engine::HTTP
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("lrclib 请求失败: {e}"))?;

    if resp.status().as_u16() == 404 {
        return Ok(None);
    }
    if !resp.status().is_success() {
        return Err(format!("lrclib 返回 HTTP {}", resp.status()));
    }

    let value: Value = resp
        .json()
        .await
        .map_err(|e| format!("lrclib 响应不是 JSON: {e}"))?;

    let text = |key: &str| -> String {
        value
            .get(key)
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string()
    };

    Ok(Some(LrclibHit {
        id: value.get("id").and_then(|v| v.as_i64()).unwrap_or(0),
        track_name: text("trackName"),
        artist_name: text("artistName"),
        album_name: text("albumName"),
        duration_sec: value.get("duration").and_then(|v| v.as_f64()).unwrap_or(0.0),
        instrumental: value
            .get("instrumental")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        synced: text("syncedLyrics"),
        plain: text("plainLyrics"),
    }))
}

impl LrclibHit {
    /// 两份都是空 —— 这一级等于没取到。
    pub fn is_empty(&self) -> bool {
        !self.instrumental && self.synced.trim().is_empty() && self.plain.trim().is_empty()
    }
}
