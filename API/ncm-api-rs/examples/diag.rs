/// 完整诊断：登录 + 保存 cookie + 多首歌曲播放链接实测
/// 运行: cargo run --example diag
///
/// cookie 保存在 cookie.txt，后续运行直接复用，无需重复扫码
use ncm_api_rs::{create_client, ApiClient, Query};
use qrcode::render::unicode;
use qrcode::QrCode;
use std::fs;
use std::io::Write;

const COOKIE_FILE: &str = "cookie.txt";

async fn qr_login(client: &ApiClient) -> Option<String> {
    let unikey = client
        .login_qr_key(&Query::new())
        .await
        .ok()?
        .body["unikey"]
        .as_str()?
        .to_string();

    let url = client
        .login_qr_create(&Query::new().param("key", &unikey))
        .await
        .ok()?
        .body["data"]["qrurl"]
        .as_str()?
        .to_string();

    println!("\n请用「网易云音乐 App」扫码：");
    if let Ok(code) = QrCode::new(url.as_bytes()) {
        println!(
            "{}",
            code.render::<unicode::Dense1x2>()
                .dark_color(unicode::Dense1x2::Dark)
                .light_color(unicode::Dense1x2::Light)
                .build()
        );
        let svg = code
            .render::<qrcode::render::svg::Color>()
            .min_dimensions(260, 260)
            .dark_color(qrcode::render::svg::Color("#000000"))
            .light_color(qrcode::render::svg::Color("#ffffff"))
            .build();
        fs::write("login_qr.svg", &svg).ok();
    }

    println!("\n等待扫码确认（约 3 分钟）...");
    for i in 0..90 {
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        let r = match client
            .login_qr_check(&Query::new().param("key", &unikey))
            .await
        {
            Ok(r) => r,
            Err(_) => continue,
        };
        match r.body["code"].as_i64().unwrap_or(0) {
            803 => {
                println!("\n登录成功！");
                if !r.cookie.is_empty() {
                    return Some(r.cookie.join("; "));
                }
                return None;
            }
            800 => {
                println!("\n二维码过期");
                return None;
            }
            801 => print!("\r[{}] 等待扫码...        ", i),
            802 => print!("\r[{}] 已扫码，等确认...   ", i),
            c => print!("\r[{}] code={}            ", i, c),
        }
        std::io::stdout().flush().unwrap();
    }
    println!("\n超时");
    None
}

async fn probe_url(client: &ApiClient, name: &str, id: &str, level: &str) {
    let q = Query::new().param("id", id).param("level", level);
    match client.song_url_v1(&q).await {
        Ok(r) => {
            let code = r.body["code"].as_i64().unwrap_or(0);
            let d = &r.body["data"];
            if let Some(item) = d.as_array().and_then(|a| a.first()) {
                let u = item["url"].as_str().unwrap_or("");
                let br = item["br"].as_i64().unwrap_or(0);
                let size = item["size"].as_i64().unwrap_or(0);
                let fee = item["fee"].as_i64().unwrap_or(-1);
                if u.is_empty() {
                    println!(
                        "  [{}] {:<8} id={:<12} ✗ 空 URL (code={} fee={})",
                        name, level, id, code, fee
                    );
                } else {
                    println!(
                        "  [{}] {:<8} id={:<12} ✓ br={} size={} fee={}",
                        name, level, id, br, size, fee
                    );
                    println!("      {}", &u[..100.min(u.len())]);
                }
            } else {
                println!("  [{}] {:<8} 无 data: {}", name, level, r.body);
            }
        }
        Err(e) => println!("  [{}] {:<8} 请求失败: {}", name, level, e),
    }
}

async fn search_top(client: &ApiClient, kw: &str) -> Option<(String, String)> {
    let q = Query::new()
        .param("keywords", kw)
        .param("type", "1")
        .param("limit", "3");
    let r = client.cloudsearch(&q).await.ok()?;
    let songs = r.body["result"]["songs"].as_array()?;
    let s = songs.first()?;
    Some((
        s["name"].as_str().unwrap_or("?").to_string(),
        s["id"].as_i64().unwrap_or(0).to_string(),
    ))
}

#[tokio::main]
async fn main() {
    let anon = create_client(None);

    // 1. 获取 cookie（优先复用本地文件）
    let cookie = match fs::read_to_string(COOKIE_FILE) {
        Ok(c) if !c.trim().is_empty() => {
            println!("复用已保存的 cookie");
            c
        }
        _ => match qr_login(&anon).await {
            Some(c) => {
                fs::write(COOKIE_FILE, &c).expect("保存 cookie 失败");
                println!("cookie 已保存到 {}", COOKIE_FILE);
                c
            }
            None => {
                eprintln!("登录失败");
                return;
            }
        },
    };

    let client = create_client(Some(cookie));

    // 2. 确认登录态
    println!("\n=== 登录状态 ===");
    match client.login_status(&Query::new()).await {
        Ok(r) => {
            let d = &r.body["data"];
            println!(
                "  code={} uid={} 昵称={}",
                r.body["code"].as_i64().unwrap_or(0),
                d["profile"]["userId"].as_i64().unwrap_or(0),
                d["profile"]["nickname"].as_str().unwrap_or("?")
            );
        }
        Err(e) => println!("  查询失败: {}", e),
    }

    // 3. 用真实曲库歌曲测播放链接（搜索到的歌必然在曲库内）
    println!("\n=== 播放链接实测（standard）===");
    for kw in ["起风了", "孤勇者", "海阔天空"] {
        match search_top(&client, kw).await {
            Some((name, id)) => {
                println!("\n《{}》", name);
                probe_url(&client, kw, &id, "standard").await;
            }
            None => println!("\n《{}》搜索失败", kw),
        }
    }

    // 4. 同一首歌不同音质
    println!("\n=== 音质对比 ===");
    if let Some((name, id)) = search_top(&client, "起风了").await {
        println!("《{}》 id={}", name, id);
        for lv in ["standard", "exhigh", "lossless"] {
            probe_url(&client, "音质", &id, lv).await;
        }
    }

    println!("\n诊断结束。");
}
