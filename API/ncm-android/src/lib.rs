//! Android 端上直连桥接层（最小可用版本）
//!
//! 目标：把 ncm-api-rs 编译成 Android 可加载的 .so，导出稳定的 C ABI，
//! 供后续 JNI / uniffi 绑定调用。
//!
//! 内存约定：所有返回的 `*mut c_char` 必须由调用方用 `ncm_free` 释放。

use ncm_api_rs::{create_client, ApiClient, Query};
use once_cell::sync::Lazy;
use std::ffi::{c_char, CString};
use std::sync::Mutex;

/// 全局 tokio 运行时。JNI 调用是同步的，用 block_on 把异步拉平。
static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime 初始化失败")
});

/// 全局客户端，持有 cookie 与设备信息。
static CLIENT: Lazy<Mutex<ApiClient>> = Lazy::new(|| Mutex::new(create_client(None)));

fn to_c(s: String) -> *mut c_char {
    CString::new(s)
        .unwrap_or_else(|_| CString::new(r#"{"error":"invalid utf8"}"#).unwrap())
        .into_raw()
}

/// 读取调用方传入的 UTF-8 字节切片。
unsafe fn borrow_str<'a>(ptr: *const u8, len: usize) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    std::str::from_utf8(std::slice::from_raw_parts(ptr, len)).ok()
}

/// 库版本号。
#[no_mangle]
pub extern "C" fn ncm_version() -> *mut c_char {
    to_c(env!("CARGO_PKG_VERSION").to_string())
}

/// 设置登录 cookie（网易云返回的完整 cookie 串）。
/// 返回 0 成功，-1 参数无效，-2 非 UTF-8。
#[no_mangle]
pub unsafe extern "C" fn ncm_set_cookie(ptr: *const u8, len: usize) -> i32 {
    match borrow_str(ptr, len) {
        Some(s) => {
            CLIENT.lock().unwrap().set_cookie(s.to_string());
            0
        }
        None => -1,
    }
}

/// 搜索歌曲。参数为 keywords，返回 JSON 字符串。
#[no_mangle]
pub unsafe extern "C" fn ncm_search(ptr: *const u8, len: usize) -> *mut c_char {
    let kw = match borrow_str(ptr, len) {
        Some(s) => s,
        None => return to_c(r#"{"error":"invalid param"}"#.to_string()),
    };
    let q = Query::new()
        .param("keywords", kw)
        .param("type", "1")
        .param("limit", "10");
    let client = CLIENT.lock().unwrap().clone();
    match RUNTIME.block_on(client.cloudsearch(&q)) {
        Ok(r) => to_c(r.body.to_string()),
        Err(e) => to_c(format!(r#"{{"error":"{}"}}"#, e)),
    }
}

/// 释放本库返回的字符串。
#[no_mangle]
pub unsafe extern "C" fn ncm_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}

/// 单元自检：确认 tokio 运行时与客户端可正常构建。
#[no_mangle]
pub extern "C" fn ncm_selftest() -> i32 {
    let client = CLIENT.lock().unwrap().clone();
    match RUNTIME.block_on(client.cloudsearch(&Query::new().param("keywords", "test").param("limit", "1"))) {
        Ok(_) => 1,
        Err(_) => 0,
    }
}
