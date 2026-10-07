//! 取词引擎的进程级状态。
//!
//! 三样东西：tokio 运行时、网易客户端（持登录 cookie）、第三方源共用的裸 HTTP 客户端。
//!
//! ⚠️ 这里的网易客户端是**独立于 `ncm-android` 的第二个实例**——按项目约定歌词引擎是单独的
//! .so，不与 ncm-android 混编。代价是 cookie 有两份，由 Kotlin 侧在同一个时机推给两边
//! （`AccountRepository` 里 `NcmBridge.setCookie` 的旁边）。两边的 cookie 必须同进同退，
//! 否则会出现「App 里显示已登录、歌词却按未登录取」的怪状态。

use ncm_api_rs::{create_client, ApiClient};
use once_cell::sync::Lazy;
use std::sync::Mutex;
use std::time::Duration;

/// 伪装浏览器 UA。
///
/// 实测镜像站目前**不挑 UA**（同一 URL 带与不带都返回同一份 TTML），但此前确实出现过
/// 返回 HTML 错误页的情况，且镜像有限流。带上浏览器 UA 零成本，作为保险留着。
pub const BROWSER_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
     (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

/// 全局 tokio 运行时。JNI 调用是同步的，用 `block_on` 把异步拉平。
pub static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime 初始化失败")
});

/// 网易侧客户端，持有登录 cookie。
static NCM: Lazy<Mutex<ApiClient>> = Lazy::new(|| Mutex::new(create_client(None)));

/// 快照当前客户端。请求期间持锁会和 set_cookie 死等，所以先 clone 再 await。
pub fn ncm_snapshot() -> ApiClient {
    NCM.lock().unwrap().clone()
}

/// 设置登录 cookie。
pub fn set_ncm_cookie(cookie: String) {
    NCM.lock().unwrap().set_cookie(cookie);
}

/// 清空登录态。直接换一个干净客户端，比 `set_cookie("")` 干净。
pub fn clear_ncm_cookie() {
    *NCM.lock().unwrap() = create_client(None);
}

/// 第三方取词（AMLL DB / Apple amp-api / lrclib）共用的裸 HTTP 客户端。
///
/// 与网易那条链路完全无关：不走 ncm-api-rs 的加密/签名，就是普通 GET。
pub static HTTP: Lazy<reqwest::Client> = Lazy::new(|| {
    reqwest::Client::builder()
        .user_agent(BROWSER_UA)
        .timeout(Duration::from_secs(15))
        .build()
        .expect("http client 初始化失败")
});
