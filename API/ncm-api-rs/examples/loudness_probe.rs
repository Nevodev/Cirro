/// 探测「统一响度」能用的数据源：
///   1. song_url_v1 的原始返回里有没有 gain / peak
///   2. /api/song/loudness 这个端点还活着吗（crate 里没有，用通用 request 试）
/// 运行: cargo run --example loudness_probe
use ncm_api_rs::{create_client, CryptoType, Query, RequestOption};
use serde_json::json;
use std::fs;

const IDS: &[&str] = &["3402673449", "186016"];

#[tokio::main]
async fn main() {
    let cookie = fs::read_to_string("cookie.txt")
        .ok()
        .map(|s| s.trim().to_string());
    let client = create_client(cookie);

    println!("========== 1b. 一次传多个 id（ids 是字符串拼接，看能否批量拿 gain）==========");
    let q = Query::new()
        .param("id", IDS.join(",").as_str())
        .param("level", "standard");
    match client.song_url_v1(&q).await {
        Ok(r) => {
            let arr = r.body.get("data").and_then(|d| d.as_array());
            println!("data 条数 = {}", arr.map(|a| a.len()).unwrap_or(0));
            if let Some(a) = arr {
                for it in a {
                    println!(
                        "  id={:<12} gain={:<10} peak={:<8} closedGain={:<10} closedPeak={}",
                        it.get("id").map(|v| v.to_string()).unwrap_or("-".into()),
                        it.get("gain").map(|v| v.to_string()).unwrap_or("(无)".into()),
                        it.get("peak").map(|v| v.to_string()).unwrap_or("(无)".into()),
                        it.get("closedGain").map(|v| v.to_string()).unwrap_or("(无)".into()),
                        it.get("closedPeak").map(|v| v.to_string()).unwrap_or("(无)".into()),
                    );
                }
            }
        }
        Err(e) => println!("请求失败: {e}"),
    }

    println!("\n========== 1. song_url_v1 原始返回的字段 ==========");
    let q = Query::new().param("id", IDS[0]).param("level", "standard");
    match client.song_url_v1(&q).await {
        Ok(r) => {
            if let Some(first) = r
                .body
                .get("data")
                .and_then(|d| d.as_array())
                .and_then(|a| a.first())
            {
                let mut keys: Vec<String> = first
                    .as_object()
                    .map(|o| o.keys().cloned().collect())
                    .unwrap_or_default();
                keys.sort();
                println!("data[0] 全部字段: {:?}", keys);
                for k in ["gain", "peak", "loudness", "level", "br"] {
                    println!(
                        "  {:<9} = {}",
                        k,
                        first
                            .get(k)
                            .map(|v| v.to_string())
                            .unwrap_or_else(|| "(不存在)".into())
                    );
                }
            } else {
                println!("data 为空: {}", r.body);
            }
        }
        Err(e) => println!("请求失败: {}", e),
    }

    let ids = format!("[{}]", IDS.join(","));
    for (name, ct) in [
        ("Api(明文)", CryptoType::Api),
        ("Eapi", CryptoType::Eapi),
        ("Weapi", CryptoType::Weapi),
    ] {
        println!("\n========== 2. /api/song/loudness  [{name}] ==========");
        let opt = RequestOption {
            crypto: ct,
            ..Default::default()
        };
        let body = json!({ "ids": ids });
        match client.request("/api/song/loudness", body, opt).await {
            Ok(r) => {
                println!("code={}", r.body.get("code").map(|c| c.to_string()).unwrap_or("-".into()));
                println!("{}", serde_json::to_string_pretty(&r.body).unwrap_or_default());
            }
            Err(e) => println!("请求失败: {e}"),
        }
    }

    println!("\n========== 4. 真实曲库里的 gain 分布 ==========");
    let q = Query::new().param("keywords", "周杰伦").param("limit", "10");
    match client.cloudsearch(&q).await {
        Ok(r) => {
            let ids: Vec<String> = r
                .body
                .pointer("/result/songs")
                .and_then(|s| s.as_array())
                .map(|a| {
                    a.iter()
                        .filter_map(|s| s.get("id").map(|v| v.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            println!("搜到 {} 首", ids.len());
            if !ids.is_empty() {
                let q2 = Query::new()
                    .param("id", ids.join(",").as_str())
                    .param("level", "standard");
                match client.song_url_v1(&q2).await {
                    Ok(r2) => {
                        if let Some(arr) = r2.body.get("data").and_then(|d| d.as_array()) {
                            println!(
                                "  {:<13} {:>10} {:>7} {:>11} {:>11}  {:>8}  可播?",
                                "id", "gain(dB)", "peak", "closedGain", "closedPeak", "系数"
                            );
                            for it in arr {
                                let g = it.get("gain").and_then(|v| v.as_f64());
                                let factor = g.map(|g| 10f64.powf(g / 20.0));
                                println!(
                                    "  {:<13} {:>10} {:>7} {:>11} {:>11}  {:>8.3}  {}",
                                    it.get("id").map(|v| v.to_string()).unwrap_or("-".into()),
                                    g.map(|g| format!("{g:.2}")).unwrap_or("null".into()),
                                    it.get("peak").map(|v| v.to_string()).unwrap_or("null".into()),
                                    it.get("closedGain")
                                        .map(|v| v.to_string())
                                        .unwrap_or("null".into()),
                                    it.get("closedPeak")
                                        .map(|v| v.to_string())
                                        .unwrap_or("null".into()),
                                    factor.unwrap_or(0.0),
                                    if it.get("url").and_then(|u| u.as_str()).is_some() {
                                        "是"
                                    } else {
                                        "否"
                                    }
                                );
                            }
                        }
                    }
                    Err(e) => println!("请求失败: {e}"),
                }
            }
        }
        Err(e) => println!("搜索失败: {e}"),
    }

    println!("\n========== 3. song_detail 里有没有响度 ==========");
    let q = Query::new().param("ids", ids.as_str());
    match client.song_detail(&q).await {
        Ok(r) => {
            if let Some(song) = r
                .body
                .get("songs")
                .and_then(|s| s.as_array())
                .and_then(|a| a.first())
            {
                let mut keys: Vec<String> = song
                    .as_object()
                    .map(|o| o.keys().cloned().collect())
                    .unwrap_or_default();
                keys.sort();
                println!("songs[0] 全部字段: {:?}", keys);
            }
        }
        Err(e) => println!("请求失败: {e}"),
    }
}
