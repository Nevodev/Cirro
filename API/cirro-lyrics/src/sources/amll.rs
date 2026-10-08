//! AMLL TTML DB（官方取词的补充索引与第 2 优先源）。
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
use tokio::task::JoinSet;

/// 同时请求的源站与镜像。
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

/// 按网易歌曲 id 并发取 AMLL TTML。
///
/// 返回值分三种：
/// - `Ok(Some(_))` 取到了；
/// - `Ok(None)` 任意一家明确回「没有这首」（404），取消剩余镜像；
/// - `Err(_)` 全部请求失败，没有有效的存在 / 不存在响应。
pub async fn fetch(id: &str) -> Result<Option<AmllHit>, String> {
    let mut requests = JoinSet::new();
    for base in BASES {
        let url = base.replace("{id}", id);
        requests.spawn(fetch_url(engine::HTTP.clone(), url));
    }
    first_answer(requests).await
}

async fn fetch_url(client: reqwest::Client, url: String) -> Result<Option<AmllHit>, String> {
    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("{url} -> {e}"))?;
    let status = resp.status();
    if status == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    if !status.is_success() {
        return Err(format!("{url} -> HTTP {status}"));
    }
    let body = resp.text().await.map_err(|e| format!("{url} -> {e}"))?;
    if looks_like_ttml(&body) {
        Ok(Some(AmllHit {
            ttml: body,
            base: url,
        }))
    } else {
        // HTTP 200 + 限流 HTML 不是「库里没有这首」，不能据此取消其他镜像。
        Err(format!("{url} -> 非 TTML 响应 len={}", body.len()))
    }
}

async fn first_answer(
    mut requests: JoinSet<Result<Option<AmllHit>, String>>,
) -> Result<Option<AmllHit>, String> {
    let mut errors = Vec::new();
    while let Some(result) = requests.join_next().await {
        match result {
            Ok(Ok(answer)) => {
                requests.abort_all();
                return Ok(answer);
            }
            Ok(Err(error)) => errors.push(error),
            Err(error) => errors.push(error.to_string()),
        }
    }
    Err(format!("AMLL DB 全部镜像请求失败: {}", errors.join("; ")))
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::future::pending;
    use std::sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    };
    use std::time::Duration;

    struct Dropped(Arc<AtomicBool>);

    impl Drop for Dropped {
        fn drop(&mut self) {
            self.0.store(true, Ordering::SeqCst);
        }
    }

    fn runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
    }

    #[test]
    fn missing_answer_cancels_pending_mirrors() {
        runtime().block_on(async {
            let dropped = Arc::new(AtomicBool::new(false));
            let guard = Dropped(dropped.clone());
            let mut requests = JoinSet::new();
            requests.spawn(async move {
                let _guard = guard;
                pending::<Result<Option<AmllHit>, String>>().await
            });
            requests.spawn(async { Ok(None) });
            let result = tokio::time::timeout(Duration::from_secs(1), first_answer(requests))
                .await
                .unwrap();
            assert!(matches!(result, Ok(None)));
            tokio::task::yield_now().await;
            assert!(dropped.load(Ordering::SeqCst));
        });
    }

    #[test]
    fn successful_answer_does_not_wait_for_pending_mirrors() {
        runtime().block_on(async {
            let mut requests = JoinSet::new();
            requests.spawn(pending::<Result<Option<AmllHit>, String>>());
            requests.spawn(async {
                Ok(Some(AmllHit {
                    ttml: "<tt/>".into(),
                    base: "fast".into(),
                }))
            });
            let result = tokio::time::timeout(Duration::from_secs(1), first_answer(requests))
                .await
                .unwrap();
            assert_eq!(result.unwrap().unwrap().base, "fast");
        });
    }

    #[test]
    fn transport_failure_keeps_other_mirrors_running() {
        runtime().block_on(async {
            let mut requests = JoinSet::new();
            requests.spawn(async { Err("timeout".into()) });
            requests.spawn(async {
                tokio::task::yield_now().await;
                Ok(Some(AmllHit {
                    ttml: "<tt/>".into(),
                    base: "healthy".into(),
                }))
            });
            assert_eq!(
                first_answer(requests).await.unwrap().unwrap().base,
                "healthy"
            );
        });
    }

    #[test]
    fn all_transport_failures_report_an_error() {
        runtime().block_on(async {
            let mut requests = JoinSet::new();
            requests.spawn(async { Err("timeout".into()) });
            requests.spawn(async { Err("HTTP 500".into()) });
            let error = first_answer(requests).await.err().unwrap();
            assert!(error.contains("timeout"));
            assert!(error.contains("HTTP 500"));
        });
    }
}
