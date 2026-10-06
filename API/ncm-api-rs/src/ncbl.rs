//! NCBL 加密日志 —— 听歌打卡（桌面客户端 PLV/PLD 上报格式）
//!
//! 移植自 api-enhanced 的 `util/ncbl.js`
//! （其来源: github.com/folltoshe/netease-report-listen-song）
//!
//! 背景：老的 `/api/feedback/weblog` 虽返回 success 但不再计入听歌排行，
//! 真正生效的是把 PLV（播放开始）+ PLD（播放结束）两条记录打进
//! `clientlog3.music.163.com/api/clientlog/encrypt/upload`。

use num_bigint::BigUint;
use rand::RngCore;
use std::collections::HashMap;
use std::io::Read;

// ---- ChaCha20（纯实现，与标准 RFC 8439 一致，mode=原始流）----

const SIGMA: [u32; 4] = [0x6170_7865, 0x3320_646e, 0x7962_2d32, 0x6b20_6574];

fn rotl(x: u32, n: u32) -> u32 {
    x.rotate_left(n)
}

fn quarter_round(s: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    s[a] = s[a].wrapping_add(s[b]);
    s[d] ^= s[a];
    s[d] = rotl(s[d], 16);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] ^= s[c];
    s[b] = rotl(s[b], 12);
    s[a] = s[a].wrapping_add(s[b]);
    s[d] ^= s[a];
    s[d] = rotl(s[d], 8);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] ^= s[c];
    s[b] = rotl(s[b], 7);
}

fn chacha_block(key: &[u8], counter: u32, nonce: &[u8]) -> [u8; 64] {
    let mut state = [0u32; 16];
    state[0..4].copy_from_slice(&SIGMA);
    for i in 0..8 {
        state[4 + i] = u32::from_le_bytes([
            key[i * 4],
            key[i * 4 + 1],
            key[i * 4 + 2],
            key[i * 4 + 3],
        ]);
    }
    state[12] = counter;
    state[13] = u32::from_le_bytes([nonce[0], nonce[1], nonce[2], nonce[3]]);
    state[14] = u32::from_le_bytes([nonce[4], nonce[5], nonce[6], nonce[7]]);
    state[15] = u32::from_le_bytes([nonce[8], nonce[9], nonce[10], nonce[11]]);

    let mut work = state;
    for _ in 0..10 {
        quarter_round(&mut work, 0, 4, 8, 12);
        quarter_round(&mut work, 1, 5, 9, 13);
        quarter_round(&mut work, 2, 6, 10, 14);
        quarter_round(&mut work, 3, 7, 11, 15);
        quarter_round(&mut work, 0, 5, 10, 15);
        quarter_round(&mut work, 1, 6, 11, 12);
        quarter_round(&mut work, 2, 7, 8, 13);
        quarter_round(&mut work, 3, 4, 9, 14);
    }

    let mut out = [0u8; 64];
    for i in 0..16 {
        out[i * 4..i * 4 + 4].copy_from_slice(&work[i].wrapping_add(state[i]).to_le_bytes());
    }
    out
}

fn chacha20(key: &[u8], counter: u32, nonce: &[u8], data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    for (off, chunk) in data.chunks(64).enumerate() {
        let ks = chacha_block(key, counter.wrapping_add(off as u32), nonce);
        for (i, b) in chunk.iter().enumerate() {
            out.push(b ^ ks[i]);
        }
    }
    out
}

// ---- RSA-256 Key Wrap（网易 NCBL 专用，256-bit 模数已被分解）----

const RSA_N_HEX: &str = "fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d";
const RSA_E: u64 = 65537;

fn rsa_wrap(key_a: &[u8]) -> Vec<u8> {
    let n = BigUint::parse_bytes(RSA_N_HEX.as_bytes(), 16).expect("RSA_N");
    let m = BigUint::from_bytes_be(key_a);
    let out = m.modpow(&BigUint::from(RSA_E), &n);
    let bytes = out.to_bytes_be();
    let mut padded = vec![0u8; 32];
    let start = 32 - bytes.len();
    padded[start..].copy_from_slice(&bytes);
    padded
}

