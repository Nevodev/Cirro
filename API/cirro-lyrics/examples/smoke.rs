/// 端到端冒烟：走 `cirro_lyrics::api` 这层门面（也就是 JNI 实际调用的那层），
/// 把四个源各跑一遍，确认正式实现（不是探针里的临时写法）行为正确。
///
/// 运行：cargo run --example smoke
use cirro_lyrics::api;
use std::fs;

/// 祝福 / YOASOBI：AMLL 里有、且带 appleMusicId。
const SONG_WITH_AM: &str = "1983292457";
/// かくれんぼ / RUQOA：AMLL 里有、但没有 appleMusicId。
const SONG_WITHOUT_AM: &str = "1987638572";

#[tokio::main]
async fn main() {
    match fs::read_to_string("../ncm-api-rs/cookie.txt") {
        Ok(c) => {
            let rc = api::set_cookie(c.trim().to_string());
            println!("set_cookie -> {rc}");
        }
        Err(e) => println!("读不到 cookie.txt: {e}"),
    }

    println!("\n--- 网易 ---");
    for id in [SONG_WITH_AM, SONG_WITHOUT_AM] {
        let v = api::netease_lyric(id.to_string()).await;
        let obj = v.as_object().unwrap();
        let lrc = obj.get("lrc").and_then(|x| x.as_str()).unwrap_or("");
        let tly = obj.get("translation").and_then(|x| x.as_str()).unwrap_or("");
        let rom = obj.get("roman").and_then(|x| x.as_str()).unwrap_or("");
        println!(
            "id={id} ok={:?} empty={:?} lrc={} translation={} roman={}",
            obj.get("ok"),
            obj.get("empty"),
            lrc.len(),
            tly.len(),
            rom.len()
        );
    }

    println!("\n--- AMLL ---");
    for id in [SONG_WITH_AM, SONG_WITHOUT_AM, "1"] {
        let v = api::amll_ttml(id.to_string()).await;
        let obj = v.as_object().unwrap();
        println!(
            "id={id} ok={:?} found={:?} ttml={} appleMusicIds={:?} base={:?}",
            obj.get("ok"),
            obj.get("found"),
            obj.get("ttml").and_then(|x| x.as_str()).map(str::len).unwrap_or(0),
            obj.get("appleMusicIds"),
            obj.get("base"),
        );
    }

    println!("\n--- Apple（未配 token，应给 reason=no_media_user_token 且不算失败）---");
    println!("appleConfigured = {}", api::apple_configured());
    println!("{}", api::apple_ttml("1645308331".to_string()).await);

    println!("\n--- lrclib（有同步词 / 只有纯文本 / 查不到）---");
    for (t, a, d) in [
        ("Hello", "Adele", "295"),
        ("祝福", "YOASOBI", "196"),
        ("不存在的歌名xyz", "不存在的歌手", ""),
    ] {
        let v = api::lrclib_lyric(t.to_string(), a.to_string(), String::new(), d.to_string()).await;
        let obj = v.as_object().unwrap();
        println!(
            "{t}/{a}: ok={:?} found={:?} empty={:?} instrumental={:?} synced={} plain={}",
            obj.get("ok"),
            obj.get("found"),
            obj.get("empty"),
            obj.get("instrumental"),
            obj.get("syncedLyrics").and_then(|x| x.as_str()).map(str::len).unwrap_or(0),
            obj.get("plainLyrics").and_then(|x| x.as_str()).map(str::len).unwrap_or(0),
        );
    }

    println!("\nversion = {}", cirro_lyrics::version());
}
