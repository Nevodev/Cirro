//! 四个取词源。每个模块只管「怎么把原始文本取回来」，**不做解析**——
//! 解析统一在 Kotlin 侧（模型也在那边），Rust 这层是纯传输。

pub mod amll;
pub mod apple;
pub mod lrclib;
pub mod netease;
pub mod qq;
mod qrc;
