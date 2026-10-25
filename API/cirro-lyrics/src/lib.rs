//! Cirro 歌词取词引擎。
//!
//! 职责边界：**只负责把原始文本取回来**，解析成统一模型是 Kotlin 侧的事。
//!
//! 四级取词链（优先级由 Kotlin 侧编排，这里只提供各源的原语）：
//! 1. 已登录时 Apple Music 官方 TTML（[`sources::apple`]），未登录时 QQ 音乐（[`sources::qq`]）；
//! 2. AMLL TTML DB —— 用网易 id 直取，且顺带给出 `appleMusicId`，见 [`sources::amll`]；
//! 3. 网易 LRC（只逐行）—— 见 [`sources::netease`]；
//! 4. lrclib —— 见 [`sources::lrclib`]。
//!
//! 这个 crate 是**独立于 `ncm-api-rs` / `ncm-android` 的单独包**，编出单独的
//! `libcirro_lyrics.so`；它把 `ncm-api-rs` 当依赖用（只为复用网易接口的签名与 cookie 处理），
//! 不改动那两个包。

pub mod api;
pub mod engine;
pub mod sources;

mod bridge;

/// 库版本号。
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
