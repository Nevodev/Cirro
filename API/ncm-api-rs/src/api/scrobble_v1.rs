use super::Query;
use crate::error::Result;
/// 听歌打卡 - NCBL 加密版（桌面客户端 PLV/PLD 上报）
/// 对应 Node.js module/scrobble_v1.js（api-enhanced）
use crate::ncbl;
use crate::request::{ApiClient, ApiResponse};
use serde_json::json;

impl ApiClient {
    /// 听歌打卡（NCBL 加密版，真实计入听歌排行）
    /// 对应 /scrobble/v1
    ///
    /// 参数：`id`（歌曲 id，必填）、`time`（播放秒数，必填）、
    ///      `sourceid`（来源列表 id）、`source`（来源名，默认 list）、
    ///      `name`/`artist`（歌名/歌手）、`bitrate`（码率，默认 320）、
    ///      `level`（音质等级，默认 exhigh）、`total`（歌曲总时长秒）
    pub async fn scrobble_v1(&self, query: &Query) -> Result<ApiResponse> {
        let id = query
            .get("id")
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);
        let play_time = query
            .get("time")
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);
        let total_time = query
            .get("total")
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(play_time);
        let bitrate = query
            .get("bitrate")
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(320);
        let level = query.get_or("level", "exhigh").to_string();

        let cookie = self.raw_cookie().unwrap_or_default().to_string();
        let ctx = ncbl::extract_context(&cookie);

        let song = ncbl::NcblSong {
            id,
            name: query.get_or("name", "").to_string(),
            artist: query.get_or("artist", "").to_string(),
            bitrate,
            level,
            total_time,
        };
        let source = ncbl::NcblSource {
            id: query
                .get("sourceid")
                .or_else(|| query.get("sourceId"))
                .map(|s| s.to_string())
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| id.to_string()),
            kind: "track".into(),
            name: query.get_or("source", "list").to_string(),
        };

        let played = play_time.min(song.total_time.max(1));
        let http = reqwest::Client::new();
        let plv = ncbl::do_upload(&http, &ctx, &ncbl::build_plv(&ctx, &song, &source), "_plv")
            .await?;
        let pld = ncbl::do_upload(&http, &ctx, &ncbl::build_pld(&ctx, &song, &source, played), "_pld")
            .await?;

        // 包成 ApiResponse 形态，body 里带上两次上传的结果
        let body = json!({
            "code": if plv["success"] == json!(true) && pld["success"] == json!(true) { 200 } else { -1 },
            "data": {
                "plv": plv,
                "pld": pld,
            }
        });
        Ok(ApiResponse {
            status: 200,
            body,
            cookie: Vec::new(),
        })
    }
}
