//! AMLL TTML DB（取词链的第 1 级实际起点，也是第 2 级）。
//!
//! 用**网易歌曲 id** 直取：`ncm-lyrics/<ncm_id>.ttml`。这一份文件就是 Apple 官方
//! TTML 的同源产物，且 `<amll:meta>` 里同时带着 `appleMusicId` —— 所以**一次请求**
//! 就同时拿到了歌词和「去 Apple 官方取词」所需的 id（实测 2026-10-07）。
//!
//! 不需要下 `ncm-lyrics/index.jsonl`（5.6MB）：单文件自带全部平台 id。
//!
//! ⚠️ 约 70% 的条目带 `appleMusicId`（实测 ncm 索引 2512/3585）。没有就是那首歌没有
//! Apple 对应版本，属数据实况 —— 此时跳过 Apple 那级、直接用这份 AMLL TTML。

use crate::engine;

/// 取词 base，源站优先、镜像兜底。
///
/// `{id}` 会替换成网易歌曲 id。注意 stevexmh 那家**没有 `.ttml` 后缀**。
///
/// 五家实测（2026-10-07）都返回同一份内容；留着多家是为了国内网络下任意一家不通时
/// 还能拿到词 —— `raw.githubusercontent.com` 在国内尤其不稳。
const BASES: [&str; 5] = [
    "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/ncm-lyrics/{id}.ttml",
    "https://amll-ttml-db.stevexmh.net/ncm/{id}",
    "https://amlldb.bikonoo.com/ncm-lyrics/{id}.ttml",
    "https://amll.mirror.dimeta.top/api/db/ncm-lyrics/{id}.ttml",
    "https://amll-ttml-db.gbclstudio.cn/ncm-lyrics/{id}.ttml",
];

/// 一次命中。
pub struct AmllHit {
    /// 原始 TTML 文本。
    pub ttml: String,
    /// 实际取到的那家 base（排障用）。
    pub base: String,
}

/// 按网易歌曲 id 取 AMLL TTML，逐家轮换。
///
/// 返回值分三种：
/// - `Ok(Some(_))` 取到了；
/// - `Ok(None)` 每家都明确回「没有这首」（404 / 内容不是 TTML）—— 这**不是错误**；
/// - `Err(_)` 每家都是传输失败（断网、全部超时）—— 这才是错误，值得让上层知道。
pub async fn fetch(id: &str) -> Result<Option<AmllHit>, String> {
    let mut transport_errors: Vec<String> = Vec::new();
    let mut answered = false;

    for base in BASES {
        let url = base.replace("{id}", id);
        match engine::HTTP.get(&url).send().await {
            Ok(resp) => {
                let status = resp.status();
                let body = resp.text().await.unwrap_or_default();
                answered = true;

                if status.is_success() && looks_like_ttml(&body) {
                    return Ok(Some(AmllHit { ttml: body, base: url }));
                }
                // 404 或返回了错误页：这家没有，换下一家
                transport_errors.push(format!("{url} -> HTTP {status} len={}", body.len()));
            }
            Err(e) => transport_errors.push(format!("{url} -> {e}")),
        }
    }

    if answered {
        Ok(None)
    } else {
        Err(format!("AMLL DB 全部镜像不可达: {}", transport_errors.join("; ")))
    }
}

/// 判断拿到的是不是 TTML（而不是限流错误页）。
///
/// 🔴 镜像被限流时返回的是 **HTTP 200 + HTML 错误页**，状态码根本不是 429 —— 只看
/// `status` 会把错误页当成歌词喂给 XML 解析器。所以必须看内容：
/// 真 TTML 必有 `<tt` 且不含 `<html`。
pub fn looks_like_ttml(body: &str) -> bool {
    body.contains("<tt") && !body.contains("<html")
}

/// 从 TTML 头部的 `<amll:meta key="appleMusicId" value="..."/>` 里抠出全部 Apple id。
///
/// 只做字符串扫描，不上 XML 解析器 —— 这些标签是自闭合的简单形状，扫足够用，
/// 也省掉一个 XML 依赖。（真正的 TTML 结构解析在 Kotlin 侧。）
pub fn apple_music_ids(ttml: &str) -> Vec<String> {
    let mut out = Vec::new();
    for (key, value) in metas(ttml) {
        if key == "appleMusicId" && !value.is_empty() && !out.contains(&value) {
            out.push(value);
        }
    }
    out
}

/// 抽出全部 `<amll:meta key=... value=.../>`。
fn metas(ttml: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut rest = ttml;
    while let Some(pos) = rest.find("<amll:meta") {
        rest = &rest[pos..];
        let end = match rest.find("/>") {
            Some(e) => e,
            None => break,
        };
        let tag = &rest[..end];
        if let (Some(k), Some(v)) = (attr(tag, "key"), attr(tag, "value")) {
            out.push((k, v));
        }
        rest = &rest[end..];
    }
    out
}

/// 取标签里某个属性的值（只认双引号写法，与 AMLL 产物一致）。
fn attr(tag: &str, name: &str) -> Option<String> {
    let pat = format!("{name}=\"");
    let start = tag.find(&pat)? + pat.len();
    let end = tag[start..].find('"')? + start;
    Some(tag[start..end].to_string())
}
