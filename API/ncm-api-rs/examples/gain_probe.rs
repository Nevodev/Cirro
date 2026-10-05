/// 验证 gain 的语义：
///   1. 大样本里会不会出现正值（决定目标响度是不是"仅衰减"）
///   2. gain 是否随 level（音质档）变化（决定要不要按播放档取）
///   3. closedGain 是否恒为常数
/// 运行: cargo run --example gain_probe
use ncm_api_rs::{create_client, Query};
use std::collections::BTreeMap;
use std::fs;

async fn ids_for(client: &ncm_api_rs::ApiClient, kw: &str, limit: &str) -> Vec<String> {
    let q = Query::new().param("keywords", kw).param("limit", limit);
    match client.cloudsearch(&q).await {
        Ok(r) => r
            .body
            .pointer("/result/songs")
            .and_then(|s| s.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|s| s.get("id").map(|v| v.to_string()))
                    .collect()
            })
            .unwrap_or_default(),
        Err(_) => Vec::new(),
    }
}

struct Row {
    gain: Option<f64>,
    peak: Option<f64>,
    closed_gain: Option<f64>,
    closed_peak: Option<f64>,
    playable: bool,
}

async fn fetch(
    client: &ncm_api_rs::ApiClient,
    ids: &[String],
    level: &str,
) -> BTreeMap<String, Row> {
    let mut out = BTreeMap::new();
    let q = Query::new()
        .param("id", ids.join(",").as_str())
        .param("level", level);
    if let Ok(r) = client.song_url_v1(&q).await {
        if let Some(arr) = r.body.get("data").and_then(|d| d.as_array()) {
            for it in arr {
                let id = it.get("id").map(|v| v.to_string()).unwrap_or_default();
                let f = |k: &str| it.get(k).and_then(|v| v.as_f64());
                out.insert(
                    id,
                    Row {
                        gain: f("gain"),
                        peak: f("peak"),
                        closed_gain: f("closedGain"),
                        closed_peak: f("closedPeak"),
                        playable: it.get("url").and_then(|u| u.as_str()).is_some(),
                    },
                );
            }
        }
    }
    out
}

#[tokio::main]
async fn main() {
    let cookie = fs::read_to_string("cookie.txt")
        .ok()
        .map(|s| s.trim().to_string());
    let client = create_client(cookie);

    let mut ids: Vec<String> = Vec::new();
    for kw in ["周杰伦", "摇滚", "古典", "电子", "民谣"] {
        for id in ids_for(&client, kw, "15").await {
            if !ids.contains(&id) {
                ids.push(id);
            }
        }
    }
    println!("样本: {} 首（去重后）\n", ids.len());

    let std_map = fetch(&client, &ids, "standard").await;
    let los_map = fetch(&client, &ids, "lossless").await;

    let mut gains: Vec<f64> = Vec::new();
    let mut positive = 0;
    let mut null_gain = 0;
    let mut unplayable = 0;
    let mut closed_set: Vec<f64> = Vec::new();
    for (_, r) in &std_map {
        match r.gain {
            Some(g) => {
                gains.push(g);
                if g > 0.0 {
                    positive += 1;
                }
            }
            None => null_gain += 1,
        }
        if !r.playable {
            unplayable += 1;
        }
        if let Some(cg) = r.closed_gain {
            if !closed_set.iter().any(|v| (v - cg).abs() < 1e-9) {
                closed_set.push(cg);
            }
        }
    }
    gains.sort_by(|a, b| a.partial_cmp(b).unwrap());

    println!("========== gain 分布（level=standard, {} 首）==========", std_map.len());
    if let Some(min) = gains.first() {
        let max = gains.last().unwrap();
        let mean: f64 = gains.iter().sum::<f64>() / gains.len() as f64;
        println!("  最小值 {min:.2} dB / 最大值 {max:.2} dB / 均值 {mean:.2} dB");
    }
    println!("  正值条数 = {positive}");
    println!("  gain 为 null = {null_gain}");
    println!("  拿不到直链（无版权/VIP）= {unplayable}");
    println!("  closedGain 出现过的不同取值 = {closed_set:?}");

    println!("\n========== 同一首歌，standard vs lossless 的 gain 是否一致 ==========");
    let mut same = 0;
    let mut diff = 0;
    let mut both_none = 0;
    let mut shown = 0;
    for (id, a) in &std_map {
        let b = los_map.get(id);
        match (a.gain, b.and_then(|r| r.gain)) {
            (Some(x), Some(y)) => {
                if (x - y).abs() < 1e-6 {
                    same += 1;
                } else {
                    diff += 1;
                    if shown < 8 {
                        println!("  id={id:<12} standard={x:.2}  lossless={y:.2}  差={:.2} dB", y - x);
                        shown += 1;
                    }
                }
            }
            (None, None) => both_none += 1,
            _ => {}
        }
    }
    println!("  完全一致 = {same}   不一致 = {diff}   两边都无 = {both_none}");

    println!("\n========== gain 是否已被钳在「不削波」范围内 ==========");
    let mut over = 0;
    let mut maxv = 0f64;
    let mut clamp_exact = 0;
    let mut offenders: Vec<(String, f64, f64, f64)> = Vec::new();
    let mut n = 0;
    for (id, r) in &std_map {
        if let (Some(g), Some(p)) = (r.gain, r.peak) {
            if p <= 0.0 {
                continue;
            }
            n += 1;
            let v = p * 10f64.powf(g / 20.0);
            if v > maxv {
                maxv = v;
            }
            if v > 1.0001 {
                over += 1;
                offenders.push((id.clone(), g, p, v));
            }
            let limit = -20.0 * p.log10();
            if (g - limit).abs() < 0.02 {
                clamp_exact += 1;
            }
        }
    }
    println!("  有效样本 {n} 首");
    println!("  peak × 10^(gain/20) 的最大值 = {maxv:.4}   （>1.0 就会削波）");
    println!("  超过 1.0 的条数 = {over}");
    println!("  gain 恰好 == -20·log10(peak)（即被削波下限钳住）= {clamp_exact} 首");
    offenders.sort_by(|a, b| b.3.partial_cmp(&a.3).unwrap());
    for (id, g, p, v) in offenders.iter().take(6) {
        println!("    越界: id={id:<12} gain={g:.2} peak={p:.4} → {v:.4}");
    }

    println!("\n========== 所有 gain>0 的歌 ==========");
    for (id, r) in &std_map {
        if let Some(g) = r.gain {
            if g > 0.0 {
                let p = r.peak.unwrap_or(f64::NAN);
                println!(
                    "  id={id:<12} gain=+{g:.2} dB  peak={p:.4}  削波下限=-20log10(peak)={:.2}",
                    -20.0 * p.log10()
                );
            }
        }
    }

    println!("\n========== peak 与 gain 的关系（前 12 条）==========");
    println!("  {:<13} {:>9} {:>7} {:>11} {:>11}", "id", "gain", "peak", "closedGain", "closedPeak");
    for (id, r) in std_map.iter().take(12) {
        let s = |v: Option<f64>| v.map(|x| format!("{x:.4}")).unwrap_or("null".into());
        println!(
            "  {:<13} {:>9} {:>7} {:>11} {:>11}",
            id,
            s(r.gain),
            s(r.peak),
            s(r.closed_gain),
            s(r.closed_peak)
        );
    }
}