// ---- NCBL 容器格式 ----

const META_BLOCK_TYPE: u16 = 0x4343;
const HEADER_FIXED_LEN: usize = 70;
const DEFAULT_MAX_FRAME: usize = 0x8000;

/// 加密整包：meta（明文 JSON）+ body（记录文本）→ NCBL 二进制
pub fn encrypt_ncbl(meta: &[u8], body: &[u8]) -> Vec<u8> {
    let mut key_a = vec![0u8; 32];
    rand::thread_rng().fill_bytes(&mut key_a);
    if key_a[0] >= 0xa3 {
        key_a[0] = 0xa2;
    }

    let key_b = rsa_wrap(&key_a);

    let mut uuid = vec![0u8; 16];
    rand::thread_rng().fill_bytes(&mut uuid);
    uuid[6] = (uuid[6] & 0x0f) | 0x40;
    uuid[8] = (uuid[8] & 0x3f) | 0x80;

    let nonce = &uuid[0..12];
    let counter = u32::from_le_bytes([uuid[12], uuid[13], uuid[14], uuid[15]]) >> 2;
    let base_seq: u32 = rand::random::<u16>() as u32;

    // meta 块用 keyB 加密
    let meta_cipher = chacha20(&key_b, counter, nonce, meta);
    let mut meta_block = Vec::with_capacity(4 + meta_cipher.len());
    meta_block.extend_from_slice(&META_BLOCK_TYPE.to_le_bytes());
    meta_block.extend_from_slice(&(meta_cipher.len() as u16).to_le_bytes());
    meta_block.extend_from_slice(&meta_cipher);

    let header_len = (HEADER_FIXED_LEN + meta_block.len()) as u16;

    // body：gzip 压缩后分帧，每帧用 keyA 加密（每帧都从同一 counter 起）
    let mut gzip = flate2::write::GzEncoder::new(
        Vec::new(),
        flate2::Compression::default(),
    );
    std::io::Write::write_all(&mut gzip, body).expect("gzip");
    let compressed = gzip.finish().expect("gzip");

    let mut trailing = Vec::new();
    let mut seq = base_seq;
    for off in (0..compressed.len()).step_by(DEFAULT_MAX_FRAME) {
        let end = (off + DEFAULT_MAX_FRAME).min(compressed.len());
        let slice = &compressed[off..end];
        let cipher = chacha20(&key_a, counter, nonce, slice);
        trailing.extend_from_slice(&(cipher.len() as u16).to_le_bytes());
        trailing.extend_from_slice(&seq.to_le_bytes());
        trailing.extend_from_slice(&cipher);
        seq += 1;
    }
    let frame_count = seq - base_seq;

    let mut header = vec![0u8; HEADER_FIXED_LEN];
    header[0..4].copy_from_slice(b"NCBL");
    header[4..8].copy_from_slice(&3u32.to_le_bytes()); // version
    header[8..10].copy_from_slice(&header_len.to_le_bytes());
    header[10..26].copy_from_slice(&uuid);
    header[26..58].copy_from_slice(&key_b);
    header[58..62].copy_from_slice(&base_seq.to_le_bytes());
    header[62..66].copy_from_slice(&(base_seq + frame_count - 1).to_le_bytes());
    header[66..70].copy_from_slice(&(trailing.len() as u32).to_le_bytes());

    let mut out = Vec::with_capacity(header.len() + meta_block.len() + trailing.len());
    out.extend_from_slice(&header);
    out.extend_from_slice(&meta_block);
    out.extend_from_slice(&trailing);
    out
}

// ---- 日志记录 ----

const FIELD_SEP: char = '\u{1}';

/// 单条记录：`时间\x01动作\x01JSON`
pub fn build_record(time: i64, action: &str, data_json: &str) -> String {
    format!("{time}{FIELD_SEP}{action}{FIELD_SEP}{data_json}")
}

