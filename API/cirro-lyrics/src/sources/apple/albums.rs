use super::*;

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Album {
    pub id: String,
    pub name: String,
    pub artist: String,
    pub track_count: u64,
    pub release_date: String,
    pub video_url: Option<String>,
    pub tracks: Vec<AlbumTrack>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlbumTrack {
    pub name: String,
    pub duration_ms: u64,
}

pub async fn search(term: &str, storefront: &str) -> Result<(Vec<Album>, String), String> {
    if term.trim().is_empty() {
        return Err("Apple 专辑搜索词为空".into());
    }
    let media_token = STATE.lock().unwrap().media_user_token.clone();
    let place = if storefront.trim().is_empty() {
        if let Some(token) = media_token.as_deref() {
            resolve_place(token).await
        } else {
            Place::unlogged_default()
        }
    } else {
        Place::for_storefront(storefront)
    };
    let url = format!(
        "{AMP_API}/v1/catalog/{}/search?types=albums&term={}&limit=25&l={}",
        place.storefront, urlencoding::encode(term), search_language(&place.storefront),
    );
    let value = request(&url, &place, media_token.as_deref()).await?;
    let albums = value.pointer("/results/albums/data").and_then(Value::as_array)
        .into_iter().flatten().filter_map(parse).collect();
    Ok((albums, place.storefront))
}

pub async fn detail(id: &str, storefront: &str) -> Result<Album, String> {
    if id.is_empty() || !id.bytes().all(|c| c.is_ascii_digit()) || storefront.trim().is_empty() {
        return Err("Apple 专辑 id 或区域无效".into());
    }
    let place = Place::for_storefront(storefront);
    let media_token = STATE.lock().unwrap().media_user_token.clone();
    let url = format!(
        "{AMP_API}/v1/catalog/{}/albums/{id}?extend=editorialVideo&include=tracks&l={}",
        place.storefront, search_language(&place.storefront),
    );
    let value = request(&url, &place, media_token.as_deref()).await?;
    let item = value.pointer("/data/0").ok_or("Apple 专辑不存在")?;
    let mut album = parse(item).ok_or("Apple 专辑缺少资料")?;
    let mut page = item.pointer("/relationships/tracks").cloned().unwrap_or(Value::Null);
    // 曲目关系可能分页；残缺的曲目表不能被上层当作另一个专辑版本参与匹配。
    for _ in 0..20 {
        album.tracks.extend(parse_tracks(&page));
        let Some(next) = page.get("next").and_then(Value::as_str) else {
            // Apple 的 trackCount 包含随专辑附送的 MV；网易详情只列音轨。
            album.track_count = album.tracks.len() as u64;
            return Ok(album);
        };
        if !next.starts_with(&format!("/v1/catalog/{}/albums/{id}/tracks?", place.storefront)) {
            return Err("Apple 专辑曲目分页地址无效".into());
        }
        page = request(&format!("{AMP_API}{next}"), &place, media_token.as_deref()).await?;
    }
    Err("Apple 专辑曲目分页过多".into())
}

async fn request(url: &str, place: &Place, token: Option<&str>) -> Result<Value, String> {
    let mut response = get_authorized(url, token, &place.accept_language).await?;
    if should_refresh_access_token(&response) {
        invalidate_access_token();
        response = get_authorized(url, token, &place.accept_language).await?;
    }
    if !(200..300).contains(&response.status) {
        return Err(format!("Apple 专辑请求返回 HTTP {}", response.status));
    }
    serde_json::from_str(&response.body).map_err(|error| format!("Apple 专辑响应不是 JSON: {error}"))
}

fn parse(item: &Value) -> Option<Album> {
    let id = item.get("id")?.as_str()?;
    if id.is_empty() || !id.bytes().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let attrs = item.get("attributes")?;
    Some(Album {
        id: id.to_string(),
        name: str_attr(attrs, "name"),
        artist: str_attr(attrs, "artistName"),
        track_count: attrs.get("trackCount").and_then(Value::as_u64).unwrap_or(0),
        release_date: str_attr(attrs, "releaseDate"),
        video_url: ["motionSquareVideo1x1", "motionDetailSquare"].iter().find_map(|key| {
            attrs.pointer(&format!("/editorialVideo/{key}/video"))
                .and_then(Value::as_str).filter(|url| url.starts_with("https://"))
                .map(str::to_string)
        }),
        tracks: Vec::new(),
    })
}

fn parse_tracks(page: &Value) -> Vec<AlbumTrack> {
    page.get("data").and_then(Value::as_array).into_iter().flatten()
        .filter(|item| item.get("type").and_then(Value::as_str) == Some("songs"))
        .filter_map(|item| {
            let attrs = item.get("attributes")?;
            Some(AlbumTrack {
                name: str_attr(attrs, "name"),
                duration_ms: attrs.get("durationInMillis").and_then(Value::as_u64).unwrap_or(0),
            })
        }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn square_video_is_preferred_and_portrait_is_not_used() {
        let mut item = json!({"id":"123","attributes":{
            "name":"Album", "artistName":"Artist", "trackCount":1,
            "editorialVideo":{"motionDetailTall":{"video":"https://example.com/tall.m3u8"}}
        }});
        assert!(parse(&item).unwrap().video_url.is_none());
        item["attributes"]["editorialVideo"]["motionDetailSquare"] =
            json!({"video":"https://example.com/detail-square.m3u8"});
        assert_eq!(parse(&item).unwrap().video_url.as_deref(), Some("https://example.com/detail-square.m3u8"));
        item["attributes"]["editorialVideo"]["motionSquareVideo1x1"] =
            json!({"video":"https://example.com/square.m3u8"});
        assert_eq!(parse(&item).unwrap().video_url.as_deref(), Some("https://example.com/square.m3u8"));
        assert!(parse(&json!({"id":"123"})).is_none());
    }

    #[test]
    fn bundled_music_videos_are_not_audio_tracks() {
        let page = json!({"data":[
            {"type":"songs","attributes":{"name":"Song","durationInMillis":180000}},
            {"type":"music-videos","attributes":{"name":"Song","durationInMillis":200000}}
        ]});
        let tracks = parse_tracks(&page);
        assert_eq!(tracks.len(), 1);
        assert_eq!(tracks[0].duration_ms, 180000);
    }

    #[test]
    #[ignore = "Checks live Apple album matching metadata and HLS"]
    fn live_album_motion() {
        crate::engine::RUNTIME.block_on(async {
            let (albums, storefront) = search("Midnights Taylor Swift", "us").await.unwrap();
            assert!(!albums.is_empty());
            let mut found = false;
            for candidate in albums.iter().filter(|album| album.name == "Midnights") {
                let album = detail(&candidate.id, &storefront).await.unwrap();
                assert_eq!(album.tracks.len() as u64, album.track_count);
                if let Some(video) = album.video_url {
                    let response = crate::engine::HTTP.get(video).send().await.unwrap();
                    assert!(response.status().is_success());
                    let playlist = response.text().await.unwrap();
                    assert!(playlist.starts_with("#EXTM3U"));
                    let resolutions = Regex::new(r"RESOLUTION=(\d+)x(\d+)").unwrap();
                    let sizes: Vec<_> = resolutions.captures_iter(&playlist).map(|capture| {
                        (capture[1].parse::<u32>().unwrap(), capture[2].parse::<u32>().unwrap())
                    }).collect();
                    assert!(!sizes.is_empty() && sizes.iter().all(|(width, height)| height == width));
                    println!("Album {}: {} tracks, square HLS resolutions {sizes:?}", album.id, album.track_count);
                    found = true;
                    break;
                }
            }
            assert!(found, "No square motion cover found");
        });
    }
}
