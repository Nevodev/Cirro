//! JNI 导出。
//!
//! 类名对应 Kotlin 侧的 `com.nevoit.cirro.lyrics.data.LyricsBridge`（Kotlin `object`，
//! 所以第二个参数是实例而不是 `jclass`）。
//!
//! 一律返回 JSON 字符串，失败也走正常返回：
//!   `{"ok":true, ...}` / `{"ok":false,"message":"..."}`。
//!
//! ⚠️ 歌词引擎是**独立于 `ncm-android` 的第二个 .so**（`libcirro_lyrics.so`），
//! 因此这里也自带一份网易 cookie —— Kotlin 侧必须在同一时机把 cookie 推给两个桥。

use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use crate::{api, engine};

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

/// 库版本号。Kotlin 侧自检用。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    jni_out(
        &mut env,
        serde_json::json!({ "ok": true, "version": crate::version() }),
    )
}

/// 设置网易 cookie。空串判失败（0 成功 / -1 参数无效）。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeSetCookie<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    cookie: JString<'local>,
) -> jint {
    let cookie = jni_in(&mut env, &cookie);
    api::set_cookie(cookie)
}

/// 清空网易登录态。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeClearCookie<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) {
    api::clear_cookie();
}

/// 配置 Apple Music 凭据。`mediaUserToken` 传空串等于关闭这一级。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeSetAppleToken<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    media_user_token: JString<'local>,
) -> jint {
    let token = jni_in(&mut env, &media_user_token);
    api::set_apple_token(token)
}

/// 下发**开发者 token 的落盘缓存目录**（App 启动时调一次，值来自 `context.filesDir`）。
///
/// 原生层拿不到 Context，所以这个可写路径只能由上层给。给完它会立刻把磁盘上的缓存读进内存
/// —— 冷启动第一次取词就直接命中，不用再抓 Apple 的网页 + JS。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeSetAppleCacheDir<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
) -> jint {
    let dir = jni_in(&mut env, &dir);
    api::set_apple_cache_dir(dir);
    0
}

/// 是否已配置 Apple 凭据。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAppleConfigured<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    jni_out(
        &mut env,
        serde_json::json!({ "ok": true, "configured": api::apple_configured() }),
    )
}

/// 网易 LRC。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeNetease<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let value = engine::RUNTIME.block_on(api::netease_lyric(id));
    jni_out(&mut env, value)
}

/// AMLL TTML DB。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAmll<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    ncm_id: JString<'local>,
) -> jstring {
    let ncm_id = jni_in(&mut env, &ncm_id);
    let value = engine::RUNTIME.block_on(api::amll_ttml(ncm_id));
    jni_out(&mut env, value)
}

/// Apple Music 官方 TTML。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeApple<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    am_id: JString<'local>,
) -> jstring {
    let am_id = jni_in(&mut env, &am_id);
    let value = engine::RUNTIME.block_on(api::apple_ttml(am_id));
    jni_out(&mut env, value)
}

/// Apple Music 搜索（不依赖 AMLL 的 Apple id 入口，**未登录也能用**）。
///
/// `storefront` 空串 = 自动（已登录用账号区域、未登录用默认区域）；非空 = 显式指定区域
/// （未登录时 `cn` 搜不到够格候选、拿 `us` 兜一次用）。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAppleSearch<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    term: JString<'local>,
    limit: JString<'local>,
    storefront: JString<'local>,
) -> jstring {
    let term = jni_in(&mut env, &term);
    let limit = jni_in(&mut env, &limit);
    let storefront = jni_in(&mut env, &storefront);
    let value = engine::RUNTIME.block_on(api::apple_search(term, limit, storefront));
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAppleArtistSearch<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    term: JString<'local>,
    limit: JString<'local>,
    storefront: JString<'local>,
) -> jstring {
    let term = jni_in(&mut env, &term);
    let limit = jni_in(&mut env, &limit);
    let storefront = jni_in(&mut env, &storefront);
    let value = engine::RUNTIME.block_on(api::apple_artist_search(term, limit, storefront));
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAppleArtistHeader<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    storefront: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let storefront = jni_in(&mut env, &storefront);
    let value = engine::RUNTIME.block_on(api::apple_artist_header(id, storefront));
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAppleAlbumSearch<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    term: JString<'local>,
    storefront: JString<'local>,
) -> jstring {
    let term = jni_in(&mut env, &term);
    let storefront = jni_in(&mut env, &storefront);
    let value = engine::RUNTIME.block_on(api::apple_album_search(term, storefront));
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeAppleAlbumDetail<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
    storefront: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let storefront = jni_in(&mut env, &storefront);
    let value = engine::RUNTIME.block_on(api::apple_album_detail(id, storefront));
    jni_out(&mut env, value)
}

/// lrclib。
#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeLrclib<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    track_name: JString<'local>,
    artist_name: JString<'local>,
    album_name: JString<'local>,
    duration_sec: JString<'local>,
) -> jstring {
    let track = jni_in(&mut env, &track_name);
    let artist = jni_in(&mut env, &artist_name);
    let album = jni_in(&mut env, &album_name);
    let duration = jni_in(&mut env, &duration_sec);
    let value = engine::RUNTIME.block_on(api::lrclib_lyric(track, artist, album, duration));
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeQqSearch<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    term: JString<'local>,
) -> jstring {
    let term = jni_in(&mut env, &term);
    let value = engine::RUNTIME.block_on(api::qq_search(term));
    jni_out(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_lyrics_data_LyricsBridge_nativeQqLyric<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: JString<'local>,
) -> jstring {
    let id = jni_in(&mut env, &id);
    let value = engine::RUNTIME.block_on(api::qq_lyric(id));
    jni_out(&mut env, value)
}