// ---- Cookie 上下文 ----

/// 从 cookie 串解析出的设备/认证上下文（键全部保留原始字符串，缺失给默认值）
pub struct NcblContext {
    pub map: HashMap<String, String>,
    pub version: String,
    pub version_code: String,
    pub channel: String,
    pub osver: String,
    pub vip_type: String,
}

pub fn extract_context(cookie: &str) -> NcblContext {
    let mut map: HashMap<String, String> = HashMap::new();
    for part in cookie.split(';') {
        let part = part.trim();
        if let Some(idx) = part.find('=') {
            let (k, v) = part.split_at(idx);
            map.entry(k.trim().to_string())
                .or_insert_with(|| v[1..].trim().to_string());
        }
    }
    let get = |k: &str| map.get(k).cloned();
    NcblContext {
        version: get("appver").unwrap_or_else(|| "3.1.35".into()),
        version_code: get("versioncode").unwrap_or_else(|| "205293".into()),
        channel: get("channel").unwrap_or_else(|| "netease".into()),
        osver: get("osver")
            .unwrap_or_else(|| "Microsoft-Windows-10-Professional-build-19045-64bit".into()),
        vip_type: get("vipType").unwrap_or_default(),
        map,
    }
}

impl NcblContext {
    fn val(&self, k: &str) -> String {
        self.map.get(k).cloned().unwrap_or_default()
    }

    fn val_or(&self, k: &str, default: &str) -> String {
        let v = self.val(k);
        if v.is_empty() {
            default.to_string()
        } else {
            v
        }
    }

    /// 取第一个非空的键值
    fn val_first(&self, keys: &[&str]) -> String {
        keys.iter()
            .map(|k| self.val(k))
            .find(|v| !v.is_empty())
            .unwrap_or_default()
    }

    /// 上传时用的 Cookie 头
    pub fn cookie_header(&self) -> String {
        let m = |k: &str| self.val(k);
        format!(
            "JSESSIONID-WYYY={}; MUSIC_U={}; NMTID={}; WEVNSM={}; WNMCID={}; __csrf={}; \
             __remember_me=true; _iuqxldmzr_=33; _ntes_nnid={}; _ntes_nuid={}; \
             appver={}.{}; channel={}; clientSign={}; deviceId={}; mode={}; \
             ntes_kaola_ad=1; os=pc; osver={}",
            m("JSESSIONID-WYYY"),
            m("MUSIC_U"),
            m("NMTID"),
            self.val_or("WEVNSM", "1.0.0"),
            m("WNMCID"),
            m("__csrf"),
            self.val_or("_ntes_nnid", ","),
            m("_ntes_nuid"),
            self.version,
            self.version_code,
            self.channel,
            m("clientSign"),
            self.val_first(&["deviceId", "sDeviceId"]),
            self.val_first(&["mode", "mobilename"]),
            self.osver,
        )
    }

    /// NCBL meta 块（cookie 的 JSON 形态）
    pub fn meta_json(&self) -> String {
        let m = |k: &str| self.val(k);
        let obj = serde_json::json!({
            "JSESSIONID-WYYY": m("JSESSIONID-WYYY"),
            "MUSIC_U": m("MUSIC_U"),
            "NMTID": m("NMTID"),
            "WEVNSM": self.val_or("WEVNSM", "1.0.0"),
            "WNMCID": m("WNMCID"),
            "__csrf": m("__csrf"),
            "_iuqxldmzr_": "33",
            "_ntes_nnid": self.val_or("_ntes_nnid", ","),
            "_ntes_nuid": m("_ntes_nuid"),
            "appver": format!("{}.{}", self.version, self.version_code),
            "channel": self.channel,
            "clientSign": m("clientSign"),
            "deviceId": self.val_first(&["deviceId", "sDeviceId"]),
            "mode": self.val_first(&["mode", "mobilename"]),
            "ntes_kaola_ad": "1",
            "os": "pc",
            "osver": self.osver,
        });
        obj.to_string()
    }
}

