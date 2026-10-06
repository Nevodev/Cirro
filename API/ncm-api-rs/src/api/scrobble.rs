use super::Query;
use crate::error::Result;
/// 听歌打卡
///
/// 配方实测（2026-10）：
/// - 老的 `weapi music.163.com /api/feedback/weblog` 会返回 success 但**不计数**
/// - 正确姿势：eapi + `clientlog.music.163.com` + cookie 注入 `os=osx`
/// - 官方客户端是两条日志分开报的：`startplay`（开始播，进「最近播放」）
///   和 `play`（完整听完，涨「听歌排行」计数）——所以要拆开调用
use crate::request::{ApiClient, ApiResponse, CryptoType, RequestOption};
use serde_json::json;

impl ApiClient {
    /// 开始播放的上报（进「最近播放」）。歌一开始播就调，不等结束。
    /// 对应 /scrobble 的 startplay 半边
    pub async fn scrobble_start(&self, query: &Query) -> Result<ApiResponse> {
        let id = query.get_or("id", "0");
        let source_id = query.get_or("sourceid", "");
        let logs = json!({
            "logs": serde_json::to_string(&json!([{
                "action": "startplay",
                "json": {
                    "id": id,
                    "type": "song",
                    "mainsite": "1",
                    "mainsiteWeb": "1",
                    "content": format!("id={source_id}"),
                }
            }]))
            .unwrap_or_default()
        });
        self.post_weblog(logs).await
    }

    /// 播放结束的上报（涨「听歌排行」计数）。自然播完才调，跳过的歌不报。
    /// 对应 /scrobble 的 play 半边
    pub async fn scrobble_end(&self, query: &Query) -> Result<ApiResponse> {
        let id = query.get_or("id", "0");
        let source_id = query.get_or("sourceid", "");
        let time = query.get_or("time", "0");
        let logs = json!({
            "logs": serde_json::to_string(&json!([{
                "action": "play",
                "json": {
                    "download": 0,
                    "end": "playend",
                    "id": id,
                    "sourceId": source_id,
                    "time": time,
                    "type": "song",
                    "wifi": 0,
                    "source": "list",
                    "mainsite": "1",
                    "mainsiteWeb": "1",
                    "content": format!("id={source_id}"),
                }
            }]))
            .unwrap_or_default()
        });
        self.post_weblog(logs).await
    }

    /// 听歌打卡：startplay + play 一次报完（适合"整首听完才知道"的场景）。
    /// 对应 /scrobble
    pub async fn scrobble(&self, query: &Query) -> Result<ApiResponse> {
        let res1 = self.scrobble_start(query).await?;
        let res2 = self.scrobble_end(query).await?;
        let body = json!({
            "code": 200,
            "details": {
                "startplay": res1.body,
                "play": res2.body,
            }
        });
        Ok(ApiResponse {
            status: 200,
            body,
            cookie: Vec::new(),
        })
    }

    /// 打卡的实际通道：eapi + clientlog 域名 + os=osx cookie。
    async fn post_weblog(&self, data: serde_json::Value) -> Result<ApiResponse> {
        // cookie 注入 os=osx（options.cookie 是整体替换，需带全量）
        let base_cookie = self.raw_cookie().unwrap_or_default().to_string();
        let cookie = if base_cookie.contains("os=") {
            let re = regex_lite::Regex::new(r"os=[^;]+").unwrap();
            re.replace(&base_cookie, "os=osx").to_string()
        } else {
            format!("{base_cookie}; os=osx")
        };
        let option = RequestOption {
            crypto: CryptoType::Eapi,
            domain: Some(crate::util::config::CL_DOMAIN.into()),
            cookie: Some(cookie),
            ..Default::default()
        };
        let mut r = self.request("/api/feedback/weblog", data, option).await?;
        // 统一外壳：真实结果挪进 details，外壳 code 恒 200
        r.body = json!({ "code": 200, "details": r.body });
        Ok(r)
    }
}
