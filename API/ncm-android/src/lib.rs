//! Android 端上直连桥接层（最小可用版本）
//!
//! 目标：把 ncm-api-rs 编译成 Android 可加载的 .so，导出稳定的 C ABI，
//! 供后续 JNI / uniffi 绑定调用。
//!
//! 内存约定：所有返回的 `*mut c_char` 必须由调用方用 `ncm_free` 释放。

use ncm_api_rs::{create_client, ApiClient, NcmError, Query};
mod library;
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
use jni::sys::{jboolean, jint, jstring};
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

/// 取若干候选键里第一个存在的字符串。
fn pick_str<'a>(v: &'a serde_json::Value, keys: &[&str]) -> &'a str {
    keys.iter()
        .find_map(|k| v.get(*k).and_then(|x| x.as_str()))
        .unwrap_or("")
}

fn pick_i64(v: &serde_json::Value, keys: &[&str]) -> i64 {
    keys.iter()
        .find_map(|k| v.get(*k).and_then(|x| x.as_i64()))
        .unwrap_or(0)
}

/// 图片 URL 归一化：把 CDN 主机号 `pN.music.126.net` 统一成 `p1.music.126.net`。
///
/// 🔴 网易的图片 CDN 每次请求可能随机换主机号（同一张图这次给 p3 下次给 p4），
/// 文件由加密路径（`/xxx==/id.jpg`）定位，主机号只是镜像。但对 Coil 来说
/// 缓存 key 就是 URL 字符串——字符串一变就是"新图"，界面上闪一次重载。
/// 实测一次 `play-record/song/list`（169 张封面）里绝大多数的 pN 都在变。
/// 归一后同一张图永远同一个 URL，Coil 命中缓存，不再闪。
fn canon_pic(url: &str) -> String {
    if let Some(start) = url.find("://p") {
        let host_start = start + 4; // "://p" 之后
        let digits = url[host_start..]
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .count();
        if digits > 0 && url[host_start + digits..].starts_with(".music.126.net") {
            let mut out = String::with_capacity(url.len());
            out.push_str(&url[..host_start]);
            out.push('1');
            out.push_str(&url[host_start + digits..]);
            return out;
        }
    }
    url.to_string()
}

/// 把网易云的歌曲对象拍平成扁平结构（Kotlin 侧只认这一种形状）。
///
/// 上游同一个"歌曲"有三种形状，字段名各不相同：
/// - `song_detail` / `recommend_songs`（每日推荐）：`ar` / `al` / `dt`
/// - `personalized_newsong`：字段在 `song` 里，且封面在外层 `item.picUrl`
/// - `personal_fm`：`artists` / `album` / `duration`
///
/// 三者的数组元素都是 `{id, name}`，专辑都有 `name` / `picUrl` —— 只有父键名和时长名不同，
/// 所以在这里按候选键依次取，Kotlin 侧不必感知上游差异。
fn flatten_song(node: &serde_json::Value, cover_fallback: Option<&str>) -> serde_json::Value {
    let artists: Vec<&str> = ["ar", "artists"]
        .iter()
        .find_map(|k| node.get(*k).and_then(|x| x.as_array()))
        .map(|a| a.iter().filter_map(|x| x["name"].as_str()).collect())
        .unwrap_or_default();

    let artist_ids: Vec<i64> = ["ar", "artists"]
        .iter()
        .find_map(|k| node.get(*k).and_then(|x| x.as_array()))
        .map(|a| a.iter().filter(|x| x["name"].as_str().is_some())
            .map(|x| pick_i64_loose(x, &["id"])).collect())
        .unwrap_or_default();

    let album = ["al", "album"]
        .iter()
        .find_map(|k| node.get(*k).filter(|x| x.is_object()));

    let album_cover = album.and_then(|a| a["picUrl"].as_str()).unwrap_or("");
    let cover = cover_fallback
        .filter(|s| !s.is_empty())
        .map(canon_pic)
        .unwrap_or_else(|| canon_pic(album_cover));

    serde_json::json!({
        "id": pick_i64(node, &["id"]),
        "name": pick_str(node, &["name"]),
        "artists": artists,
        "artistIds": artist_ids,
        "album": album.and_then(|a| a["name"].as_str()).unwrap_or(""),
        "albumId": album.map(|a| pick_i64_loose(a, &["id"])).unwrap_or(0),
        "cover": cover,
        "durationMs": pick_i64(node, &["dt", "duration"]),
        "fee": pick_i64(node, &["fee"]),
        // 能不能播默认按"能"处理：只有 song_detail 那类带 `privileges` 的响应才判得出来，
        // 那种情况由 flatten_song_detail 覆盖。宁可不置灰，也别把能放的歌灰掉。
        "playable": true,
    })
}

/// `song_detail` 类响应（`{songs:[…], privileges:[…]}`）→ 扁平歌曲数组。
///
/// 🔴 **能不能播看 `privileges`，不看 `dt`**。实测 Phaxe/Morten Granau 的「Lost」
/// （id 485964202）`dt=0`，但 privileges 里 `pl=999000` / `st=0`，`song_url` 回 200、
/// 照常拿到直链；而下架的「月亮之子」（id 1383715435）`st=-200` / `pl=0`，
/// `song_url` 回 **404**。拿 `dt <= 0` 当判据会把前一种误杀。
fn flatten_song_detail(body: &serde_json::Value) -> Vec<serde_json::Value> {
    let privileges = body["privileges"].as_array();
    body["songs"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .map(|s| {
                    let mut out = flatten_song(s, None);
                    let id = out["id"].as_i64().unwrap_or(0);
                    let found = privileges
                        .and_then(|list| list.iter().find(|x| pick_i64_loose(x, &["id"]) == id));
                    if let Some(p) = found {
                        out["playable"] = serde_json::json!(privilege_playable(p));
                    }
                    out
                })
                .collect()
        })
        .unwrap_or_default()
}

/// 这条 privilege 还能不能播。
///
/// 只在**明确不可播**时给 false：`st < 0`（下架 / 无版权）或 `pl == 0`（一个可播档位都没有）。
/// 字段缺失一律当能播 —— 接口少给一个键，不该把整行灰掉。
fn privilege_playable(privilege: &serde_json::Value) -> bool {
    if pick_i64_loose(privilege, &["st"]) < 0 {
        return false;
    }
    !matches!(privilege.get("pl").and_then(|v| v.as_i64()), Some(v) if v <= 0)
}

/// 宽松取整数：首页那几个 id 和播放量有时是数字、有时是字符串，两种都要认。
fn pick_i64_loose(v: &serde_json::Value, keys: &[&str]) -> i64 {
    keys.iter()
        .find_map(|k| {
            let x = v.get(*k)?;
            x.as_i64().or_else(|| x.as_str().and_then(|s| s.parse().ok()))
        })
        .unwrap_or(0)
}

/// 取字符串数组，非字符串元素直接丢掉（首页的 `labelTexts`）。
fn pick_str_array(v: &serde_json::Value) -> Vec<String> {
    v.as_array()
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}