// ---- PLV / PLD 载荷（桌面客户端格式）----

pub struct NcblSong {
    pub id: i64,
    pub name: String,
    pub artist: String,
    pub bitrate: i64,
    pub level: String,
    pub total_time: i64,
}

pub struct NcblSource {
    pub id: String,
    pub kind: String,
    pub name: String,
}

pub fn build_plv(ctx: &NcblContext, song: &NcblSong, source: &NcblSource) -> String {
    let now = chrono::Utc::now().timestamp_millis();
    let add_refer = format!(
        "[F:63][{now}#933#{}#{}#c9156c3][e][2][23][cell_pc_songlist_song:2|page_pc_songlist_songflow|page_mine_like_music][{id}:song:x:x|:::|{sid}:list::]",
        ctx.version, ctx.version_code, id = song.id, sid = source.id
    );
    let multi_refers = serde_json::json!([
        "[F:26][s][18][_ai]",
        "[F:26][s][12][_ai]",
        format!("[F:63][{now}#933#{}#{}#c9156c3][e][2][8][cell_pc_main_tab_entrance:6|page_pc_main_tab][我喜欢的音乐:spm::|:::]", ctx.version, ctx.version_code),
        "[F:26][s][5][_ai]",
        "[F:26][s][0][_ai]",
    ]);
    serde_json::json!({
        "mode": "circulation",
        "download": 0,
        "alg": "",
        "status": "front",
        "id": song.id.to_string(),
        "bitrate": song.bitrate,
        "type": "song",
        "is_listentogether": 0,
        "source": source.name,
        "is_heart": 0,
        "resource_ratio": "",
        "resource_time": song.total_time,
        "musiceffect_id": "",
        "app_mode": 2,
        "bitrate_level": song.level,
        "_addrefer": add_refer,
        "_multirefers": multi_refers,
        "vipType": ctx.vip_type,
        "fee": 1,
        "file": 4,
        "rightSource": 0,
        "sourceId": source.id,
        "sourcetype": source.kind,
        "libra_abt": "",
        "channel": ctx.channel,
        "curStartChannel": "",
    })
    .to_string()
}

pub fn build_pld(
    ctx: &NcblContext,
    song: &NcblSong,
    source: &NcblSource,
    played: i64,
) -> String {
    let now = chrono::Utc::now().timestamp_millis();
    let add_refer = format!(
        "[F:63][{now}#616#{}#{}#c9156c3][e][2][92][btn_pc_cover_play|cell_pc_songlist_song:6|page_pc_songlist_songflow|page_mine_like_music][:::|{id}:song:x:x|:::|{sid}:list::]",
        ctx.version, ctx.version_code, id = song.id, sid = source.id
    );
    let multi_refers = serde_json::json!([
        "[F:26][s][87][_ai]",
        "[F:26][s][81][_ai]",
        "[F:26][s][75][_ai]",
        "[F:26][s][69][_ai]",
        "[F:26][s][63][_ai]",
    ]);
    serde_json::json!({
        "mode": "circulation",
        "download": 0,
        "alg": "",
        "status": "front",
        "id": song.id.to_string(),
        "time": played,
        "type": "song",
        "is_listentogether": 0,
        "source": source.name,
        "is_heart": 0,
        "realtime": played,
        "resource_ratio": "",
        "resource_time": song.total_time,
        "musiceffect_id": "1001",
        "app_mode": 1,
        "lyriceffect": "default",
        "displayMode": "classic",
        "bitrate": song.bitrate,
        "bitrate_level": song.level,
        "_addrefer": add_refer,
        "_multirefers": multi_refers,
        "vipType": ctx.vip_type,
        "fee": 8,
        "file": 4,
        "rightSource": 0,
        "sourceId": source.id,
        "sourcetype": source.kind,
        "end": "interrupt",
        "libra_abt": "",
        "channel": ctx.channel,
        "curStartChannel": "",
    })
    .to_string()
}

