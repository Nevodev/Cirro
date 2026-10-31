use super::{canon_pic, client_snapshot, flatten_song, jni_in, jni_out, pick_i64_loose, pick_str, RUNTIME};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use ncm_api_rs::{Query, RequestOption};
use serde_json::{json, Value};

fn candidates(web: &Value, mobile: &Value) -> Value {
    let result = &web["result"];
    let songs: Vec<Value> = result["songs"].as_array().into_iter().flatten()
        .filter(|item| pick_i64_loose(item, &["id"]) > 0)
        .map(|item| flatten_song(item, None)).collect();
    let mut collections = Vec::new();
    for (key, kind) in [("artists", "ARTIST"), ("albums", "ALBUM"), ("playlists", "PLAYLIST")] {
        for item in result[key].as_array().into_iter().flatten() {
            let id = pick_i64_loose(item, &["id"]);
            if id <= 0 { continue; }
            let author = if kind == "ALBUM" {
                pick_str(&item["artist"], &["name"])
            } else {
                pick_str(&item["creator"], &["nickname"])
            };
            collections.push(json!({
                "kind": kind,
                "id": id,
                "title": pick_str(item, &["name"]),
                "author": author,
                "cover": canon_pic(pick_str(item, &["picUrl", "coverImgUrl", "img1v1Url"])),
                "trackCount": pick_i64_loose(item, &["trackCount", "size", "musicSize"]),
                "albumType": pick_str(item, &["type", "subType"]),
            }));
        }
    }
    let mut keywords = Vec::new();
    for item in mobile["result"]["allMatch"].as_array().into_iter().flatten() {
        let keyword = pick_str(item, &["keyword"]).trim();
        if !keyword.is_empty() && !keywords.iter().any(|previous| previous == keyword) {
            keywords.push(keyword.to_string());
        }
    }
    let order: Vec<&str> = result["order"].as_array().into_iter().flatten()
        .filter_map(Value::as_str).collect();
    let videos: Vec<Value> = result["mvs"].as_array().into_iter().flatten()
        .filter(|item| pick_i64_loose(item, &["id"]) > 0)
        .map(|item| {
            let artists = item["artists"].as_array().into_iter().flatten()
                .map(|artist| pick_str(artist, &["name"]))
                .filter(|name| !name.is_empty()).collect::<Vec<_>>().join(" / ");
            json!({
                "id": pick_i64_loose(item, &["id"]),
                "title": pick_str(item, &["name"]),
                "artist": if artists.is_empty() { pick_str(item, &["artistName"]).to_string() } else { artists },
                "cover": canon_pic(pick_str(item, &["cover", "imgurl", "picUrl"])),
                "durationMs": pick_i64_loose(item, &["duration"]),
                "playCount": pick_i64_loose(item, &["playCount"]),
            })
        }).collect();
    json!({"keywords": keywords, "songs": songs, "collections": collections, "order": order, "videos": videos})
}

fn results_page(body: &Value, kind: &str, offset: usize, limit: usize) -> Value {
    let result = &body["result"];
    let types = [
        ("songs", "1", "songCount"),
        ("artists", "100", "artistCount"),
        ("albums", "10", "albumCount"),
        ("playlists", "1000", "playlistCount"),
        ("mvs", "1004", "mvCount"),
    ];
    let mut flat = serde_json::Map::new();
    let mut next = serde_json::Map::new();
    if let Some((key, _, total)) = types.iter().find(|(_, wire, _)| *wire == kind) {
        let items = result[*key].as_array().cloned().unwrap_or_default();
        let end = offset + items.len();
        let more = result["hasMore"].as_bool().unwrap_or_else(|| {
            result[*total].as_u64().map(|total| end < total as usize).unwrap_or(items.len() >= limit)
        });
        if !items.is_empty() && more {
            next.insert(key.to_string(), json!(end));
        }
        flat.insert(key.to_string(), json!(items));
        flat.insert("order".to_string(), json!([key]));
    }
    let mut found = candidates(&json!({"result": flat}), &Value::Null);
    for song in found["songs"].as_array_mut().into_iter().flatten() {
        let original = &result["songs"];
        let id = song["id"].as_i64().unwrap_or(0);
        if let Some(privilege) = original.as_array().into_iter().flatten()
            .find(|node| pick_i64_loose(node, &["id"]) == id)
            .and_then(|node| node.get("privilege")).filter(|value| value.is_object()) {
            song["playable"] = json!(super::privilege_playable(privilege));
        }
    }
    json!({"candidates": found, "nextOffsets": next})
}

