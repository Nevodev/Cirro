/// 扫码登录完整验证
/// 运行: cargo run --example qrlogin
///
/// 流程: 取 unikey -> 终端渲染二维码 -> 轮询扫码状态 -> 用 cookie 取播放链接
use ncm_api_rs::{create_client, Query};
use qrcode::render::unicode;
use qrcode::QrCode;
use std::io::Write;

#[tokio::main]
async fn main() {
    let client = create_client(None);

    // 1. 获取 unikey
    let unikey = match client.login_qr_key(&Query::new()).await {
        Ok(r) => r.body["unikey"].as_str().unwrap_or("").to_string(),
        Err(e) => {
            eprintln!("获取 unikey 失败: {}", e);
            return;
        }
    };
    if unikey.is_empty() {
        eprintln!("unikey 为空");
        return;
    }

    // 2. 生成二维码（本地生成，不联网）
    let url = match client
        .login_qr_create(&Query::new().param("key", &unikey))
        .await
    {
        Ok(r) => r.body["data"]["qrurl"].as_str().unwrap_or("").to_string(),
        Err(e) => {
            eprintln!("生成二维码失败: {}", e);
            return;
        }
    };

    println!("\n请用「网易云音乐 App」扫描二维码登录：");
    match QrCode::new(url.as_bytes()) {
        Ok(code) => {
            let image = code
                .render::<unicode::Dense1x2>()
                .dark_color(unicode::Dense1x2::Dark)
                .light_color(unicode::Dense1x2::Light)
                .build();
            println!("{}", image);

            let svg = code
                .render::<qrcode::render::svg::Color>()
                .min_dimensions(260, 260)
                .dark_color(qrcode::render::svg::Color("#000000"))
                .light_color(qrcode::render::svg::Color("#ffffff"))
                .build();
            std::fs::write("login_qr.svg", &svg).expect("写入 svg 失败");
            println!("二维码已保存: login_qr.svg");
        }
        Err(e) => eprintln!("二维码渲染失败: {}", e),
    }
    println!("扫不出来可手动打开: {}", url);

    // 3. 轮询扫码状态（最多约 3 分钟）
    println!("\n等待扫码确认（约 3 分钟超时）...");
    let mut cookie: Option<String> = None;
    for i in 0..90 {
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        match client
            .login_qr_check(&Query::new().param("key", &unikey))
            .await
        {
            Ok(r) => {
                let code = r.body["code"].as_i64().unwrap_or(0);
                match code {
                    803 => {
                        println!("\n登录成功！");
                        println!("响应: {}", r.body);
                        if !r.cookie.is_empty() {
                            cookie = Some(r.cookie.join("; "));
                        }
                        break;
                    }
                    800 => {
                        println!("\n二维码已过期");
                        break;
                    }
                    801 => print!("\r[{}] 等待扫码...        ", i),
                    802 => print!("\r[{}] 已扫码，等待确认... ", i),
                    _ => print!("\r[{}] code={}           ", i, code),
                }
                std::io::stdout().flush().unwrap();
            }
            Err(e) => {
                println!("\n轮询失败: {}", e);
                break;
            }
        }
    }

    // 4. 用 cookie 取播放链接
    let cookie = match cookie {
        Some(c) => c,
        None => {
            println!("\n未获取到 cookie，跳过播放链接测试");
            return;
        }
    };
    println!("\n=== 用登录 cookie 取播放链接 ===");
    let c2 = create_client(Some(cookie));
    let q = Query::new()
        .param("id", "186016")
        .param("level", "standard");
    match c2.song_url_v1(&q).await {
        Ok(r) => {
            println!("status={}", r.status);
            if let Some(data) = r.body["data"].as_array() {
                for item in data {
                    let u = item["url"].as_str().unwrap_or("(空)");
                    println!(
                        "  url={} br={} size={}",
                        if u.is_empty() { "(空)" } else { &u[..80.min(u.len())] },
                        item["br"].as_i64().unwrap_or(0),
                        item["size"].as_i64().unwrap_or(0)
                    );
                }
            }
        }
        Err(e) => println!("失败: {}", e),
    }
}
