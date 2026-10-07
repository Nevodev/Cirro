//! 网易云歌词（取词链的第 3 级）。
//!
//! 复用 `ncm-api-rs` 已有的 `/api/song/lyric` —— 签名、加密、cookie 注入都现成，
//! 这里只负责把响应里那几份文本抽出来。
//!
//! 用户明确要**只要逐行**，所以只用 `lrc` / `tlyric` / `romalrc` 三份，
//! 不碰 `yrc`（逐字）。翻译与罗马音是**按行时间戳并回主行**的，那步在 Kotlin 侧做。
//!
//! 实测响应形状（2026-10-07）：
//! ```text
//! 顶层键: ["code","klyric","lrc","qfy","romalrc","sfy","sgc","tlyric","transUser"]
//! lrc.lyric / tlyric.lyric / romalrc.lyric  都是标准 LRC 文本；klyric 实测为空
//! ```

use ncm_api_rs::Query;

use crate::engine;

/// 网易给的三份文本。任何一份都可能为空（无翻译 / 无罗马音，或未登录时整份为空）。
#[derive(Debug, Default, Clone)]
pub struct NeteaseLyric {
    pub lrc: String,
    pub translation: String,
    pub roman: String,
}

impl NeteaseLyric {
    /// 连主歌词都没有 —— 这一级等于没取到，调用方应该往下回落。
    ///
    /// 🔴 掉登录时网易对歌词接口**照样回 code=200**，只是 `lrc.lyric` 为空串；
    /// 所以判「有没有取到」只能看文本，不能看状态码。
    pub fn is_empty(&self) -> bool {
        self.lrc.trim().is_empty()
    }
}

/// 按歌曲 id 取网易歌词。
pub async fn fetch(id: &str) -> Result<NeteaseLyric, String> {
    let client = engine::ncm_snapshot();
    let query = Query::new().param("id", id);
    let resp = client.lyric(&query).await.map_err(|e| e.to_string())?;

    let text = |key: &str| -> String {
        resp.body
            .get(key)
            .and_then(|node| node.get("lyric"))
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string()
    };

    Ok(NeteaseLyric {
        lrc: text("lrc"),
        translation: text("tlyric"),
        roman: text("romalrc"),
    })
}
