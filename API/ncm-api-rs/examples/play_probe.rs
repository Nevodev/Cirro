/// 探测 song_url_v1 与 song_detail 的真实返回结构。
/// 运行: cargo run --example play_probe            (带 cookie.txt)
///       cargo run --example play_probe -- anon    (匿名，看未登录时的表现)
use ncm_api_rs::{create_client, Query};
use std::fs;

const IDS: &[&str] = &["3402673449"];

#[tokio::main]
async fn main() {
    let anon = std::env::args().any(|a| a == "anon");
    let cookie = if anon {
        None
    } else {
        fs::read_to_string("cookie.txt").ok().map(|s| s.trim().to_string())
    };
    println!("模式: {}", if anon { "匿名" } else { "带 cookie" });

    let client = create_client(cookie);

    println!("\n================ song_url_v1 (level=standard) ================");
    for id in IDS {
        let q = Query::new().param("id", *id).param("level", "standard");
        match client.song_url_v1(&q).await {
            Ok(r) => {
                let data = r.body.get("data").and_then(|d| d.as_array());
                match data.and_then(|a| a.first()) {
                    Some(first) => {
                        let url = first.get("url").and_then(|u| u.as_str());
                        println!(
                            "  id={:<12} code={:<6} level={:<10} type={:<8} size={:<10} url={}",
                            id,
                            first.get("code").map(|c| c.to_string()).unwrap_or("-".into()),
                            first.get("level").and_then(|l| l.as_str()).unwrap_or("-"),
                            first.get("type").and_then(|t| t.as_str()).unwrap_or("-"),
                            first.get("size").map(|s| s.to_string()).unwrap_or("-".into()),
                            match url {
                                Some(u) => format!("{}…(len {})", &u[..u.len().min(70)], u.len()),
                                None => "<null>".to_string(),
                            }
                        );
                    }
                    None => println!("  id={:<12} data 为空: {}", id, r.body),
                }
            }
            Err(e) => println!("  id={:<12} 请求失败: {}", id, e),
        }
    }

    println!("\n================ song_url_v1 第一条完整结构 ================");
    let q = Query::new().param("id", IDS[0]).param("level", "standard");
    if let Ok(r) = client.song_url_v1(&q).await {
        println!("{}", serde_json::to_string_pretty(&r.body).unwrap_or_default());
    }

    println!("\n================ song_detail ================");
    let q = Query::new().param("ids", IDS[0]);
    match client.song_detail(&q).await {
        Ok(r) => {
            let songs = r.body.get("songs").and_then(|s| s.as_array());
            match songs.and_then(|a| a.first()) {
                Some(song) => {
                    println!("顶层字段: {:?}", r.body.as_object().map(|o| o.keys().collect::<Vec<_>>()));
                    println!("歌曲字段: {:?}", song.as_object().map(|o| o.keys().collect::<Vec<_>>()));
                    println!("\n完整结构:\n{}", serde_json::to_string_pretty(song).unwrap_or_default());
                }
                None => println!("songs 为空: {}", r.body),
            }
        }
        Err(e) => println!("请求失败: {}", e),
    }
}
