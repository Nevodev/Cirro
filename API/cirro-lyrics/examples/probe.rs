/// 四个取词源的真实返回探测。
///
/// 目的：在写正式实现前，把每个源**真实**的响应形状 / 可用性钉死，不靠记忆。
///
/// 运行（CWD 必须是本 crate 根）：
///   cargo run --example probe
///
/// cookie 从 `../ncm-api-rs/cookie.txt` 读（跟 ncm-api-rs 的例程同一份）。
use ncm_api_rs::{create_client, Query};
use std::fs;

const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

/// 一首确定在 AMLL DB 里、且带 appleMusicId 的歌（祝福 / YOASOBI）。
const SONG_WITH_AM: &str = "1983292457";
/// 一首在 AMLL DB 里、但不带 appleMusicId 的歌（かくれんぼ）。
const SONG_WITHOUT_AM: &str = "1987638572";

#[tokio::main]
async fn main() {
    let http = reqwest::Client::builder()
        .user_agent(UA)
        .build()
        .expect("http client");

    probe_netease().await;
    probe_amll(&http).await;
    probe_lrclib(&http).await;
    probe_apple(&http).await;
}

// ---------------------------------------------------------------------------
// 1. 网易歌词：/api/song/lyric 与 /api/song/lyric/v1 的真实字段
// ---------------------------------------------------------------------------
async fn probe_netease() {
    println!("\n================ 1. 网易歌词 ================");
    let cookie = match fs::read_to_string("../ncm-api-rs/cookie.txt") {
        Ok(c) => c.trim().to_string(),
        Err(e) => {
            println!("读不到 cookie.txt（{e}），跳过");
            return;
        }
    };
    let client = create_client(Some(cookie));

    for id in [SONG_WITH_AM, SONG_WITHOUT_AM] {
        let q = Query::new().param("id", id);
        match client.lyric(&q).await {
            Ok(r) => {
                println!("\n--- /api/song/lyric  id={id}  status={}", r.status);
                if let Some(obj) = r.body.as_object() {
                    println!("顶层键: {:?}", obj.keys().collect::<Vec<_>>());
                    for k in ["lrc", "tlyric", "romalrc", "yrc", "klyric"] {
                        if let Some(v) = obj.get(k) {
                            let text = v.get("lyric").and_then(|x| x.as_str()).unwrap_or("");
                            println!(
                                "  {k}: version={} len={} head={:?}",
                                v.get("version").map(|x| x.to_string()).unwrap_or("-".into()),
                                text.len(),
                                text.chars().take(60).collect::<String>()
                            );
                        }
                    }
                }
            }
            Err(e) => println!("/api/song/lyric id={id} 失败: {e}"),
        }

        match client.lyric_new(&q).await {
            Ok(r) => {
                println!("\n--- /api/song/lyric/v1  id={id}  status={}", r.status);
                if let Some(obj) = r.body.as_object() {
                    println!("顶层键: {:?}", obj.keys().collect::<Vec<_>>());
                }
            }
            Err(e) => println!("/api/song/lyric/v1 id={id} 失败: {e}"),
        }
    }
}

// ---------------------------------------------------------------------------
// 2. AMLL TTML DB：五家 base 的真实可用性与错误页判别
// ---------------------------------------------------------------------------
async fn probe_amll(http: &reqwest::Client) {
    println!("\n================ 2. AMLL TTML DB ================");

    fn bases(id: &str) -> Vec<String> {
        vec![
            format!("https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/ncm-lyrics/{id}.ttml"),
            format!("https://amll-ttml-db.stevexmh.net/ncm/{id}"),
            format!("https://amlldb.bikonoo.com/ncm-lyrics/{id}.ttml"),
            format!("https://amll.mirror.dimeta.top/api/db/ncm-lyrics/{id}.ttml"),
            format!("https://amll-ttml-db.gbclstudio.cn/ncm-lyrics/{id}.ttml"),
        ]
    }

    for id in [SONG_WITH_AM, SONG_WITHOUT_AM] {
        println!("\n===== ncm id = {id} =====");
        for url in bases(id) {
            match http.get(&url).send().await {
                Ok(resp) => {
                    let status = resp.status();
                    let ctype = resp
                        .headers()
                        .get(reqwest::header::CONTENT_TYPE)
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or("-")
                        .to_string();
                    let body = resp.text().await.unwrap_or_default();
                    let is_ttml = body.contains("<tt") && !body.contains("<html");
                    println!(
                        "  [{status}] len={:<7} ctype={:<28} ttml={} head={:?}",
                        body.len(),
                        ctype,
                        is_ttml,
                        body.chars().take(48).collect::<String>()
                    );
                    if is_ttml {
                        // 抽 amll:meta（正式实现就靠这一步拿 appleMusicId）
                        let metas = extract_meta(&body);
                        println!("      amll:meta -> {metas:?}");
                    }
                }
                Err(e) => println!("  请求失败: {e}"),
            }
        }
    }
}