fn complex_query(keywords: &str, cursor: Value) -> Value {
    json!({
        "keyword": keywords.trim(), "scene": "normal", "channel": "",
        "needCorrect": "false", "newStyle": "true", "extInfo": "{}",
        "cursor": cursor.to_string(),
    })
}

fn complex_results_page(body: &Value) -> Value {
    let data = &body["data"];
    let mut flat = serde_json::Map::new();
    let mut item_order = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for block in data["blocks"].as_array().into_iter().flatten() {
        let resources = block["resources"].as_array()
            .filter(|items| !items.is_empty()).or_else(|| block["dslData"]["resources"].as_array());
        for resource in resources.into_iter().flatten() {
            let base = &resource["baseInfo"];
            let base_id = pick_i64_loose(base, &["id"]);
            let id = if base_id > 0 { base_id } else { pick_i64_loose(resource, &["resourceId"]) };
            if id <= 0 { continue; }
            let (key, identity, mut item) = match pick_str(resource, &["resourceType"]) {
                "song" => ("songs", format!("song:{id}"), base.clone()),
                "artist" => ("artists", format!("ARTIST:{id}"), base.clone()),
                "album" => ("albums", format!("ALBUM:{id}"), base.clone()),
                "playlist" => ("playlists", format!("PLAYLIST:{id}"), base.clone()),
                "mv" => ("mvs", format!("mv:{id}"), base.clone()),
                "new_mlog" if base["mlogBaseDataType"].as_i64() == Some(3) => {
                    let video = &base["resource"]["mlogBaseData"];
                    let details = &base["resource"]["mlogExtVO"];
                    ("mvs", format!("mv:{id}"), json!({
                        "name": pick_str(video, &["text"]), "cover": pick_str(video, &["coverUrl"]),
                        "duration": pick_i64_loose(video, &["duration"]),
                        "artistName": pick_str(details, &["artistName"]), "artists": details["artists"],
                        "playCount": pick_i64_loose(details, &["playCount"]),
                    }))
                }
                _ => continue,
            };
            if !item.is_object() || !seen.insert(identity.clone()) { continue; }
            item["id"] = json!(id);
            flat.entry(key.to_string()).or_insert_with(|| json!([])).as_array_mut().unwrap().push(item);
            item_order.push(identity);
        }
    }
    let originals = flat.get("songs").cloned().unwrap_or_else(|| json!([]));
    let mut found = candidates(&json!({"result": flat}), &Value::Null);
    for song in found["songs"].as_array_mut().into_iter().flatten() {
        if let Some(privilege) = originals.as_array().into_iter().flatten()
            .find(|item| item["id"] == song["id"])
            .and_then(|item| item.get("privilege")).filter(|value| value.is_object()) {
            song["playable"] = json!(super::privilege_playable(privilege));
        }
    }
    found["itemOrder"] = json!(item_order);
    let next_cursor = if data["hasMore"].as_bool() == Some(true) {
        let mut cursor = data["cursor"].as_object().cloned().unwrap_or_default();
        let page = cursor.get("page").and_then(Value::as_u64).unwrap_or(0);
        cursor.insert("page".to_string(), json!(page + 1));
        if !data["traceId"].is_null() { cursor.insert("traceId".to_string(), data["traceId"].clone()); }
        Some(Value::Object(cursor).to_string())
    } else { None };
    json!({"candidates": found, "nextOffsets": {}, "nextCursor": next_cursor})
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSearchResults(
    mut env: JNIEnv,
    _this: JObject,
    keywords: JString,
    kind: JString,
    offset: jint,
    cursor: JString,
) -> jstring {
    let keywords = jni_in(&mut env, &keywords);
    let kind = jni_in(&mut env, &kind);
    let cursor = jni_in(&mut env, &cursor);
    if keywords.trim().is_empty() || !["1018", "1", "100", "10", "1000", "1004"].contains(&kind.as_str()) || offset < 0 {
        return jni_out(&mut env, json!({"ok": false, "message": "搜索参数无效"}));
    }
    let query = Query::new().param("keywords", keywords.trim()).param("type", &kind)
        .param("offset", &offset.to_string()).param("limit", "30");
    let client = client_snapshot();
    let cursor = if cursor.is_empty() {
        json!({"page": 0, "ignoreBlocks": [], "traceId": ""})
    } else {
        match serde_json::from_str::<Value>(&cursor) {
            Ok(cursor) if cursor.is_object() => cursor,
            _ => return jni_out(&mut env, json!({"ok": false, "message": "搜索游标无效"})),
        }
    };
    let response = RUNTIME.block_on(async {
        if kind == "1018" {
            client.request("/api/search/complex/page", complex_query(&keywords, cursor), RequestOption::default()).await
        } else {
            client.cloudsearch(&query).await
        }
    });
    match response {
        Ok(response) if response.body["code"] == 200 => jni_out(&mut env, json!({
            "ok": true, "searchPage": if kind == "1018" { complex_results_page(&response.body) }
                else { results_page(&response.body, &kind, offset as usize, 30) },
        })),
        Ok(response) => jni_out(&mut env, json!({
            "ok": false,
            "message": "搜索未能完成，请稍后重试",
            "detail": format!("Search type {} returned code {}: {}", kind, response.body["code"],
                pick_str(&response.body, &["message", "msg"])),
        })),
        Err(error) => jni_out(&mut env, json!({
            "ok": false, "message": "搜索未能完成，请稍后重试", "detail": error.to_string(),
        })),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nevoit_cirro_data_NcmBridge_nativeSearchCandidates(
    mut env: JNIEnv,
    _this: JObject,
    keywords: JString,
) -> jstring {
    let keywords = jni_in(&mut env, &keywords);
    if keywords.trim().is_empty() {
        return jni_out(&mut env, json!({"ok": true, "candidates": candidates(&Value::Null, &Value::Null)}));
    }
    let client = client_snapshot();
    let (web, mobile) = RUNTIME.block_on(async {
        let mobile_client = client.clone();
        let mobile_query = Query::new().param("keywords", &keywords).param("type", "mobile");
        let mobile = tokio::spawn(async move { mobile_client.search_suggest(&mobile_query).await });
        let web = client.search_suggest(&Query::new().param("keywords", &keywords)).await;
        (web, mobile.await)
    });
    let web = web.ok().filter(|response| response.body["code"] == 200).map(|response| response.body);
    let mobile = mobile.ok().and_then(Result::ok)
        .filter(|response| response.body["code"] == 200).map(|response| response.body);
    if web.is_none() && mobile.is_none() {
        return jni_out(&mut env, json!({"ok": false, "message": "搜索候选未能加载，请稍后重试"}));
    }
    jni_out(&mut env, json!({
        "ok": true,
        "candidates": candidates(web.as_ref().unwrap_or(&Value::Null), mobile.as_ref().unwrap_or(&Value::Null)),
        "partial": web.is_none() || mobile.is_none(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adapts_entity_suggestions_and_mobile_keywords_without_inventing_missing_covers() {
        let result = candidates(&json!({"result": {
            "order": ["playlists", "songs", "albums", "artists"],
            "songs": [{"id": 3402673449i64, "name": "歌", "artists": [{"id": 12, "name": "艺人"}], "album": {"id": "13", "name": "专辑"}}],
            "artists": [{"id": "12", "name": "艺人", "picUrl": "https://p4.music.126.net/artist.jpg"}],
            "albums": [{"id": 13, "name": "专辑", "artist": {"name": "艺人"}, "picId": 100}],
            "playlists": [{"id": 17547744753i64, "name": "歌单", "coverImgUrl": "https://p2.music.126.net/list.jpg", "creator": null}, {"id": 0}],
        }}), &json!({"result": {"allMatch": [{"keyword": "歌"}, {"keyword": "歌"}, {"keyword": "  "}, {"keyword": "歌 live"}]}}));
        assert_eq!(result["songs"][0]["id"], 3402673449i64);
        assert_eq!(result["songs"][0]["albumId"], 13);
        assert_eq!(result["collections"].as_array().unwrap().len(), 3);
        assert_eq!(result["collections"][0]["cover"], "https://p1.music.126.net/artist.jpg");
        assert_eq!(result["collections"][1]["cover"], "");
        assert_eq!(result["collections"][2]["id"], 17547744753i64);
        assert_eq!(result["keywords"], json!(["歌", "歌 live"]));
        assert_eq!(result["order"], json!(["playlists", "songs", "albums", "artists"]));
    }

    #[test]
    fn empty_and_partial_suggestions_are_valid() {
        let empty = candidates(&json!({"result": {}}), &Value::Null);
        assert_eq!(empty, json!({"keywords": [], "songs": [], "collections": [], "order": [], "videos": []}));
        assert_eq!(candidates(&Value::Null, &json!({"result": {"allMatch": [{"keyword": "夜"}]}}))["keywords"], json!(["夜"]));
    }

    #[test]
    fn typed_pagination_uses_raw_count_and_stops_on_the_last_or_empty_page() {
        let body = json!({"result": {"songCount": 3, "songs": [{"id": 1}, {"id": 2}]}});
        assert_eq!(results_page(&body, "1", 0, 2)["nextOffsets"], json!({"songs": 2}));
        assert_eq!(results_page(&body, "1", 1, 2)["nextOffsets"], json!({}));
        assert_eq!(results_page(&json!({"result": {"hasMore": true, "songs": []}}), "1", 0, 2)["nextOffsets"], json!({}));
        assert_eq!(results_page(&json!({"result": {}}), "1", 0, 30)["candidates"]["songs"], json!([]));
    }

    #[test]
    fn music_video_results_preserve_metadata_and_use_the_mv_total_for_pagination() {
        let body = json!({"result": {"mvCount": 3, "mvs": [
            {"id": 123, "name": "音乐视频", "cover": "http://p3.music.126.net/mv.jpg", "duration": 123456,
             "playCount": 9876, "artists": [{"name": "甲"}, {"name": "乙"}]},
            {"id": 456, "name": "第二条", "artistName": "丙"},
        ]}});
        let page = results_page(&body, "1004", 0, 2);
        let videos = &page["candidates"]["videos"];
        assert_eq!(page["candidates"]["order"], json!(["mvs"]));
        assert_eq!(videos[0]["id"], 123);
        assert_eq!(videos[0]["artist"], "甲 / 乙");
        assert_eq!(videos[0]["durationMs"], 123456);
        assert_eq!(videos[0]["cover"], "http://p1.music.126.net/mv.jpg");
        assert_eq!(videos[1]["artist"], "丙");
        assert_eq!(page["nextOffsets"], json!({"mvs": 2}));
        assert_eq!(results_page(&body, "1004", 1, 2)["nextOffsets"], json!({}));
    }

    #[test]
    fn official_complex_page_preserves_mixed_resource_order_and_cursor_and_skips_duplicates() {
        let body = json!({"code": 200, "data": {
            "traceId": "next-trace", "hasMore": true,
            "cursor": {"page": 2, "ignoreBlocks": ["best", "song"]},
            "blocks": [
                {"resources": [
                    {"resourceType": "artist", "baseInfo": {"id": 2, "name": "艺人"}},
                    {"resourceType": "playlist", "baseInfo": {"id": 3, "name": "歌单"}},
                ]},
                {"resources": [
                    {"resourceType": "song", "baseInfo": {"id": 1, "name": "歌", "privilege": {"st": -200}}},
                    {"resourceType": "user", "baseInfo": {"id": 99}},
                    {"resourceType": "song", "baseInfo": {"id": 1, "name": "重复"}},
                    {"resourceType": "album", "baseInfo": {"id": 4, "name": "专辑"}},
                ]},
                {"resources": [{"resourceType": "new_mlog", "resourceId": "9", "baseInfo": {
                    "id": "9", "mlogBaseDataType": 3,
                    "resource": {"mlogBaseData": {"text": "视频", "duration": 120000, "coverUrl": "http://p3.music.126.net/mv.jpg"},
                        "mlogExtVO": {"artistName": "艺人"}}
                }}]},
            ]
        }});
        let page = complex_results_page(&body);
        assert_eq!(page["candidates"]["itemOrder"], json!(["ARTIST:2", "PLAYLIST:3", "song:1", "ALBUM:4", "mv:9"]));
        assert_eq!(page["candidates"]["songs"].as_array().unwrap().len(), 1);
        assert_eq!(page["candidates"]["songs"][0]["playable"], false);
        assert_eq!(page["candidates"]["videos"][0]["durationMs"], 120000);
        let cursor: Value = serde_json::from_str(page["nextCursor"].as_str().unwrap()).unwrap();
        assert_eq!(cursor, json!({"page": 3, "ignoreBlocks": ["best", "song"], "traceId": "next-trace"}));
        let request = complex_query(" 歌 ", cursor.clone());
        assert_eq!(request["keyword"], "歌");
        assert_eq!(serde_json::from_str::<Value>(request["cursor"].as_str().unwrap()).unwrap(), cursor);
        assert_eq!(complex_results_page(&json!({"data": {"blocks": [], "hasMore": false}}))["nextCursor"], Value::Null);
    }
}
