/// 探针：验证登录链路与播放链接可达性
/// 运行: cargo run --example probe
use ncm_api_rs::{create_client, Query};

#[tokio::main]
async fn main() {
    let client = create_client(None);

    println!("=== 1. 扫码登录 key ===");
    match client.login_qr_key(&Query::new()).await {
        Ok(r) => println!("status={} body={}", r.status, r.body),
        Err(e) => println!("失败: {}", e),
    }

    println!("\n=== 2. 匿名注册 ===");
    let anon_cookie = match client.register_anonimous(&Query::new()).await {
        Ok(r) => {
            println!("status={}", r.status);
            println!("body={}", r.body);
            println!("cookie={:?}", r.cookie);
            if r.cookie.is_empty() {
                None
            } else {
                Some(r.cookie.join("; "))
            }
        }
        Err(e) => {
            println!("失败: {}", e);
            None
        }
    };

    if let Some(c) = anon_cookie {
        println!("\n=== 3. 用匿名 cookie 取播放链接 ===");
        let c2 = create_client(Some(c));
        let q = Query::new()
            .param("id", "186016")
            .param("level", "standard");
        match c2.song_url_v1(&q).await {
            Ok(r) => {
                println!("status={}", r.status);
                if let Some(data) = r.body["data"].as_array() {
                    for item in data {
                        println!(
                            "  url={} br={} size={}",
                            item["url"].as_str().unwrap_or("(空)"),
                            item["br"].as_i64().unwrap_or(0),
                            item["size"].as_i64().unwrap_or(0)
                        );
                    }
                }
            }
            Err(e) => println!("失败: {}", e),
        }
    }
}