/// 极简抽取 `<amll:meta key="..." value="..."/>`（探针用，正式实现里同样够）。
fn extract_meta(ttml: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut rest = ttml;
    while let Some(pos) = rest.find("<amll:meta") {
        rest = &rest[pos..];
        let end = match rest.find("/>") {
            Some(e) => e,
            None => break,
        };
        let tag = &rest[..end];
        let key = attr(tag, "key");
        let value = attr(tag, "value");
        if let (Some(k), Some(v)) = (key, value) {
            out.push((k, v));
        }
        rest = &rest[end..];
    }
    out
}

fn attr(tag: &str, name: &str) -> Option<String> {
    let pat = format!("{name}=\"");
    let start = tag.find(&pat)? + pat.len();
    let end = tag[start..].find('"')? + start;
    Some(tag[start..end].to_string())
}

// ---------------------------------------------------------------------------
// 3. lrclib：/api/get 的字段
// ---------------------------------------------------------------------------
async fn probe_lrclib(http: &reqwest::Client) {
    println!("\n================ 3. lrclib ================");
    let url = "https://lrclib.net/api/get?track_name=%E7%A5%9D%E7%A6%8F&artist_name=YOASOBI";
    match http
        .get(url)
        .header("User-Agent", "Cirro/1.0 (https://github.com/)")
        .send()
        .await
    {
        Ok(resp) => {
            println!("status={}", resp.status());
            let body = resp.text().await.unwrap_or_default();
            match serde_json::from_str::<serde_json::Value>(&body) {
                Ok(v) => {
                    if let Some(obj) = v.as_object() {
                        println!("顶层键: {:?}", obj.keys().collect::<Vec<_>>());
                        for k in ["trackName", "artistName", "albumName", "duration", "instrumental"] {
                            println!("  {k} = {:?}", obj.get(k));
                        }
                        let synced = obj.get("syncedLyrics").and_then(|x| x.as_str()).unwrap_or("");
                        println!("  syncedLyrics len={} head={:?}", synced.len(), synced.chars().take(80).collect::<String>());
                    } else {
                        println!("非对象: {}", &body[..body.len().min(300)]);
                    }
                }
                Err(_) => println!("非 JSON({}): {}", body.len(), &body[..body.len().min(300)]),
            }
        }
        Err(e) => println!("请求失败: {e}"),
    }
}

// ---------------------------------------------------------------------------
// 4. Apple Music：开发者 access token 抓取链路 + amp-api 无 token 时的表现
// ---------------------------------------------------------------------------
async fn probe_apple(http: &reqwest::Client) {
    println!("\n================ 4. Apple Music ================");

    let html = match http.get("https://music.apple.com/us/browse").send().await {
        Ok(r) => {
            println!("browse status={}", r.status());
            r.text().await.unwrap_or_default()
        }
        Err(e) => {
            println!("browse 请求失败: {e}");
            return;
        }
    };

    let scripts = find_index_scripts(&html);
    println!("index*.js 候选 {} 个: {:?}", scripts.len(), scripts.iter().take(3).collect::<Vec<_>>());

    let mut token: Option<String> = None;
    for url in scripts.iter().take(4) {
        match http.get(url).send().await {
            Ok(r) => {
                let js = r.text().await.unwrap_or_default();
                println!("  {url} -> len={}", js.len());
                if let Some(t) = find_bearer_jwt(&js) {
                    println!("  找到 JWT: {}...", &t[..t.len().min(24)]);
                    token = Some(t);
                    break;
                }
            }
            Err(e) => println!("  {url} 失败: {e}"),
        }
    }

    match &token {
        Some(t) => println!("access token 抓取成功（len={}）", t.len()),
        None => println!("access token 未抓到"),
    }

    // amp-api 直连（没有 media-user-token，预期 401/403；用来确认端点与鉴权行为）
    let url = "https://amp-api.music.apple.com/v1/catalog/cn/songs/1645308331?include[songs]=syllable-lyrics&l=zh-hans-cn&extend=ttmlLocalizations";
    let mut req = http
        .get(url)
        .header("Origin", "https://music.apple.com")
        .header("Accept", "application/json");
    if let Some(t) = &token {
        req = req.header("Authorization", format!("Bearer {t}"));
    }
    match req.send().await {
        Ok(r) => {
            println!("amp-api status={}", r.status());
            let body = r.text().await.unwrap_or_default();
            println!("body head={:?}", body.chars().take(200).collect::<String>());
        }
        Err(e) => println!("amp-api 请求失败: {e}"),
    }
}

