use super::*;
use serde_json::{json, Value};

fn collections(body: &Value, kind: &str, uid: i64) -> Result<Vec<Value>, String> {
    playlist_request_success(body)?;
    let key = if kind == "PLAYLIST" { "playlist" } else { "data" };
    let items = body[key].as_array().ok_or("资料库列表缺少数据")?;
    items.iter().filter(|item| kind != "PLAYLIST" || pick_i64_loose(item, &["specialType"]) != 5)
        .map(|item| {
            let id = pick_i64_loose(item, &["id"]);
            if id <= 0 { return Err("资料库条目 id 无效".into()); }
            let author = if kind == "PLAYLIST" {
                pick_str(&item["creator"], &["nickname"]).to_string()
            } else {
                item["artists"].as_array().map(|artists| artists.iter()
                    .filter_map(|artist| artist["name"].as_str()).collect::<Vec<_>>().join(" & "))
                    .unwrap_or_default()
            };
            Ok(json!({
                "info": {
                    "kind": kind, "id": id, "title": pick_str(item, &["name"]),
                    "author": author,
                    "cover": canon_pic(pick_str(item, &["coverImgUrl", "picUrl", "img1v1Url"])),
                    "trackCount": pick_i64_loose(item, &["trackCount", "size", "musicSize"]),
                    "subscribed": kind != "PLAYLIST" || pick_i64_loose(&item["creator"], &["userId"]) != uid,
                },
                "addedAtMs": pick_i64_loose(item, &["subTime", "subscribeTime"]),
                "artistIds": item["artists"].as_array().map(|artists| artists.iter()
                    .map(|artist| pick_i64_loose(artist, &["id"])).collect::<Vec<_>>()).unwrap_or_default(),
                "artistNames": item["artists"].as_array().map(|artists| artists.iter()
                    .map(|artist| pick_str(artist, &["name"]).to_string()).collect::<Vec<_>>()).unwrap_or_default(),
            }))
        }).collect()
}

fn has_more(body: &Value, next_offset: usize, page_size: usize) -> bool {
    body["hasMore"].as_bool().or_else(|| body["more"].as_bool()).unwrap_or_else(|| {
        let total = pick_i64_loose(body, &["count", "total", "totalCount"]);
        if total > 0 { next_offset < total as usize } else { page_size == 100 }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeLibraryPage<'local>(
    mut env: JNIEnv<'local>, _this: JObject<'local>, kind: JString<'local>, uid: JString<'local>, offset: jint,
) -> jstring {
    let kind = jni_in(&mut env, &kind);
    if !matches!(kind.as_str(), "PLAYLIST" | "ALBUM" | "ARTIST") {
        return jni_out(&mut env, json!({ "ok": false, "message": "未知资料库分类" }));
    }
    let uid = jni_in(&mut env, &uid);
    let query = Query::new().param("uid", &uid).param("limit", "100").param("offset", &offset.to_string());
    let client = client_snapshot();
    let response = RUNTIME.block_on(async {
        match kind.as_str() {
            "PLAYLIST" => client.user_playlist(&query).await,
            "ALBUM" => client.album_sublist(&query).await,
            "ARTIST" => client.artist_sublist(&query).await,
            _ => unreachable!(),
        }
    });
    let value = match response {
        Ok(response) => match collections(&response.body, &kind, uid.parse().unwrap_or(0)) {
            Ok(items) => {
                let key = if kind == "PLAYLIST" { "playlist" } else { "data" };
                let count = response.body[key].as_array().map_or(0, Vec::len);
                json!({ "ok": true, "libraryItems": items,
                    "more": has_more(&response.body, offset as usize + count, count),
                    "nextOffset": offset as usize + count })
            },
            Err(message) => json!({ "ok": false, "message": message }),
        },
        Err(error) => json!({ "ok": false, "message": error.to_string() }),
    };
    jni_out(&mut env, value)
}

fn liked_tracks(body: &Value) -> Result<Vec<Value>, String> {
    playlist_request_success(body)?;
    body["playlist"]["trackIds"].as_array().ok_or("喜爱歌曲缺少 trackIds")?.iter().map(|track| {
        let id = pick_i64_loose(track, &["id"]);
        if id <= 0 { return Err("喜爱歌曲 id 无效".into()); }
        // at 是加入播放列表的时间；t/v 是歌曲属性，不能用来排序。
        Ok(json!({ "id": id, "addedAtMs": pick_i64_loose(track, &["at"]) }))
    }).collect()
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeLikedTracks<'local>(
    mut env: JNIEnv<'local>, _this: JObject<'local>, id: JString<'local>,
) -> jstring {
    let query = Query::new().param("id", &jni_in(&mut env, &id));
    let client = client_snapshot();
    let value = match RUNTIME.block_on(client.playlist_detail(&query)) {
        Ok(response) => match liked_tracks(&response.body) {
            Ok(tracks) => json!({ "ok": true, "libraryTracks": tracks }),
            Err(message) => json!({ "ok": false, "message": message }),
        },
        Err(error) => json!({ "ok": false, "message": error.to_string() }),
    };
    jni_out(&mut env, value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playlist_entries_never_become_recent_albums_and_likes_have_their_own_entry() {
        let items = collections(&json!({ "code": 200, "playlist": [
            { "id": 1, "specialType": 5 },
            { "id": 17715824860i64, "name": "list", "creator": {"userId": 42} }
        ]}), "PLAYLIST", 42).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0]["info"]["kind"], "PLAYLIST");
        assert_eq!(items[0]["info"]["subscribed"], false);
        assert_eq!(items[0]["info"]["id"], 17715824860i64);
    }

    #[test]
    fn likes_preserve_order_and_only_use_added_time() {
        let tracks = liked_tracks(&json!({ "code": 200, "playlist": { "trackIds": [
            { "id": 3, "at": 1700000000000i64 }, { "id": 2, "t": 100, "v": 999 }
        ]} })).unwrap();
        assert_eq!(tracks[0]["id"], 3);
        assert_eq!(tracks[0]["addedAtMs"], 1700000000000i64);
        assert_eq!(tracks[1]["addedAtMs"], 0);
        assert!(liked_tracks(&json!({ "code": 200 })).is_err());
    }

    #[test]
    fn missing_and_rejected_lists_are_not_empty_successes() {
        assert!(collections(&json!({ "code": 200, "data": [] }), "ALBUM", 42).unwrap().is_empty());
        assert!(collections(&json!({ "code": 200 }), "ALBUM", 42).is_err());
        assert!(collections(&json!({ "code": 301, "data": [] }), "ALBUM", 42).is_err());
    }

    #[test]
    fn album_and_artist_pagination_accepts_has_more_and_total_count() {
        assert!(has_more(&json!({"hasMore": true}), 100, 100));
        assert!(!has_more(&json!({"hasMore": false}), 100, 100));
        assert!(has_more(&json!({"count": 150}), 100, 100));
        assert!(!has_more(&json!({"count": 150}), 150, 50));
        assert!(has_more(&json!({}), 100, 100));
        assert!(!has_more(&json!({}), 150, 50));
    }

    #[test]
    fn album_contributors_keep_names_aligned_with_ids() {
        let items = collections(&json!({"code": 200, "data": [{
            "id": 1, "artists": [{"id": 50, "name": "artist"}, {"name": "unknown"}],
            "subTime": 1700000000000i64
        }]}), "ALBUM", 42).unwrap();
        assert_eq!(items[0]["artistIds"], json!([50, 0]));
        assert_eq!(items[0]["artistNames"], json!(["artist", "unknown"]));
        assert_eq!(items[0]["addedAtMs"], 1700000000000i64);
    }
}
