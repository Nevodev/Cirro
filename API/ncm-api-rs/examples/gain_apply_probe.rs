/// 验证原生层新暴露的 gain / peak 在真实响应里能不能取到，以及"0.9 基线 + peak 钳制"
/// 在真实数据上会算出什么。
///
/// 重点看两件事：
///   1. gain / peak 的**原始 JSON 类型**——如果是字符串，`as_f64()` 会静默返回 None，
///      整个响度归一就哑了，而调用方一点报错都看不到。
///   2. 钳制后输出峰值有没有真的压回 1.0 以内。
///
/// 运行: cargo run --example gain_apply_probe
use ncm_api_rs::{create_client, Query};
use std::fs;

const BASE_GAIN: f64 = 0.9;

#[tokio::main]
async fn main() {
    let cookie = fs::read_to_string("cookie.txt")
        .ok()
        .map(|s| s.trim().to_string());
    let client = create_client(cookie);

    let mut ids: Vec<String> = vec!["3402673449".into()];
    let q = Query::new().param("keywords", "周杰伦").param("limit", "8");
    if let Ok(r) = client.cloudsearch(&q).await {
        if let Some(arr) = r.body.pointer("/result/songs").and_then(|s| s.as_array()) {
            for s in arr {
                if let Some(id) = s.get("id") {
                    ids.push(id.to_string());
                }
            }
        }
    }

    println!("样本 {} 首（level=standard）\n", ids.len());
    println!(
        "  {:<12} {:>4} {:>14} {:>12} {:>9} {:>9} {:>9} {:>5} {:>9}",
        "id", "直链", "gain 原始", "peak 原始", "gain dB", "0.9×增益", "最终", "钳制", "输出峰值"
    );

    let mut null_gain = 0;
    let mut clamped = 0;
    let mut over_after = 0;
    let mut checked = 0;

    for id in &ids {
        let q = Query::new().param("id", id.as_str()).param("level", "standard");
        let Ok(r) = client.song_url_v1(&q).await else {
            println!("  {id:<12} 请求失败");
            continue;
        };
        let first = &r.body["data"][0];
        let has_url = !first["url"].as_str().unwrap_or("").is_empty();
        let gain_raw = &first["gain"];
        let peak_raw = &first["peak"];
        let gain = gain_raw.as_f64();
        let peak = peak_raw.as_f64();

        if gain.is_none() {
            null_gain += 1;
        }

        let mut total = BASE_GAIN * gain.map(|g| 10f64.powf(g / 20.0)).unwrap_or(1.0);
        let raw_total = total;
        let mut is_clamped = false;
        if let Some(p) = peak {
            if p > 0.0 && total > 1.0 / p {
                total = 1.0 / p;
                is_clamped = true;
            }
        }
        if is_clamped {
            clamped += 1;
        }
        let out_peak = peak.map(|p| p * total);
        if let Some(v) = out_peak {
            checked += 1;
            if v > 1.0001 {
                over_after += 1;
            }
        }

        let f = |v: Option<f64>| v.map(|x| format!("{x:.3}")).unwrap_or("null".into());
        println!(
            "  {:<12} {:>4} {:>14} {:>12} {:>9} {:>9} {:>9} {:>5} {:>9}",
            id,
            if has_url { "有" } else { "无" },
            gain_raw.to_string(),
            peak_raw.to_string(),
            f(gain),
            format!("{raw_total:.3}"),
            format!("{total:.3}"),
            if is_clamped { "是" } else { "" },
            f(out_peak),
        );
    }

    println!("\n  gain 取不到（null）= {null_gain}");
    println!("  被 peak 钳过 = {clamped}");
    println!("  钳制后仍然 >1.0 = {over_after}   （必须为 0，否则钳制逻辑有问题，检查了 {checked} 首）");
}