/// 从 browse HTML 里找 `assets/index*.js`（手工扫描，避开 regex 的前瞻需求）。
fn find_index_scripts(html: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let needle = "assets/index";
    let mut rest = html;
    while let Some(pos) = rest.find(needle) {
        rest = &rest[pos..];
        // 往回找 URL 起点（"/" 或 "https://music.apple.com"）
        let tail: String = rest
            .chars()
            .take_while(|c| !c.is_whitespace() && *c != '"' && *c != '\'' && *c != '<' && *c != '>')
            .collect();
        if tail.ends_with(".js") {
            let mut url = tail.clone();
            if !url.starts_with("http") {
                url = format!("https://music.apple.com/{}", url.trim_start_matches('/'));
            }
            if !url.contains("index-legacy") && !out.contains(&url) {
                out.push(url);
            }
        }
        rest = &rest[needle.len()..];
    }
    out
}

/// 在 JS 里找像 Apple 开发者 token 的 JWT，按 kid/iss 打分挑最像的。
fn find_bearer_jwt(js: &str) -> Option<String> {
    use regex_lite::Regex;
    let re = Regex::new(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+").unwrap();
    let mut best: Option<(i32, String)> = None;
    for m in re.find_iter(js) {
        let t = m.as_str().to_string();
        let score = jwt_score(&t);
        if score < 0 {
            continue;
        }
        if best.as_ref().map(|(s, _)| score > *s).unwrap_or(true) {
            best = Some((score, t));
        }
    }
    best.map(|(_, t)| t)
}

/// 解 JWT payload 的明文段，看 kid/iss 是否像 WebPlay token；顺便判过期。
fn jwt_score(token: &str) -> i32 {
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() < 2 {
        return -1;
    }
    let payload = base64url_decode(parts[1]);
    let header = base64url_decode(parts[0]);
    if payload.is_none() {
        return -1;
    }
    let payload = payload.unwrap();
    let header = header.unwrap_or_default();

    // 过期了就不算数
    if let Some(exp_pos) = payload.find("\"exp\"") {
        let tail = &payload[exp_pos..];
        let num: String = tail
            .chars()
            .skip_while(|c| !c.is_ascii_digit())
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if let Ok(exp) = num.parse::<u64>() {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            if now + 60 >= exp {
                return -1;
            }
        }
    }

    let mut score = 0;
    if header.contains("\"kid\":\"WebPlayKid\"") {
        score += 100;
    }
    if payload.contains("\"iss\":\"AMPWebPlay\"") {
        score += 100;
    }
    if payload.contains("root_https_origin") {
        score += 10;
    }
    score
}

fn base64url_decode(s: &str) -> Option<String> {
    let mut t: String = s.replace('-', "+").replace('_', "/");
    while t.len() % 4 != 0 {
        t.push('=');
    }
    // 极简 base64 解码（够解 JWT 的 JSON 段）
    let table: Vec<u8> = {
        let mut v = Vec::new();
        for c in b'A'..=b'Z' {
            v.push(c);
        }
        for c in b'a'..=b'z' {
            v.push(c);
        }
        for c in b'0'..=b'9' {
            v.push(c);
        }
        v.push(b'+');
        v.push(b'/');
        v
    };
    let mut bits: u32 = 0;
    let mut nbits: u32 = 0;
    let mut out: Vec<u8> = Vec::new();
    for ch in t.bytes() {
        if ch == b'=' {
            break;
        }
        let idx = table.iter().position(|&x| x == ch)? as u32;
        bits = (bits << 6) | idx;
        nbits += 6;
        if nbits >= 8 {
            nbits -= 8;
            out.push(((bits >> nbits) & 0xFF) as u8);
        }
    }
    t.clear();
    String::from_utf8(out).ok()
}
