//! Android 端上直连桥接层（最小可用版本）
//!
//! 目标：把 ncm-api-rs 编译成 Android 可加载的 .so，导出稳定的 C ABI，
//! 供后续 JNI / uniffi 绑定调用。
//!
//! 内存约定：所有返回的 `*mut c_char` 必须由调用方用 `ncm_free` 释放。

use ncm_api_rs::{create_client, ApiClient, NcmError, Query};
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

// ---------------------------------------------------------------------------
// JNI 导出
//
// 类名对应 Kotlin 侧的 `com.nevoit.cirro.data.NcmBridge`（Kotlin `object`，
// 所以第二个参数是实例而不是 jclass）。
//
// 一律返回 JSON 字符串，失败也走正常返回，不让异常跨 JNI 边界：
//   {"ok":true, ...} / {"ok":false,"message":"..."}
// ---------------------------------------------------------------------------

use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

/// 快照当前客户端。请求期间持锁会和 set_cookie 死等，所以先 clone 再 await。
fn client_snapshot() -> ApiClient {
    CLIENT.lock().unwrap().clone()
}

fn jni_out(env: &mut JNIEnv, value: serde_json::Value) -> jstring {
    match env.new_string(value.to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn jni_in(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    match env.get_string(value) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    }
}

/// 取二维码 unikey。返回 `{"ok":true,"unikey":"..."}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeQrKey<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.login_qr_key(&Query::new())) {
        Ok(r) => {
            let unikey = r.body["unikey"].as_str().unwrap_or("").to_string();
            if unikey.is_empty() {
                serde_json::json!({ "ok": false, "message": format!("unikey 为空: {}", r.body) })
            } else {
                serde_json::json!({ "ok": true, "unikey": unikey })
            }
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 由 unikey 生成二维码内容。纯本地拼接，不联网。
/// 返回 `{"ok":true,"qrurl":"https://music.163.com/login?codekey=..."}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeQrCreate<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JString<'local>,
) -> jstring {
    let key = jni_in(&mut env, &key);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.login_qr_create(&Query::new().param("key", &key))) {
        Ok(r) => {
            let qrurl = r.body["data"]["qrurl"].as_str().unwrap_or("").to_string();
            if qrurl.is_empty() {
                serde_json::json!({ "ok": false, "message": "二维码 URL 为空" })
            } else {
                serde_json::json!({ "ok": true, "qrurl": qrurl })
            }
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 轮询扫码状态。code: 801 待扫码 / 802 已扫码待确认 / 803 成功 / 800 已过期。
/// 803 时把 cookie 写回全局客户端，并一并返回，后续接口不必再传。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeQrCheck<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JString<'local>,
) -> jstring {
    let key = jni_in(&mut env, &key);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.login_qr_check(&Query::new().param("key", &key))) {
        Ok(r) => {
            let code = r.body["code"].as_i64().unwrap_or(0);
            // 优先用 body 里那份干净的 cookie 串；没有才退回 Set-Cookie 头拼出来的。
            let cookie = r.body["cookie"]
                .as_str()
                .filter(|c| !c.is_empty())
                .map(str::to_string)
                .or_else(|| (!r.cookie.is_empty()).then(|| r.cookie.join("; ")));
            let mut out = serde_json::json!({
                "ok": true,
                "code": code,
                "message": r.body["message"].as_str().unwrap_or(""),
            });
            if code == 803 {
                if let Some(cookie) = cookie {
                    CLIENT.lock().unwrap().set_cookie(cookie.clone());
                    out["cookie"] = serde_json::Value::String(cookie);
                }
            }
            out
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 手动设置登录 cookie。返回 0 成功。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSetCookie<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    cookie: JString<'local>,
) -> jint {
    let cookie = jni_in(&mut env, &cookie);
    if cookie.is_empty() {
        return -1;
    }
    CLIENT.lock().unwrap().set_cookie(cookie);
    0
}

/// 清空登录态。直接换一个干净客户端，比 set_cookie("") 干净。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeClearCookie<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) {
    *CLIENT.lock().unwrap() = create_client(None);
}

/// 查登录态并取用户资料。
///
/// 未登录时网易云会返回 301，`request` 会把它变成 `AuthRequired`——这里必须把它认成
/// “没登录”而不是错误，否则冷启动时界面会显示一条假的失败提示。
/// 返回 `{"ok":true,"loggedIn":false}` 或 `{"ok":true,"loggedIn":true,"profile":{...}}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeLoginStatus<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.login_status(&Query::new())) {
        Ok(r) => {
            let profile = &r.body["profile"];
            if profile.is_null() {
                serde_json::json!({ "ok": true, "loggedIn": false })
            } else {
                serde_json::json!({ "ok": true, "loggedIn": true, "profile": profile })
            }
        }
        Err(NcmError::AuthRequired(_)) => serde_json::json!({ "ok": true, "loggedIn": false }),
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 换播放直链。`level` 取 standard / higher / exhigh / lossless / hires / sky，空则按 standard。
///
/// 拿不到直链（无版权、VIP 未开通、下架）是**正常的业务结果**，不是错误：
/// 返回 `{"ok":true,"url":""}`。调用方必须把它和 `ok:false`（请求本身失败，值得重试）
/// 分开处理，否则网络抖一下就会被当成"这首歌不能放"。
///
/// 响应里的 `expi` 是直链有效期（实测 1200 秒），很短，所以不要提前批量换好囤起来。
///
/// `gain`（dB）和 `peak`（线性峰值，0..1）是网易云自己算好的响度数据，供客户端做响度归一。
/// 实测 `gain` 会取正值（最高 +14.75 dB，即"这首歌偏轻，需要提"），所以不能拿它当衰减系数用；
/// `peak` 用来算提升上限，`gain` 乘上 `peak` 会越过 1.0 的歌约占 4%，必须钳。
/// 没有这两项时返回 null，客户端按"不做处理"走。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSongUrl<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    level: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let level = match jni_in(&mut env, &level) {
        l if l.is_empty() => "standard".to_string(),
        l => l,
    };

    let client = client_snapshot();
    let query = Query::new().param("id", &id).param("level", &level);
    let value = match RUNTIME.block_on(client.song_url_v1(&query)) {
        Ok(r) => {
            let first = &r.body["data"][0];
            let url = first["url"].as_str().unwrap_or("");
            serde_json::json!({
                "ok": true,
                "url": url,
                "code": first["code"].as_i64().unwrap_or(0),
                "level": first["level"].as_str().unwrap_or(""),
                "type": first["type"].as_str().unwrap_or(""),
                "size": first["size"].as_i64().unwrap_or(0),
                "expi": first["expi"].as_i64().unwrap_or(0),
                "gain": first["gain"].as_f64(),
                "peak": first["peak"].as_f64(),
                "message": if url.is_empty() {
                    first["message"]
                        .as_str()
                        .filter(|m| !m.is_empty())
                        .unwrap_or("该歌曲暂无可播放的音频")
                } else {
                    ""
                },
            })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 批量取歌曲元数据。入参是逗号分隔的 id 串。
///
/// 这里把网易云那坨嵌套结构（ar / al / alia / tns / dt…）拍平成扁平的歌曲对象再返回，
/// 让 Kotlin 侧的模型保持干净；将来上游改字段名也只需要改这一处。
/// 返回 `{"ok":true,"songs":[{"id":..,"name":..,"artists":[..],"album":..,"cover":..,"durationMs":..,"fee":..}]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSongDetail<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    ids: JString<'local>,
) -> jstring {
    let ids = jni_in(&mut env, &ids);
    if ids.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "ids 为空" }),
        );
    }

    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.song_detail(&Query::new().param("ids", &ids))) {
        Ok(r) => {
            let songs: Vec<serde_json::Value> = r.body["songs"]
                .as_array()
                .map(|arr| {
                    arr.iter()
                        .map(|s| {
                            serde_json::json!({
                                "id": s["id"].as_i64().unwrap_or(0),
                                "name": s["name"].as_str().unwrap_or(""),
                                "artists": s["ar"]
                                    .as_array()
                                    .map(|a| a
                                        .iter()
                                        .filter_map(|x| x["name"].as_str())
                                        .collect::<Vec<_>>())
                                    .unwrap_or_default(),
                                "album": s["al"]["name"].as_str().unwrap_or(""),
                                "cover": s["al"]["picUrl"].as_str().unwrap_or(""),
                                "durationMs": s["dt"].as_i64().unwrap_or(0),
                                "fee": s["fee"].as_i64().unwrap_or(0),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default();
            serde_json::json!({ "ok": true, "songs": songs })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}


