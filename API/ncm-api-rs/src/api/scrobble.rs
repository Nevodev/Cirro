use super::Query;
use crate::error::{NcmError, Result};
use crate::request::{ApiClient, ApiResponse, CryptoType, RequestOption};
use serde_json::{json, Value};

fn event_logs(event: Value) -> Result<Value> {
    if !matches!(event["action"].as_str(), Some("startplay" | "play")) {
        return Err(NcmError::InvalidParam("不支持的播放事件".into()));
    }
    let fields = event["json"]
        .as_object()
        .ok_or_else(|| NcmError::InvalidParam("播放事件缺少 json 对象".into()))?;
    let id = fields.get("id").and_then(|value| {
        value
            .as_i64()
            .or_else(|| value.as_str().and_then(|id| id.parse::<i64>().ok()))
    });
    if !id.is_some_and(|id| id > 0) || fields.get("type").and_then(Value::as_str) != Some("song") {
        return Err(NcmError::InvalidParam("播放事件歌曲身份无效".into()));
    }
    for key in ["time", "realtime", "resource_time", "progressTime", "startlogtime"] {
        if let Some(value) = fields.get(key) {
            if !value.as_f64().is_some_and(|number| number.is_finite() && number >= 0.0) {
                return Err(NcmError::InvalidParam(format!("播放事件 {key} 无效")));
            }
        }
    }
    if let Some(speed) = fields.get("play_speed") {
        if !speed.as_f64().is_some_and(|speed| speed.is_finite() && speed > 0.0) {
            return Err(NcmError::InvalidParam("播放事件 play_speed 无效".into()));
        }
    }
    if let Some(error_code) = fields.get("errorcode") {
        if error_code.as_i64().is_none() {
            return Err(NcmError::InvalidParam("播放事件 errorcode 无效".into()));
        }
    }
    Ok(json!({ "logs": serde_json::to_string(&[event])? }))
}

fn require_weblog_success(response: &ApiResponse) -> Result<()> {
    let code = response.body.get("code").and_then(|value| {
        value
            .as_i64()
            .or_else(|| value.as_str().and_then(|code| code.parse::<i64>().ok()))
    });
    if response.status != 200 || code != Some(200) {
        // 通用请求层会放行登录轮询等接口的特殊码；播放上报必须检查原始业务码。
        return Err(NcmError::Api {
            code: code.unwrap_or(response.status),
            msg: "播放事件未被接收".into(),
        });
    }
    Ok(())
}

impl ApiClient {
    /// 上传一个已结算的事件，保持调用方提供的真实累计量和结束原因。
    pub async fn scrobble_event(&self, event: Value) -> Result<ApiResponse> {
        self.post_weblog(event_logs(event)?).await
    }

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

    /// 旧查询参数接口；完整会话字段使用 scrobble_event。
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
        require_weblog_success(&r)?;
        // 统一外壳：真实结果挪进 details，外壳 code 恒 200
        r.body = json!({ "code": 200, "details": r.body });
        Ok(r)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_end_event_keeps_actual_time_and_reason_without_a_second_start() {
        let event = json!({
            "action": "play",
            "json": {
                "id": 42, "type": "song", "time": 17, "realtime": 12,
                "resource_time": 240, "end": "ui", "sourceId": "84",
            }
        });
        let logs = event_logs(event.clone()).unwrap();
        let decoded: Vec<Value> = serde_json::from_str(logs["logs"].as_str().unwrap()).unwrap();
        assert_eq!(decoded, vec![event]);
    }

    #[test]
    fn invalid_event_identity_or_action_is_rejected_before_network() {
        for event in [
            json!({"action":"heartbeat","json":{"id":42,"type":"song"}}),
            json!({"action":"play","json":{"id":0,"type":"song"}}),
            json!({"action":"play","json":{"id":"invalid","type":"song"}}),
            json!({"action":"play","json":{"id":42,"type":"program"}}),
            json!({"action":"play","json":[]}),
        ] {
            assert!(event_logs(event).is_err());
        }
        assert!(event_logs(json!({"action":"startplay","json":{"id":"42","type":"song"}})).is_ok());
    }

    #[test]
    fn normalized_status_cannot_hide_an_upstream_rejection_or_missing_code() {
        for body in [json!({"code":400}), json!({"code":502}), json!({"code":403}), json!({})] {
            let response = ApiResponse { status: 200, body, cookie: vec![] };
            assert!(require_weblog_success(&response).is_err());
        }
        for body in [json!({"code":200}), json!({"code":"200"})] {
            let response = ApiResponse { status: 200, body, cookie: vec![] };
            assert!(require_weblog_success(&response).is_ok());
        }
        let response = ApiResponse { status: 403, body: json!({"code":200}), cookie: vec![] };
        assert!(require_weblog_success(&response).is_err());
    }

    #[test]
    fn malformed_present_numbers_are_rejected_without_requiring_optional_fields() {
        let base = json!({"action":"play","json":{"id":42,"type":"song"}});
        for key in ["time", "realtime", "resource_time", "progressTime", "startlogtime"] {
            for value in [json!(-1), json!("17"), Value::Null, json!([]), json!(true)] {
                let mut event = base.clone();
                event["json"][key] = value;
                assert!(event_logs(event).is_err(), "accepted malformed {key}");
            }
            for value in [json!(0), json!(17.5)] {
                let mut event = base.clone();
                event["json"][key] = value;
                assert!(event_logs(event).is_ok());
            }
        }
        for value in [json!(0), json!(-1), json!("1"), Value::Null] {
            let mut event = base.clone();
            event["json"]["play_speed"] = value;
            assert!(event_logs(event).is_err());
        }
        let mut event = base.clone();
        event["json"]["play_speed"] = json!(1.5);
        event["json"]["errorcode"] = json!(-1);
        assert!(event_logs(event.clone()).is_ok());
        event["json"]["errorcode"] = json!(1.5);
        assert!(event_logs(event).is_err());
        assert!(event_logs(base).is_ok());
    }
}
