//! Anonymous QQ Music search and QRC download, following Lyricify-Lyrics-Helper.
//! See THIRD_PARTY_NOTICES.md for attribution.

use crate::engine;
use quick_xml::{events::Event, Reader};
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::time::Duration;

const SEARCH_KEY: &str = "music.search.SearchCgiService.DoSearchForQQMusicMobile";
const UA: &str = "QQMusic 20080508(android)";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Song {
    id: String,
    name: String,
    artists: Vec<String>,
    album: String,
    duration_ms: u64,
}

#[derive(Default, Serialize)]
pub struct Lyric {
    pub qrc: String,
    pub translation: String,
    pub roman: String,
}

pub async fn search(term: &str) -> Result<Vec<Song>, String> {
    if term.trim().is_empty() {
        return Ok(vec![]);
    }
    let body = json!({
        "comm": {"ct":"11", "v":"20080508", "tmeLoginType":"0", "tmeLoginMethod":"0"},
        (SEARCH_KEY): {
            "module":"music.search.SearchCgiService", "method":"DoSearchForQQMusicMobile",
            "param": {"query":term,"highlight":1,"searchid":"","sub_searchid":0,
                "search_type":0,"nqc_flag":0,"sin":0,"ein":30,"page_num":1,"num_per_page":30,
                "cat":2,"grp":1,"remoteplace":"","from":"","multi_zhida":1,"sem":0,
                "related_data":"","ambi_data":""}
        }
    });
    // Anonymous mobile search must omit `cv`, otherwise QQ may return no songs.
    let data: Value = engine::HTTP
        .post("https://u6.y.qq.com/cgi-bin/musicu.fcg")
        .timeout(Duration::from_secs(8))
        .header("User-Agent", UA)
        .header("Referer", "https://c.y.qq.com/")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(body.to_string())
        .send()
        .await
        .map_err(|e| e.to_string())?
        .error_for_status()
        .map_err(|e| e.to_string())?
        .json()
        .await
        .map_err(|e| e.to_string())?;
    parse_search(&data)
}

fn clean(value: &Value) -> String {
    value
        .as_str()
        .unwrap_or_default()
        .replace("<em>", "")
        .replace("</em>", "")
}

fn parse_search(data: &Value) -> Result<Vec<Song>, String> {
    let response = &data[SEARCH_KEY];
    if data["code"].as_i64() != Some(0) || response["code"].as_i64() != Some(0) {
        return Err(format!(
            "QQ search codes: {}/{}",
            data["code"], response["code"]
        ));
    }
    let entries = response["data"]["body"]["item_song"]
        .as_array()
        .ok_or("QQ search missing item_song")?;
    let mut songs = Vec::new();
    let mut seen = HashSet::new();
    fn append(entries: &[Value], songs: &mut Vec<Song>, seen: &mut HashSet<String>, depth: u8) {
        for entry in entries {
            let id = entry["id"]
                .as_u64()
                .filter(|id| *id > 0)
                .map(|id| id.to_string());
            if let Some(id) = id.filter(|id| seen.insert(id.clone())) {
                let mut name = clean(&entry["name"]);
                if name.is_empty() {
                    name = clean(&entry["title"]);
                }
                let subtitle = clean(&entry["subtitle"]);
                // Version labels may exist only in subtitle (live / accompaniment).
                if !subtitle.is_empty() {
                    name.push_str(&format!(" ({subtitle})"));
                }
                songs.push(Song {
                    id,
                    name,
                    artists: entry["singer"]
                        .as_array()
                        .map(|a| a.iter().map(|s| clean(&s["name"])).collect())
                        .unwrap_or_default(),
                    album: clean(&entry["album"]["name"]),
                    duration_ms: entry["interval"].as_u64().unwrap_or(0).saturating_mul(1000),
                });
            }
            if depth < 3 {
                if let Some(group) = entry["grp"].as_array() {
                    append(group, songs, seen, depth + 1);
                }
            }
        }
    }
    append(entries, &mut songs, &mut seen, 0);
    Ok(songs)
}

