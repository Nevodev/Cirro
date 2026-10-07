/// 把网易三份歌词落到临时文件，用于核对「翻译/罗马音的时间戳是否与主歌词对齐」。
///
/// 运行：cargo run --example dump -- 1983292457
use std::fs;

#[tokio::main]
async fn main() {
    let id = std::env::args().nth(1).unwrap_or_else(|| "1983292457".to_string());
    let cookie = fs::read_to_string("../ncm-api-rs/cookie.txt").expect("cookie.txt");
    cirro_lyrics::api::set_cookie(cookie.trim().to_string());

    let value = cirro_lyrics::api::netease_lyric(id.clone()).await;
    let obj = value.as_object().expect("对象");
    let get = |k: &str| obj.get(k).and_then(|v| v.as_str()).unwrap_or("");

    fs::write("dump_lrc.txt", get("lrc")).unwrap();
    fs::write("dump_tlyric.txt", get("translation")).unwrap();
    fs::write("dump_romalrc.txt", get("roman")).unwrap();
    println!(
        "id={id} lrc={} translation={} roman={}",
        get("lrc").len(),
        get("translation").len(),
        get("roman").len()
    );
}