/// 拍平首页区块里的一条歌单。
///
/// 首页的歌单**不是标准的 `playlist` 对象**，而是"资源 + UI"两层：名字在
/// `uiElement.mainTitle.title`，封面在 `uiElement.image.imageUrl`，播放量直接挂在
/// `resourceExtInfo.playCount` 上（**没有 `extInfo` 那一层**，别多取一级）。歌单本身该有的
/// `trackCount` / `creator` 一个都没有，所以只能给出这几个字段。
///
/// 🔴 `resourceId` 实测是**字符串**（`"17547744753"`），所以必须走 `pick_i64_loose`；
/// 用 `as_i64()` 会一条不剩地变成 0，界面就是一片空白。
fn flatten_home_playlist(creative: &serde_json::Value) -> Option<serde_json::Value> {
    let res = creative["resources"].as_array()?.first()?;
    let id = pick_i64_loose(res, &["resourceId"]);
    if id == 0 {
        return None;
    }
    let ui = &res["uiElement"];
    Some(serde_json::json!({
        "id": id,
        "name": pick_str(&ui["mainTitle"], &["title"]),
        "cover": canon_pic(pick_str(&ui["image"], &["imageUrl"])),
        "playCount": pick_i64_loose(&res["resourceExtInfo"], &["playCount"]),
        "labels": pick_str_array(&ui["labelTexts"]),
    }))
}

