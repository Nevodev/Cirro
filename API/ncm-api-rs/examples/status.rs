/// 查看 login_status 的返回结构（字段名以真实响应为准）
/// 运行: cargo run --example status
use ncm_api_rs::{create_client, Query};
use std::fs;

#[tokio::main]
async fn main() {
    let cookie = fs::read_to_string("cookie.txt").expect("没有 cookie.txt");
    let client = create_client(Some(cookie.trim().to_string()));

    match client.login_status(&Query::new()).await {
        Ok(r) => {
            println!("status={}", r.status);
            println!("{}", serde_json::to_string_pretty(&r.body).unwrap());
        }
        Err(e) => println!("失败: {}", e),
    }
}