// ---- 上传 ----

fn random_uuid_hex() -> String {
    let mut b = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// 构造 multipart body（字段名固定 file，随机文件名）
fn build_multipart(payload: &[u8]) -> (String, String, Vec<u8>) {
    let boundary = random_uuid_hex();
    let file_name = format!(
        "op_{}_0_{}",
        rand::random::<u32>() % 90000 + 10000,
        rand::random::<u32>(),
    );
    let head = format!(
        "--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{f}\"\r\n\
         Content-Type: multipart/form-data\r\n\r\n",
        b = boundary,
        f = file_name
    );
    let mut body = head.into_bytes();
    body.extend_from_slice(payload);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    (boundary, file_name, body)
}

/// 上传一条 NCBL 日志（PLV 或 PLD 各传一次）
pub async fn do_upload(
    http: &reqwest::Client,
    ctx: &NcblContext,
    record_json: &str,
    action: &str,
) -> std::result::Result<serde_json::Value, crate::error::NcmError> {
    use crate::error::NcmError;
    let url = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload?multiupload=true";
    let ts = chrono::Utc::now().timestamp();
    let body = build_record(ts, action, record_json);

    let payload = encrypt_ncbl(ctx.meta_json().as_bytes(), body.as_bytes());
    let (boundary, file_name, multipart) = build_multipart(&payload);

    let resp = http
        .post(url)
        .header(
            "Content-Type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .header("Referer", "https://music.163.com/di")
        .header(
            "User-Agent",
            format!(
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/{}",
                ctx.version
            ),
        )
        .header("Accept-Encoding", "gzip,deflate")
        .header("Accept-Language", "zh-CN,zh;q=0.8")
        .header("Cookie", ctx.cookie_header())
        .body(multipart)
        .timeout(std::time::Duration::from_secs(15))
        .send()
        .await
        .map_err(NcmError::Http)?;

    let status = resp.status();
    let raw = resp.bytes().await.map_err(NcmError::Http)?;
    // 服务端可能 gzip 压缩响应（reqwest 未开 gzip feature，手动解）
    let text = if raw.starts_with(&[0x1f, 0x8b]) {
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(&raw[..])
            .read_to_end(&mut out)
            .map_err(|e| NcmError::Unknown(format!("NCBL 响应解压失败: {e}")))?;
        String::from_utf8_lossy(&out).into_owned()
    } else {
        String::from_utf8_lossy(&raw).into_owned()
    };
    let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| {
        NcmError::Unknown(format!(
            "NCBL 响应解析失败: {e} / status={status} / body前200字节={:?}",
            &raw[..raw.len().min(200)]
        ))
    })?;

    // 成功判定：code==200 且 data.successfiles 包含我们的文件名
    let ok = v.get("code").and_then(|c| c.as_i64()) == Some(200)
        && v.pointer("/data/successfiles")
            .and_then(|s| s.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_str())
                    .any(|s| s == file_name)
            })
            .unwrap_or(false);
    Ok(serde_json::json!({"success": ok, "fileName": file_name, "resp": v}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chacha20_reference() {
        // RFC 8439 §2.3.2 测试向量
        let key = [0u8; 32];
        let nonce = [0u8; 12];
        let out = chacha_block(&key, 0, &nonce);
        let expect_head: [u8; 16] = [
            0x76, 0xb8, 0xe0, 0xad, 0xa0, 0xf1, 0x3d, 0x90, 0x40, 0x5d, 0x6a, 0xe5,
            0x53, 0x86, 0xbd, 0x28,
        ];
        assert_eq!(out[0..16], expect_head);
    }

    #[test]
    fn ncbl_roundtrip_shape() {
        let payload = encrypt_ncbl(b"{}", b"123\x01act\x01{}");
        assert_eq!(&payload[0..4], b"NCBL");
        assert_eq!(u32::from_le_bytes(payload[4..8].try_into().unwrap()), 3);
    }
}