pub async fn fetch(id: &str) -> Result<Lyric, String> {
    if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
        return Err("invalid QQ song id".into());
    }
    let text = engine::HTTP
        .post("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg")
        .timeout(Duration::from_secs(8))
        .header("User-Agent", UA)
        .header("Referer", "https://c.y.qq.com/")
        .form(&[
            ("version", "15"),
            ("miniversion", "82"),
            ("lrctype", "4"),
            ("musicid", id),
        ])
        .send()
        .await
        .map_err(|e| e.to_string())?
        .error_for_status()
        .map_err(|e| e.to_string())?
        .text()
        .await
        .map_err(|e| e.to_string())?;
    parse_download(&text)
}

fn lyric_text(raw: &str) -> Result<String, String> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Ok(String::new());
    }
    let text = if raw.bytes().all(|b| b.is_ascii_hexdigit()) {
        super::qrc::decrypt(raw)?
    } else {
        raw.to_owned()
    };
    if !text.trim_start().starts_with('<') {
        return Ok(text);
    }
    let mut reader = Reader::from_str(&text);
    loop {
        match reader.read_event().map_err(|e| e.to_string())? {
            Event::Start(e) | Event::Empty(e) if e.name().as_ref() == b"Lyric_1" => {
                for attribute in e.attributes() {
                    let attribute = attribute.map_err(|e| e.to_string())?;
                    if attribute.key.as_ref() == b"LyricContent" {
                        return attribute
                            .unescape_value()
                            .map(|v| v.into_owned())
                            .map_err(|e| e.to_string());
                    }
                }
            }
            Event::Eof => return Ok(String::new()),
            _ => {}
        }
    }
}

fn parse_download(text: &str) -> Result<Lyric, String> {
    // QQ wraps its response document in a comment, including a nonstandard miniversion tag.
    let text = text
        .trim()
        .strip_prefix("<!--")
        .and_then(|t| t.trim().strip_suffix("-->"))
        .unwrap_or(text);
    let mut reader = Reader::from_str(text);
    let mut field = Vec::new();
    let mut raw = String::new();
    let mut result = Lyric::default();
    loop {
        match reader.read_event().map_err(|e| e.to_string())? {
            Event::Start(e)
                if matches!(
                    e.name().as_ref(),
                    b"content" | b"contentts" | b"contentroma"
                ) =>
            {
                field = e.name().as_ref().to_vec();
                raw.clear();
            }
            Event::Text(e) if !field.is_empty() => {
                let text = e.xml_content().map_err(|e| e.to_string())?;
                raw.push_str(&quick_xml::escape::unescape(&text).map_err(|e| e.to_string())?);
            }
            Event::CData(e) if !field.is_empty() => {
                raw.push_str(&e.xml_content().map_err(|e| e.to_string())?)
            }
            Event::End(e) if e.name().as_ref() == field.as_slice() => {
                // A damaged optional translation must not discard usable original lyrics.
                match lyric_text(&raw) {
                    Ok(text) => match field.as_slice() {
                        b"content" => result.qrc = text,
                        b"contentts" => result.translation = text,
                        b"contentroma" => result.roman = text,
                        _ => {}
                    },
                    Err(e) if field == b"content" => return Err(e),
                    Err(_) => {}
                }
                field.clear();
            }
            Event::Eof => return Ok(result),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_plain_lyrics_inside_commented_response() {
        let lyric = parse_download("<!--<root><miniversion=\"1\"/><content><![CDATA[[00:01.00]A & B]]></content><contentts>[00:01.00]翻译</contentts></root>-->").unwrap();
        assert_eq!(lyric.qrc, "[00:01.00]A & B");
        assert_eq!(lyric.translation, "[00:01.00]翻译");
    }
    #[test]
    fn unwraps_qrc_xml_attribute() {
        assert_eq!(lyric_text("<?xml version=\"1.0\"?><QrcInfos><Lyric_1 LyricContent=\"[100,200]A &amp; B(100,200)\"/></QrcInfos>").unwrap(), "[100,200]A & B(100,200)");
    }
    #[test]
    fn search_includes_grouped_versions_and_removes_highlights() {
        let data = json!({"code":0,(SEARCH_KEY):{"code":0,"data":{"body":{"item_song":[{"id":1,"name":"<em>Song</em>","singer":[{"name":"Artist"}],"interval":123,"grp":[{"id":2,"name":"Song","subtitle":"Live"}]}]}}}});
        let songs = parse_search(&data).unwrap();
        assert_eq!(songs.len(), 2);
        assert_eq!(songs[0].name, "Song");
        assert_eq!(songs[0].duration_ms, 123000);
        assert_eq!(songs[1].name, "Song (Live)");
    }
}