/// 拍平一个首页区块。
///
/// 区块标题在 `uiElement.subTitle.title`（`mainTitle` 实测是空串）。
/// 内容按 `creativeType` 分两路：
/// - `SONG_LIST_HOMEPAGE`（曲风推荐）→ `columns`，**一个 creative 是一列**（实测每列 3 首），
///   客户端要按列排，所以不能拍成一维；
/// - 其余（推荐歌单 / 雷达歌单）→ `playlists`。
fn flatten_home_block(block: &serde_json::Value) -> serde_json::Value {
    let ui = &block["uiElement"];
    let subtitle = ui["subTitle"]["title"].as_str().unwrap_or("");
    let title = if subtitle.is_empty() {
        ui["mainTitle"]["title"].as_str().unwrap_or("")
    } else {
        subtitle
    };

    let mut playlists = Vec::new();
    let mut columns: Vec<Vec<serde_json::Value>> = Vec::new();

    if let Some(creatives) = block["creatives"].as_array() {
        for creative in creatives {
            if creative["creativeType"].as_str() == Some("SONG_LIST_HOMEPAGE") {
                let songs: Vec<serde_json::Value> = creative["resources"]
                    .as_array()
                    .map(|a| {
                        a.iter()
                            .filter_map(|r| {
                                let song = r.get("resourceExtInfo")?.get("songData")?;
                                song.is_object().then(|| flatten_song(song, None))
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                if !songs.is_empty() {
                    columns.push(songs);
                }
            } else if let Some(playlist) = flatten_home_playlist(creative) {
                playlists.push(playlist);
            }
        }
    }

    serde_json::json!({
        "code": pick_str(block, &["blockCode"]),
        "title": title,
        "showType": pick_str(block, &["showType"]),
        "playlists": playlists,
        "columns": columns,
    })
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
            let songs = flatten_song_detail(&r.body);
            serde_json::json!({ "ok": true, "songs": songs })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 把上游的歌曲数组整体拍平。
fn flatten_song_array(v: &serde_json::Value) -> Vec<serde_json::Value> {
    v.as_array()
        .map(|arr| arr.iter().map(|s| flatten_song(s, None)).collect())
        .unwrap_or_default()
}

fn intelligence_songs(body: &serde_json::Value) -> Result<Vec<serde_json::Value>, String> {
    if pick_i64_loose(body, &["code"]) != 200 {
        return Err(pick_str(body, &["message", "msg"]).to_string());
    }
    body["data"].as_array()
        .ok_or_else(|| "心动模式返回缺少歌曲列表".to_string())?
        .iter()
        .map(|item| {
            let song = &item["songInfo"];
            let outer_id = pick_i64(item, &["id"]);
            let id = if outer_id > 0 { outer_id } else { pick_i64(song, &["id"]) };
            if !song.is_object() || id <= 0 {
                return Err("心动模式返回的歌曲无效".to_string());
            }
            let mut flattened = flatten_song(song, None);
            flattened["id"] = serde_json::json!(id);
            Ok(flattened)
        })
        .collect()
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeIntelligenceSongs<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    pid: JString<'local>,
    sid: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let pid = jni_in(&mut env, &pid);
    let sid = jni_in(&mut env, &sid);
    let query = Query::new().param("id", &id).param("pid", &pid)
        .param("sid", &sid).param("count", "20");
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.playmode_intelligence_list(&query)) {
        Ok(response) => match intelligence_songs(&response.body) {
            Ok(songs) => serde_json::json!({ "ok": true, "songs": songs }),
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 私人FM 这次回了哪几首。
///
/// 🔴 FM 的 `data` 里是**简版歌曲**：只有 `id` / `name`，`ar` / `al` 全是 null（实测），
/// 直接拍平会得到一批没有歌手、没有封面的空壳。所以这里只取 id，再统一走一次 `song/detail`
/// 换成完整对象。
/// `/song/simi/get` 这次回了哪首歌。
///
/// 🔴 它**一次只给一首**（官方客户端就是"一首一首插"），挂在
/// `data.commonResourceList[].resourceId`，而且 `resourceId` 是**字符串**（`"1340757963"`），
/// 必须走 `pick_i64_loose`。别去读 `songs` —— 那层根本没有。
fn simi_resource_ids(body: &serde_json::Value) -> Result<Vec<i64>, String> {
    if pick_i64_loose(body, &["code"]) != 200 {
        let message = pick_str(body, &["message", "msg"]);
        return Err(if message.is_empty() {
            "插播相似歌曲请求失败".to_string()
        } else {
            message.to_string()
        });
    }
    let list = body["data"]["commonResourceList"]
        .as_array()
        .ok_or_else(|| "插播相似歌曲返回缺少列表".to_string())?;
    Ok(list
        .iter()
        .map(|item| pick_i64_loose(item, &["resourceId"]))
        .filter(|id| *id > 0)
        .collect())
}

/// 插播相似歌曲：要 `count` 首就得**链式**要 —— 每次拿上一首当种子再问一次。
///
/// 服务端对同一个种子会换着给（同一首连问两次拿到的是不同的歌），所以链式走下来
/// 天然是一条"越走越相似"的路，正好是电台要的东西。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSongSimiGet<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    count: jint,
) -> jstring {
    let mut cursor = jni_in(&mut env, &id);
    if cursor.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }
    let count = count.clamp(1, 10);
    let client = client_snapshot();
    let mut ids: Vec<i64> = Vec::new();
    let mut last_error = String::new();

    for _ in 0..count {
        let query = Query::new().param("id", &cursor);
        match RUNTIME.block_on(client.song_simi_get(&query)) {
            Ok(response) => match simi_resource_ids(&response.body) {
                Ok(found) => {
                    let next = found.into_iter().find(|candidate| {
                        *candidate > 0 && !ids.contains(candidate) && candidate.to_string() != cursor
                    });
                    match next {
                        Some(next_id) => {
                            cursor = next_id.to_string();
                            ids.push(next_id);
                        }
                        None => break,
                    }
                }
                Err(message) => {
                    // 第一首就要不到才算失败；半路断了就把手上这几首交出去。
                    if ids.is_empty() {
                        last_error = message;
                    }
                    break;
                }
            },
            Err(e) => {
                if ids.is_empty() {
                    last_error = e.to_string();
                }
                break;
            }
        }
    }

    if ids.is_empty() {
        let message = if last_error.is_empty() {
            "插播相似歌曲没有返回".to_string()
        } else {
            last_error
        };
        return jni_out(&mut env, serde_json::json!({ "ok": false, "message": message }));
    }

    let id_list = ids
        .iter()
        .map(|id| id.to_string())
        .collect::<Vec<_>>()
        .join(",");
    let value = match RUNTIME.block_on(client.song_detail(&Query::new().param("ids", &id_list))) {
        Ok(detail) => {
            let songs = flatten_song_detail(&detail.body);
            if songs.is_empty() {
                serde_json::json!({ "ok": false, "message": "插播相似歌曲的详情为空" })
            } else {
                serde_json::json!({ "ok": true, "songs": songs })
            }
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

fn personal_fm_ids(body: &serde_json::Value) -> Result<Vec<i64>, String> {
    if pick_i64_loose(body, &["code"]) != 200 {
        let message = pick_str(body, &["message", "msg"]);
        return Err(if message.is_empty() {
            "私人FM请求失败".to_string()
        } else {
            message.to_string()
        });
    }
    let ids: Vec<i64> = body["data"]
        .as_array()
        .ok_or_else(|| "私人FM返回缺少歌曲列表".to_string())?
        .iter()
        .map(|item| pick_i64_loose(item, &["id"]))
        .filter(|id| *id > 0)
        .collect();
    if ids.is_empty() {
        return Err("私人FM这次没有返回歌曲".to_string());
    }
    Ok(ids)
}

/// 近七天播放最多的一首：`user_record` 的 `type=1` 榜单**已经按播放次数降序**，
/// 第一条就是种子，不用再排一次。
fn weekly_top_song(body: &serde_json::Value) -> Result<serde_json::Value, String> {
    if pick_i64_loose(body, &["code"]) != 200 {
        let message = pick_str(body, &["message", "msg"]);
        return Err(if message.is_empty() {
            "听歌排行请求失败".to_string()
        } else {
            message.to_string()
        });
    }
    let song = body["weekData"]
        .as_array()
        .and_then(|list| list.first())
        .map(|entry| &entry["song"])
        .filter(|song| song.is_object())
        .ok_or_else(|| "最近七天没有听歌记录".to_string())?;

    let flattened = flatten_song(song, None);
    if flattened["id"].as_i64().unwrap_or(0) <= 0 {
        return Err("听歌排行返回的歌曲无效".to_string());
    }
    Ok(flattened)
}

/// 电台的种子：该用户近七天播放最多的一首歌。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeWeeklyTopSong<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    uid: JString<'local>,
) -> jstring {
    let uid = jni_in(&mut env, &uid);
    let query = Query::new().param("uid", &uid).param("type", "1");
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.user_record(&query)) {
        Ok(response) => match weekly_top_song(&response.body) {
            Ok(song) => serde_json::json!({ "ok": true, "song": song }),
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

fn liked_playlist_id(body: &serde_json::Value, uid: i64) -> Option<i64> {
    if pick_i64_loose(body, &["code"]) != 200 { return None; }
    body["playlist"].as_array()?.iter()
        .find(|playlist| {
            let owner = pick_i64_loose(&playlist["creator"], &["userId"]);
            pick_i64(playlist, &["specialType"]) == 5 && (owner == 0 || owner == uid)
        })
        .map(|playlist| pick_i64_loose(playlist, &["id"]))
        .filter(|id| *id > 0)
}

fn playlist_request_success(body: &serde_json::Value) -> Result<(), String> {
    let code = pick_i64_loose(body, &["code"]);
    if code == 200 {
        Ok(())
    } else {
        let message = pick_str(body, &["message", "msg"]);
        Err(if message.is_empty() { format!("播放列表请求失败（code={code}）") } else { message.to_string() })
    }
}

fn owned_playlists(body: &serde_json::Value, uid: i64) -> Result<Vec<serde_json::Value>, String> {
    playlist_request_success(body)?;
    let playlists = body["playlist"].as_array().ok_or("播放列表缺少 playlist")?;
    Ok(playlists.iter().filter_map(|playlist| {
        let id = pick_i64_loose(playlist, &["id"]);
        let owner = pick_i64_loose(&playlist["creator"], &["userId"]);
        // “我喜欢的音乐”由喜欢接口维护，避免添加曲目后红心缓存没有同步。
        if id <= 0 || owner != uid || pick_i64_loose(playlist, &["specialType"]) == 5 {
            return None;
        }
        Some(serde_json::json!({
            "id": id,
            "name": pick_str(playlist, &["name"]),
            "cover": canon_pic(pick_str(playlist, &["coverImgUrl"])),
            "trackCount": pick_i64_loose(playlist, &["trackCount"]),
            "creator": pick_str(&playlist["creator"], &["nickname"]),
        }))
    }).collect())
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeOwnedPlaylists<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    uid: JString<'local>,
    offset: jint,
) -> jstring {
    let uid = jni_in(&mut env, &uid);
    let query = Query::new().param("uid", &uid).param("limit", "100").param("offset", &offset.to_string());
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.user_playlist(&query)) {
        Ok(response) => match owned_playlists(&response.body, uid.parse().unwrap_or(0)) {
            Ok(playlists) => serde_json::json!({
                "ok": true, "playlists": playlists,
                "more": response.body["more"].as_bool().unwrap_or(false),
                "nextOffset": offset as usize + response.body["playlist"].as_array().map_or(0, Vec::len),
            }),
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeAddSongToPlaylist<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    pid: JString<'local>,
    song_id: JString<'local>,
) -> jstring {
    let query = Query::new().param("pid", &jni_in(&mut env, &pid))
        .param("tracks", &jni_in(&mut env, &song_id)).param("op", "add");
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.playlist_tracks(&query)) {
        Ok(response) => match playlist_request_success(&response.body) {
            Ok(()) => serde_json::json!({ "ok": true }),
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

#[cfg(test)]
mod playlist_menu_tests {
    use super::{owned_playlists, playlist_request_success};
    use serde_json::json;

    #[test]
    fn only_owned_regular_playlists_are_writable_choices() {
        let playlists = owned_playlists(&json!({"code": 200, "playlist": [
            {"id": 17715824860i64, "name": "mine", "creator": {"userId": "42"}},
            {"id": 2, "creator": {"userId": 99}},
            {"id": 3, "specialType": 5, "creator": {"userId": 42}},
            {"id": 4}, {"id": 0, "creator": {"userId": 42}}
        ]}), 42).unwrap();
        assert_eq!(playlists.len(), 1);
        assert_eq!(playlists[0]["id"], 17715824860i64);
        assert_eq!(playlists[0]["name"], "mine");
    }

    #[test]
    fn empty_playlists_are_distinct_from_a_missing_list() {
        assert!(owned_playlists(&json!({"code": 200, "playlist": []}), 42).unwrap().is_empty());
        assert!(owned_playlists(&json!({"code": 200}), 42).is_err());
    }

    #[test]
    fn a_rejected_write_cannot_be_reported_as_added() {
        assert!(playlist_request_success(&json!({"code": "200"})).is_ok());
        for code in [201, 302, 400, 502] {
            assert_eq!(playlist_request_success(&json!({"code": code, "msg": "rejected"})), Err("rejected".into()));
        }
        assert!(playlist_request_success(&json!({})).is_err());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeLikedPlaylistId<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    uid: JString<'local>,
) -> jstring {
    let uid = jni_in(&mut env, &uid);
    let query = Query::new().param("uid", &uid).param("limit", "1000");
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.user_playlist(&query)) {
        Ok(response) => match liked_playlist_id(&response.body, uid.parse().unwrap_or(0)) {
            Some(id) => serde_json::json!({ "ok": true, "id": id }),
            None => serde_json::json!({ "ok": false, "message": "未能获取喜爱歌曲播放列表" }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

#[cfg(test)]
mod intelligence_tests {
    use super::{intelligence_songs, liked_playlist_id};
    use serde_json::json;

    #[test]
    fn nested_song_info_uses_the_existing_song_contract() {
        let body = json!({ "code": 200, "data": [{ "songInfo": {
            "id": 3402673449i64, "name": "song", "ar": [{"name": "artist"}],
            "al": {"name": "album", "picUrl": "https://p3.music.126.net/cover"}, "dt": 10000
        }}] });
        let songs = intelligence_songs(&body).unwrap();
        assert_eq!(songs[0]["id"], 3402673449i64);
        assert_eq!(songs[0]["artists"], json!(["artist"]));
        assert_eq!(songs[0]["cover"], "https://p1.music.126.net/cover");
        assert_eq!(songs[0]["durationMs"], 10000);
        assert!(intelligence_songs(&json!({"code": 200, "data": []})).unwrap().is_empty());
        assert!(intelligence_songs(&json!({"code": 400, "data": []})).is_err());
        assert!(intelligence_songs(&json!({"code": 200})).is_err());
        assert!(intelligence_songs(&json!({"code": 200, "data": [{}]})).is_err());
        let outer_id = json!({"code": 200, "data": [{"id": 3402673449i64, "songInfo": {"name": "song"}}]});
        assert_eq!(intelligence_songs(&outer_id).unwrap()[0]["id"], 3402673449i64);
    }

    #[test]
    fn liked_playlist_is_selected_by_type_and_owner_not_position() {
        let body = json!({"code": 200, "playlist": [
            {"id": 1, "specialType": 0, "creator": {"userId": 42}},
            {"id": 2, "specialType": 5, "creator": {"userId": 43}},
            {"id": "17715824860", "specialType": 5, "creator": {"userId": 42}}
        ]});
        assert_eq!(liked_playlist_id(&body, 42), Some(17715824860));
        assert_eq!(liked_playlist_id(&body, 44), None);
        assert_eq!(liked_playlist_id(&json!({"code": 200, "playlist": [
            {"id": 42, "specialType": 5, "creator": {"nickname": "me"}}
        ]}), 42), Some(42));
    }
}

/// 每日推荐歌曲（需登录，约 30 首）。返回 `{"ok":true,"songs":[...]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeRecommendSongs<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.recommend_songs(&Query::new())) {
        Ok(r) => {
            let songs = flatten_song_array(&r.body["data"]["dailySongs"]);
            serde_json::json!({ "ok": true, "songs": songs })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 每日推荐歌单（需登录，约 26 条）。
/// 返回 `{"ok":true,"playlists":[{"id":..,"name":..,"cover":..,"playCount":..,"trackCount":..,"creator":..}]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeRecommendResource<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.recommend_resource(&Query::new())) {
        Ok(r) => {
            let playlists: Vec<serde_json::Value> = r.body["recommend"]
                .as_array()
                .map(|arr| {
                    arr.iter()
                        .map(|p| {
                            serde_json::json!({
                                "id": p["id"].as_i64().unwrap_or(0),
                                "name": p["name"].as_str().unwrap_or(""),
                                "cover": canon_pic(p["picUrl"].as_str().unwrap_or("")),
                                "playCount": p["playcount"].as_i64().unwrap_or(0),
                                "trackCount": p["trackCount"].as_i64().unwrap_or(0),
                                "creator": p["creator"]["nickname"].as_str().unwrap_or(""),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default();
            serde_json::json!({ "ok": true, "playlists": playlists })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 排行榜列表。上游返回 60 多条，`official` 为真的才是官方 4 榜（飙升/新歌/原创/热歌）。
/// 返回 `{"ok":true,"lists":[{"id":..,"name":..,"cover":..,"updateFrequency":..,"trackCount":..,"official":bool}]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeToplist<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.toplist(&Query::new())) {
        Ok(r) => {
            let lists: Vec<serde_json::Value> = r.body["list"]
                .as_array()
                .map(|arr| {
                    arr.iter()
                        .map(|l| {
                            serde_json::json!({
                                "id": l["id"].as_i64().unwrap_or(0),
                                "name": l["name"].as_str().unwrap_or(""),
                                "cover": canon_pic(l["coverImgUrl"].as_str().unwrap_or("")),
                                "updateFrequency": l["updateFrequency"].as_str().unwrap_or(""),
                                "trackCount": l["trackCount"].as_i64().unwrap_or(0),
                                // 官方榜才有 ToplistType，其余为空字符串
                                "official": l["ToplistType"]
                                    .as_str()
                                    .map(|s| !s.is_empty())
                                    .unwrap_or(false),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default();
            serde_json::json!({ "ok": true, "lists": lists })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 私人 FM（需登录）。网易云一次只给 3 首，是流式的，切一首要再拉一次。
///
/// 🔴 FM 的 `data` 是**简版歌曲**：只有 `id` / `name`，`ar` / `al` 全是 null（实测）。
/// 照原样拍平会得到一批没有歌手、没有封面的空壳，所以这里先取 id 再统一走一次
/// `song/detail` 换成完整对象，顺带拿到 `privileges` 判可播性。
/// 返回 `{"ok":true,"songs":[...]}`，形状与每日推荐一致。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativePersonalFm<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.personal_fm(&Query::new())) {
        Ok(r) => match personal_fm_ids(&r.body) {
            Ok(ids) => {
                let id_list = ids
                    .iter()
                    .map(|id| id.to_string())
                    .collect::<Vec<_>>()
                    .join(",");
                let query = Query::new().param("ids", &id_list);
                match RUNTIME.block_on(client.song_detail(&query)) {
                    Ok(detail) => {
                        let songs = flatten_song_detail(&detail.body);
                        if songs.is_empty() {
                            serde_json::json!({ "ok": false, "message": "私人FM的歌曲详情为空" })
                        } else {
                            serde_json::json!({ "ok": true, "songs": songs })
                        }
                    }
                    Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
                }
            }
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 歌单曲目，`limit` / `offset` 分页。
///
/// 上游这个是**两步请求**：先 `/api/v6/playlist/detail` 拿 trackIds，再 `/api/v3/song/detail`
/// 换成歌曲详情，所以比单次接口慢一档。只要首曲封面时 `limit` 传 1——上游仍会拉一遍完整
/// 的 trackIds（歌单越大越明显），但省掉了换详情的那一次。
///
/// 拿回来的是标准 `ar` / `al` / `dt` 形状，直接复用 `flatten_song`。
/// 返回 `{"ok":true,"songs":[...]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativePlaylistTracks<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    let id = jni_in(&mut env, &id);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }
    // limit 为 0 会让上游切出空切片，最少给 1。
    let limit = limit.max(1);
    let offset = offset.max(0);

    let query = Query::new()
        .param("id", &id)
        .param("limit", &limit.to_string())
        .param("offset", &offset.to_string());

    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.playlist_track_all(&query)) {
        Ok(r) => {
            // 上游第二步就是 /api/v3/song/detail，所以这里同样带 privileges，能一起判可播性。
            let songs = flatten_song_detail(&r.body);
            serde_json::json!({ "ok": true, "songs": songs })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 歌单详情（需登录）。
///
/// `/api/v6/playlist/detail` 里要用的字段一次拍平。`subscribed` 是**当前登录用户**是否收藏过；
/// `updateTime` 是毫秒时间戳（雷达类歌单该字段不一定跟着内容更新，如实透传）。
/// 返回 `{"ok":true,"id":..,"name":..,"cover":..,"creator":..,"description":..,
///        "updateTime":..,"trackCount":..,"playCount":..,"subscribed":bool}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativePlaylistDetail<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.playlist_detail(&Query::new().param("id", &id))) {
        Ok(r) => {
            let pl = &r.body["playlist"];
            serde_json::json!({
                "ok": true,
                "id": pick_i64_loose(pl, &["id"]),
                "name": pl["name"].as_str().unwrap_or(""),
                "cover": canon_pic(pl["coverImgUrl"].as_str().unwrap_or("")),
                "creator": pl["creator"]["nickname"].as_str().unwrap_or(""),
                "description": pl["description"].as_str().unwrap_or(""),
                "updateTime": pick_i64_loose(pl, &["updateTime"]),
                "trackCount": pick_i64_loose(pl, &["trackCount"]),
                "playCount": pick_i64_loose(pl, &["playCount"]),
                "subscribed": pl["subscribed"].as_bool().unwrap_or(false),
            })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 专辑详情（`/api/v1/album/{id}`）。
///
/// 专辑主接口**不带** `subscribed`（歌单有，实测确认过），所以这里多打一次
/// `/api/album/detail/dynamic` 补 `isSub`；动态那步失败不算整体失败，isSub 按未收藏处理。
/// 歌曲列表在顶层 `songs`，直接复用 `flatten_song` 拍平。
/// `updateTime` 实为专辑的 `publishTime`（毫秒）；`trackCount` 实为 `size`。
/// `company` 是版权/发行公司（老专辑可能空串）；`albumType` 是 `type`（"专辑"/"Single"/"EP"）。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeAlbumDetail<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    let client = client_snapshot();
    let value = match RUNTIME.block_on(async {
        let album = client.album(&Query::new().param("id", &id)).await;
        let dynamic = client
            .album_detail_dynamic(&Query::new().param("id", &id))
            .await;
        (album, dynamic)
    }) {
        (Ok(r), dynamic) => {
            let al = &r.body["album"];
            // 描述主字段是 description，不少专辑只填了 briefDesc
            let description = {
                let d = al["description"].as_str().unwrap_or("");
                if d.is_empty() {
                    al["briefDesc"].as_str().unwrap_or("")
                } else {
                    d
                }
            };
            let artists: Vec<&str> = al["artists"]
                .as_array()
                .map(|xs| xs.iter().filter_map(|a| a["name"].as_str()).collect())
                .unwrap_or_default();
            serde_json::json!({
                "ok": true,
                "id": pick_i64_loose(al, &["id"]),
                "name": al["name"].as_str().unwrap_or(""),
                "cover": canon_pic(al["picUrl"].as_str().unwrap_or("")),
                "artists": artists,
                "description": description,
                "primaryArtistId": primary_album_artist(al).0,
                "primaryArtistName": primary_album_artist(al).1,
                "updateTime": pick_i64_loose(al, &["publishTime"]),
                "trackCount": pick_i64_loose(al, &["size"]),
                // 版权/发行公司；老专辑可能给空串（界面留空兜底）
                "company": al["company"].as_str().unwrap_or(""),
                // 专辑类型："专辑" / "Single" / "EP"……
                "albumType": al["type"].as_str().unwrap_or(""),
                "subscribed": match &dynamic {
                    Ok(d) => d.body["isSub"].as_bool().unwrap_or(false),
                    Err(_) => false,
                },
                "songs": flatten_song_array(&r.body["songs"]),
            })
        }
        (Err(e), _) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

fn primary_album_artist(album: &serde_json::Value) -> (i64, &str) {
    let artist = album["artists"].as_array().and_then(|xs| xs.first())
        .unwrap_or(&album["artist"]);
    (pick_i64_loose(artist, &["id"]), pick_str(artist, &["name"]))
}

fn flatten_artist_albums(body: &serde_json::Value) -> Result<serde_json::Value, String> {
    if pick_i64_loose(body, &["code"]) != 200 || !body["hotAlbums"].is_array() {
        return Err("加载艺人作品失败".into());
    }
    let albums: Vec<_> = body["hotAlbums"].as_array().unwrap().iter().map(|album| {
        let (artist_id, artist_name) = primary_album_artist(album);
        serde_json::json!({
            "kind": "ALBUM", "id": pick_i64_loose(album, &["id"]),
            "title": pick_str(album, &["name"]), "author": artist_name,
            "cover": canon_pic(pick_str(album, &["picUrl"])),
            "updateTimeMs": pick_i64_loose(album, &["publishTime"]),
            "albumType": pick_str(album, &["type"]),
            "primaryArtistId": artist_id, "primaryArtistName": artist_name,
        })
    }).collect();
    Ok(serde_json::json!({"ok": true, "albums": albums}))
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeArtistAlbums<'local>(
    mut env: JNIEnv<'local>, _this: JObject<'local>, id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.artist_album(&Query::new().param("id", &id))) {
        Ok(r) => match flatten_artist_albums(&r.body) {
            Ok(value) => value,
            Err(message) => serde_json::json!({"ok": false, "message": message}),
        },
        Err(e) => serde_json::json!({"ok": false, "message": e.to_string()}),
    };
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSimilarSongs<'local>(
    mut env: JNIEnv<'local>, _this: JObject<'local>, id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.simi_song(&Query::new().param("id", &id))) {
        Ok(r) if pick_i64_loose(&r.body, &["code"]) == 200 && r.body["songs"].is_array() =>
            serde_json::json!({"ok": true, "songs": flatten_song_detail(&r.body)}),
        Ok(_) => serde_json::json!({"ok": false, "message": "加载相似歌曲失败"}),
        Err(e) => serde_json::json!({"ok": false, "message": e.to_string()}),
    };
    jni_out(&mut env, value)
}

#[cfg(test)]
mod album_recommendation_tests {
    use super::{primary_album_artist, flatten_artist_albums};
    use serde_json::json;

    #[test]
    fn primary_artist_is_the_first_album_credit_even_if_tracks_have_other_artists() {
        let album = json!({"artists": [
            {"id": "3402673449", "name": "first"}, {"id": 2, "name": "second"}
        ]});
        assert_eq!(primary_album_artist(&album), (3402673449, "first"));
        assert_eq!(primary_album_artist(&json!({"artist": {"id": 3, "name": "fallback"}})), (3, "fallback"));
    }

    #[test]
    fn album_shelf_keeps_navigation_identity_and_rejects_failed_or_missing_lists() {
        let result = flatten_artist_albums(&json!({"code": 200, "hotAlbums": [{
            "id": "17715824860", "name": "work", "type": "Single",
            "artist": {"id": 3, "name": "artist"}, "picUrl": "https://p3.music.126.net/cover"
        }]})).unwrap();
        assert_eq!(result["albums"][0]["id"], 17715824860i64);
        assert_eq!(result["albums"][0]["kind"], "ALBUM");
        assert_eq!(result["albums"][0]["albumType"], "Single");
        assert_eq!(result["albums"][0]["cover"], "https://p1.music.126.net/cover");
        assert!(flatten_artist_albums(&json!({"code": 302, "hotAlbums": []})).is_err());
        assert!(flatten_artist_albums(&json!({"code": 200})).is_err());
    }
}

fn flatten_artist_detail(body: &serde_json::Value) -> Result<serde_json::Value, String> {
    if pick_i64_loose(body, &["code"]) != 200 {
        let message = pick_str(body, &["message", "msg"]);
        return Err(if message.is_empty() { "加载艺人失败".into() } else { message.into() });
    }
    let artist = &body["artist"];
    let id = pick_i64_loose(artist, &["id"]);
    if id <= 0 || !body["hotSongs"].is_array() {
        return Err("艺人详情缺少资料或热门歌曲".into());
    }
    let songs = flatten_song_array(&body["hotSongs"]);
    Ok(serde_json::json!({
        "ok": true, "id": id, "name": pick_str(artist, &["name"]),
        "cover": canon_pic(pick_str(artist, &["picUrl", "img1v1Url"])),
        "description": pick_str(artist, &["briefDesc"]),
        "trackCount": songs.len(),
        "subscribed": artist["followed"].as_bool().or_else(|| body["followed"].as_bool()).unwrap_or(false),
        "songs": songs,
    }))
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeArtistDetail<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.artists(&Query::new().param("id", &id))) {
        Ok(response) => match flatten_artist_detail(&response.body) {
            Ok(value) => value,
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 艺人头像（轻量）。
///
/// 🔴 走 `/api/artist/head/info/get`（实测 ~3 KB / 250 ms），不是 `/api/v1/artist/{id}`
/// （~95 KB，还捎回 50 首热门歌）——「全部艺人」列表要补几十上百个头像，后者三十倍的体积受不了。
/// 这个端点**不支持批量**（`id` 传逗号串直接返错），只能一个一个来。
///
/// 拿不到图不是失败：`ok: true` + `cover: ""`（跟"拿不到直链"一个约定），上层缓存空串，
/// 免得没头像的艺人每次都被重查一遍；`ok: false` 才是请求本身坏了。
fn flatten_artist_cover(body: &serde_json::Value) -> Result<serde_json::Value, String> {
    if pick_i64_loose(body, &["code"]) != 200 {
        let message = pick_str(body, &["message", "msg"]);
        return Err(if message.is_empty() { "加载艺人头像失败".into() } else { message.into() });
    }
    let artist = &body["data"]["artist"];
    let id = pick_i64_loose(artist, &["id"]);
    if id <= 0 {
        return Err("艺人资料缺失".into());
    }
    // cover 是艺人页那张大方图，avatar 是 img1v1 小方图，圆头像用哪个都行，优先 cover
    let cover = pick_str(artist, &["cover", "avatar", "picUrl", "img1v1Url"]);
    let cover = if cover.is_empty() { String::new() } else { canon_pic(cover) };
    Ok(serde_json::json!({ "ok": true, "id": id, "cover": cover }))
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeArtistCover<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.artist_detail(&Query::new().param("id", &id))) {
        Ok(response) => match flatten_artist_cover(&response.body) {
            Ok(value) => value,
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

#[cfg(test)]
mod song_navigation_tests {
    use super::{flatten_artist_cover, flatten_artist_detail, flatten_song, flatten_song_detail, privilege_playable};
    use serde_json::json;

    #[test]
    fn navigation_ids_remain_aligned_with_artist_names_and_keep_64_bits() {
        let song = flatten_song(&json!({"id": 1, "ar": [
            {"id": "3402673449", "name": "first"}, {"id": 2}, {"name": "unknown"}
        ], "al": {"id": 17715824860i64, "name": "album"}}), None);
        assert_eq!(song["artists"], json!(["first", "unknown"]));
        assert_eq!(song["artistIds"], json!([3402673449i64, 0]));
        assert_eq!(song["albumId"], 17715824860i64);
        let long_keys = flatten_song(&json!({"id": 1, "artists": [{"id": 3, "name": "name"}], "album": {"id": "4"}}), None);
        assert_eq!(long_keys["artistIds"], json!([3]));
        assert_eq!(long_keys["albumId"], 4);
    }

    #[test]
    fn artist_page_requires_valid_metadata_and_a_song_list() {
        let detail = flatten_artist_detail(&json!({"code": 200, "artist": {
            "id": 42, "name": "artist", "picUrl": "https://p3.music.126.net/portrait", "followed": true
        }, "hotSongs": [{"id": 5}]})).unwrap();
        assert_eq!(detail["name"], "artist");
        assert_eq!(detail["trackCount"], 1);
        assert_eq!(detail["subscribed"], true);
        assert_eq!(detail["songs"][0]["id"], 5);
        assert!(flatten_artist_detail(&json!({"code": 200, "artist": {"id": 42}})).is_err());
        assert!(flatten_artist_detail(&json!({"code": 302})).is_err());
    }

    /// 艺人头像：`cover` 优先于 `avatar`；**没图的艺人返成功 + 空串**（不是失败），
    /// 免得上层把"这个艺人就是没头像"当成网络错误、每次刷新都重查一遍。
    #[test]
    fn artist_cover_is_ok_with_empty_url_and_normalizes_host() {
        let with_cover = flatten_artist_cover(&json!({"code": 200, "data": {"artist": {
            "id": 42, "cover": "http://p3.music.126.net/portrait.jpg",
            "avatar": "http://p4.music.126.net/small.jpg"
        }}})).unwrap();
        assert_eq!(with_cover["cover"], "http://p1.music.126.net/portrait.jpg");
        let only_avatar = flatten_artist_cover(&json!({"code": 200, "data": {"artist": {
            "id": 42, "avatar": "http://p4.music.126.net/small.jpg"
        }}})).unwrap();
        assert_eq!(only_avatar["cover"], "http://p1.music.126.net/small.jpg");
        let no_pic = flatten_artist_cover(&json!({"code": 200, "data": {"artist": {"id": 42}}})).unwrap();
        assert_eq!(no_pic["ok"], true);
        assert_eq!(no_pic["cover"], "");
        assert!(flatten_artist_cover(&json!({"code": 200, "data": {}})).is_err());
        assert!(flatten_artist_cover(&json!({"code": 404, "message": "nope"})).is_err());
    }

    /// 可播性只认 `privileges`，**不认 `dt`** —— 两个反例都来自实测：
    /// 「月亮之子」1383715435 已下架（`st=-200`/`pl=0`，`song_url` 回 404）；
    /// Phaxe/Morten Granau「Lost」485964202 的 `dt` 同样是 0，但 `pl=999000`，直链正常。
    #[test]
    fn playable_comes_from_privileges_not_duration() {
        let body = json!({
            "songs": [
                {"id": 1383715435, "name": "月亮之子", "dt": 0, "ar": [], "al": {}},
                {"id": 485964202, "name": "Lost", "dt": 0, "ar": [], "al": {}}
            ],
            "privileges": [
                {"id": 1383715435, "st": -200, "pl": 0},
                {"id": 485964202, "st": 0, "pl": 999000}
            ]
        });
        let songs = flatten_song_detail(&body);
        assert_eq!(songs[0]["playable"], json!(false));
        assert_eq!(songs[1]["playable"], json!(true));
    }

    /// 拿不到 privileges / 里面没这首 / 键缺失：一律当能播，绝不误灰。
    #[test]
    fn playable_defaults_to_true_when_unknown() {
        let no_privileges = flatten_song_detail(&json!({"songs": [{"id": 7, "ar": [], "al": {}}]}));
        assert_eq!(no_privileges[0]["playable"], json!(true));

        let not_listed = flatten_song_detail(&json!({
            "songs": [{"id": 7, "ar": [], "al": {}}],
            "privileges": [{"id": 8, "st": -200, "pl": 0}]
        }));
        assert_eq!(not_listed[0]["playable"], json!(true));

        // 只有 pl=0（st 还是 0）也要判死；空 privilege 判活。
        assert!(!privilege_playable(&json!({"st": 0, "pl": 0})));
        assert!(privilege_playable(&json!({})));
    }
}

fn song_like_success(body: &serde_json::Value) -> Result<(), String> {
    let code = body.get("code").and_then(|code| {
        code.as_i64().or_else(|| code.as_str().and_then(|code| code.parse().ok()))
    });
    // 请求库为二维码等接口把 302 / 400 / 502 也当作 Ok；红心必须以业务码 200 为准。
    if code == Some(200) {
        Ok(())
    } else {
        let message = pick_str(body, &["message", "msg"]);
        let message = if message.is_empty() { "喜爱歌曲请求失败" } else { message };
        Err(format!("{message}（code={}）", code.map(|code| code.to_string()).unwrap_or_else(|| "missing".to_string())))
    }
}

fn liked_song_ids(body: &serde_json::Value) -> Result<Vec<i64>, String> {
    song_like_success(body)?;
    body.get("ids")
        .and_then(|ids| ids.as_array())
        .ok_or_else(|| "喜爱歌曲列表缺少 ids".to_string())?
        .iter()
        .map(|id| id.as_i64().filter(|id| *id > 0).ok_or_else(|| "喜爱歌曲 id 无效".to_string()))
        .collect()
}

#[cfg(test)]
mod song_like_tests {
    use super::{liked_song_ids, song_like_success};
    use serde_json::json;

    #[test]
    fn empty_likes_are_distinct_from_an_invalid_response() {
        assert_eq!(liked_song_ids(&json!({ "code": 200, "ids": [] })), Ok(vec![]));
        assert_eq!(liked_song_ids(&json!({ "code": 200, "ids": [123, 456] })), Ok(vec![123, 456]));
        assert!(liked_song_ids(&json!({ "code": 200 })).is_err());
        assert!(liked_song_ids(&json!({ "code": 200, "ids": [123, "invalid"] })).is_err());
    }

    #[test]
    fn special_request_codes_cannot_confirm_a_heart() {
        assert!(song_like_success(&json!({ "code": 200 })).is_ok());
        assert!(song_like_success(&json!({ "code": "200" })).is_ok());
        for code in [201, 302, 400, 502] {
            assert_eq!(song_like_success(&json!({ "code": code, "msg": "rejected" })), Err(format!("rejected（code={code}）")));
        }
        assert!(song_like_success(&json!({})).is_err());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeLikedSongIds<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    uid: JString<'local>,
) -> jstring {
    let uid = jni_in(&mut env, &uid);
    let query = Query::new().param("uid", &uid);
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.likelist(&query)) {
        Ok(response) => match liked_song_ids(&response.body) {
            Ok(ids) => serde_json::json!({ "ok": true, "likedSongIds": ids }),
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSongLike<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    uid: JString<'local>,
    like: jboolean,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let uid = jni_in(&mut env, &uid);
    let query = Query::new()
        .param("id", &id)
        .param("uid", &uid)
        .param("like", if like != 0 { "true" } else { "false" });
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.song_like(&query)) {
        Ok(response) => match song_like_success(&response.body) {
            Ok(()) => serde_json::json!({ "ok": true }),
            Err(message) => serde_json::json!({ "ok": false, "message": message }),
        },
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 收藏 / 取消收藏（统一详情页用）。
///
/// `kind` 取 "playlist"（`/api/playlist/subscribe|unsubscribe`）或
/// "album"（`/api/album/sub|unsub`）；`t` 传 "1" 收藏、"2" 取消，空按 "1"。
/// 服务端的业务失败（收藏自己的歌单之类）由请求层把非 200 的 body code 翻成 Err，
/// 这里统一走 `{"ok":false,"message":..}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeCollectionSub<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    kind: JString<'local>,
    id: JString<'local>,
    t: JString<'local>,
) -> jstring {
    let kind = jni_in(&mut env, &kind);
    let id = jni_in(&mut env, &id);
    let t = {
        let t = jni_in(&mut env, &t);
        if t.is_empty() { "1".to_string() } else { t }
    };
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    let query = Query::new().param("id", &id).param("t", &t);
    let client = client_snapshot();
    let result = match kind.as_str() {
        "album" => RUNTIME.block_on(client.album_sub(&query)),
        "artist" => RUNTIME.block_on(client.artist_sub(&query)),
        _ => RUNTIME.block_on(client.playlist_subscribe(&query)),
    };
    let value = match result {
        Ok(_) => serde_json::json!({ "ok": true }),
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 首页「最近播放」要几张专辑。
///
/// 这是一条横滑带，10 张够翻两屏了；再多只是把首页那次请求的返回撑大。
const RECENT_ALBUM_LIMIT: usize = 10;

/// 最近播放的**专辑**（首页 section，`/api/play-record/album/list`，需登录）。
///
/// 🔴 数据源是**听歌记录**的专辑维度，不是 `/api/pc/recent/listen/list`（那个才叫"最近常听"）。
/// 后者看着更"官方"，但它把专辑 / 歌单 / 播客混在一条流里，且**不带歌手**——首页那张方封面卡
/// 下面那行小字就没数据了。走听歌记录这条路，专辑名、歌手、封面一次全给，
/// 不必再为补个歌手去逐张打专辑详情（10 张 = 10 次请求）。
///
/// 服务端**已经按专辑归并、按最后一次播放时间倒序**，所以这里不自己去重、不自己排序。
/// **单曲照收**：网易云的单曲本身就是一张单曲专辑，跟正式专辑在同一个列表里，
/// 没有单拎出来区别对待的必要。
///
/// 这块**不是首页区块**：客户端已知的 38 个 `HOMEPAGE_BLOCK_*` 里没有它，
/// 不能指望 `homepage/block/page` 里会冒出来。
/// 空态是"这段时间没听"，客户端按"这块不显示"处理，不是错误。
///
/// 返回 `{"ok":true,"recent":[{"title":..,"cover":..,"artists":[..]}]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeRecentAlbums<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let query = Query::new().param("limit", &RECENT_ALBUM_LIMIT.to_string());
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.record_recent_album(&query)) {
        Ok(r) => {
            let recent: Vec<serde_json::Value> = r.body["data"]["list"]
                .as_array()
                .map(|arr| {
                    arr.iter()
                        // 这个列表按专辑收口，理论上只有 ALBUM；不认识的一律不要。
                        .filter(|x| x["resourceType"].as_str() == Some("ALBUM"))
                        .take(RECENT_ALBUM_LIMIT)
                        .filter_map(|x| {
                            let album = &x["data"];
                            let title = album["name"].as_str().unwrap_or("");
                            if title.is_empty() {
                                return None;
                            }
                            // 歌手给**列表**，拼成一行是排版的事（跟 songs 里的 artists 一个约定）
                            let artists: Vec<&str> = album["artists"]
                                .as_array()
                                .map(|xs| xs.iter().filter_map(|a| a["name"].as_str()).collect())
                                .unwrap_or_default();
                            Some(serde_json::json!({
                                "title": title,
                                "cover": canon_pic(album["picUrl"].as_str().unwrap_or("")),
                                "artists": artists,
                            }))
                        })
                        .collect()
                })
                .unwrap_or_default();
            serde_json::json!({ "ok": true, "recent": recent })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 移动端首页（`/api/homepage/block/page`，需登录）。
///
/// 这是 App「发现」页真正在用的接口，一次下发三个区块：推荐歌单、按你听过的艺人推的歌曲、
/// 以及一组雷达歌单（华语/时光/新歌/乐迷/宝藏/欧美）。
///
/// 区块**统一拍平**成 `{code, title, showType, playlists, columns}`，客户端按 `code` 挑自己
/// 认识的那几块，不认识的就忽略——所以服务端加减区块不用改桥接层。
/// 返回 `{"ok":true,"blocks":[...]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeHomeBlocks<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.homepage_block_page(&Query::new())) {
        Ok(r) => {
            let blocks: Vec<serde_json::Value> = r.body["data"]["blocks"]
                .as_array()
                .map(|a| a.iter().map(flatten_home_block).collect())
                .unwrap_or_default();
            serde_json::json!({ "ok": true, "blocks": blocks })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}



/// 听歌打卡（把一次完整播放上报给网易，计入听歌排行/最近播放）。
///
/// 入参：`id`（歌曲 id）、`sourceid`（来源歌单 id，可为空串）、`time`（播放秒数）。
/// 上报失败不影响播放，Kotlin 侧只管尽力而为。
/// 返回 `{"ok":true,"reported":true}` 或 `{"ok":false,"message":"..."}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeScrobble<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    sourceid: JString<'local>,
    time: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let sourceid = jni_in(&mut env, &sourceid);
    let time = jni_in(&mut env, &time);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    let client = client_snapshot();
    let query = Query::new()
        .param("id", &id)
        .param("sourceid", &sourceid)
        .param("time", &time);
    let value = match RUNTIME.block_on(client.scrobble(&query)) {
        Ok(r) => {
            // 两条日志（startplay/play）各自的 code，都 200 才算上报成功
            let startplay_ok = r.body.pointer("/details/startplay/code").and_then(|v| v.as_i64())
                == Some(200);
            let play_ok = r.body.pointer("/details/play/code").and_then(|v| v.as_i64()) == Some(200);
            if startplay_ok && play_ok {
                serde_json::json!({ "ok": true, "reported": true })
            } else {
                serde_json::json!({
                    "ok": true,
                    "reported": false,
                    "message": format!("startplay_ok={startplay_ok} play_ok={play_ok}"),
                })
            }
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 开始播放的上报（进「最近播放」）。歌一开始播就调，不等结束。
///
/// 入参：`id`（歌曲 id）、`sourceid`（来源歌单 id，可为空串）。
/// 返回 `{"ok":true,"reported":true}` 或 `{"ok":false,"message":"..."}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeScrobbleStart<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    sourceid: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let sourceid = jni_in(&mut env, &sourceid);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    let client = client_snapshot();
    let query = Query::new().param("id", &id).param("sourceid", &sourceid);
    let value = match RUNTIME.block_on(client.scrobble_start(&query)) {
        Ok(r) => {
            let code = r.body.pointer("/details/code").and_then(|v| v.as_i64());
            if code == Some(200) {
                serde_json::json!({ "ok": true, "reported": true })
            } else {
                serde_json::json!({ "ok": true, "reported": false, "message": format!("code={code:?}") })
            }
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 最近播放的**单曲**（`/api/play-record/song/list`，需登录）。
///
/// 🔴 首页「最近播放」的数据源换到这条：专辑维度（`record_recent_album`）只在**官方客户端
/// 的专辑页点播放**时才记账——9.6.05 APK 里 `play-record` 家族只有查询/删除端点、没有写入，
/// 记账靠服务端自己的数据源，Cirro 的播歌永远进不去。单曲维度跟听歌排行同源
/// （weblog 打卡即可进入，实测有传播延迟）。
///
/// 服务端按最后播放时间倒序，同一首歌只出现最近一次；但同一张**专辑**会有多条
/// （听了几首就有几条），按专辑归并在 Kotlin 侧做（`HomeRepository.recentAlbums`），
/// 这层只负责拍平：专辑信息取 `data.al`（这个接口是**短键**歌对象，长键 `album` 兜底），
/// 歌手取 `data.ar`，播放时间 `playTime`（毫秒）在条目上。
///
/// 返回 `{"ok":true,"recent":[{"albumId":..,"album":..,"cover":..,"artists":[..],"playTime":..}]}`。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeRecentSongs<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let query = Query::new().param("limit", "100");
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.record_recent_song(&query)) {
        Ok(r) => {
            let recent: Vec<serde_json::Value> = r.body["data"]["list"]
                .as_array()
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| {
                            let song = &x["data"];
                            // 这个接口的歌是短键（al/ar）；长键兜底，别的来源混进来也不慌
                            let album = if song["al"].is_null() { &song["album"] } else { &song["al"] };
                            let artists_arr = if song["ar"].is_null() {
                                &song["artists"]
                            } else {
                                &song["ar"]
                            };
                            let album_name = album["name"].as_str().unwrap_or("");
                            if album_name.is_empty() {
                                return None;
                            }
                            let artists: Vec<&str> = artists_arr
                                .as_array()
                                .map(|xs| xs.iter().filter_map(|a| a["name"].as_str()).collect())
                                .unwrap_or_default();
                            Some(serde_json::json!({
                                "albumId": album["id"].as_i64().unwrap_or(0),
                                "album": album_name,
                                "cover": canon_pic(album["picUrl"].as_str().unwrap_or("")),
                                "artists": artists,
                                "playTime": x["playTime"].as_i64().unwrap_or(0),
                            }))
                        })
                        .collect()
                })
                .unwrap_or_default();
            serde_json::json!({ "ok": true, "recent": recent })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 专辑类型（`/api/v1/album/{id}`，公开数据）。
///
/// 首页「最近播放」要给单曲专辑的标题补 " - Single"，但最近播放单曲里的 `al`
/// 对象**不带 type**（实测只有 id/name/pic/picUrl/pic_str/tns），只能逐张查专辑详情。
/// `type` 是静态元数据（Single / EP / 专辑…），Kotlin 侧永久缓存，一个专辑一辈子查一次。
///
/// 返回 `{"ok":true,"type":"Single"}`；查不到或失败时 type 为空串/ok=false，
/// 调用方按"没有类型"处理（不加后缀）。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeAlbumType<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    let client = client_snapshot();
    let query = Query::new().param("id", &id);
    let value = match RUNTIME.block_on(client.album(&query)) {
        Ok(r) => {
            let ty = r.body["album"]["type"].as_str().unwrap_or("");
            serde_json::json!({ "ok": true, "type": ty })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}

/// 这首歌**有哪几档音质**（`/api/song/music/detail/get`）。
///
/// 返回体是一组**固定键**，值为 `{br,fid,size,sr,vd}`；**`null` = 这首歌没有这一档**：
/// `l`=standard / `m`=higher / `h`=exhigh / `sq`=lossless / `hr`=hires /
/// `je`=jyeffect / `jm`=jymaster / `sk`=sky（`sks` 是它的另一个编码变体，任一有值即算有）/
/// `vi`=vivid / `db`=dolby。
///
/// ⚠️ **字段在 ≠ 有权益**：实测 `vi` 恒有值，但请求 `vivid` 仍回 `code=-110`。这份清单只够
/// 用来**灰化界面 / 显示体积**，最终能不能播一律以直链响应为准（见 `nativeSongUrl`）。
///
/// 返回 `{"ok":true,"levels":[{"level":"standard","size":..,"br":..}]}`，按**高→低**给。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSongMusicDetail<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    if id.is_empty() {
        return jni_out(
            &mut env,
            serde_json::json!({ "ok": false, "message": "id 为空" }),
        );
    }

    // (detail 键, wire level)。顺序就是档位从高到低，界面直接用。
    const KEYS: &[(&str, &str)] = &[
        ("db", "dolby"),
        ("vi", "vivid"),
        ("sk", "sky"),
        ("jm", "jymaster"),
        ("je", "jyeffect"),
        ("hr", "hires"),
        ("sq", "lossless"),
        ("h", "exhigh"),
        ("m", "higher"),
        ("l", "standard"),
    ];

    let client = client_snapshot();
    let query = Query::new().param("id", &id);
    let value = match RUNTIME.block_on(client.song_music_detail(&query)) {
        Ok(r) => {
            let d = &r.body["data"];
            let levels: Vec<serde_json::Value> = KEYS
                .iter()
                .filter_map(|(key, level)| {
                    // sky 有两个键，任一有值即算有；其余档只看自己那一个键
                    let mut candidates = vec![*key];
                    if *key == "sk" {
                        candidates.push("sks");
                    }
                    let node = candidates
                        .iter()
                        .map(|k| &d[*k])
                        .find(|v| !v.is_null())?;
                    Some(serde_json::json!({
                        "level": level,
                        "br": node["br"].as_i64().unwrap_or(0),
                        "size": node["size"].as_i64().unwrap_or(0),
                    }))
                })
                .collect();
            serde_json::json!({ "ok": true, "levels": levels })
        }
        Err(e) => serde_json::json!({ "ok": false, "message": e.to_string() }),
    };
    jni_out(&mut env, value)
}
