use super::Query;
use crate::error::Result;
/// 提交歌曲播放状态
/// 对应 Node.js module/relay_play_state_submit.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// 提交歌曲播放状态（新版客户端的听歌上报）
    /// 对应 /relay/play/state/submit
    pub async fn relay_play_state_submit(&self, query: &Query) -> Result<ApiResponse> {
        // 12 位大写字母 + 数字
        let session_id = query.get("sessionid").map(|s| s.to_string()).unwrap_or_else(
            || (0..12)
                .map(|_| {
                    b"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                        [rand::random::<usize>() % 36] as char
                })
                .collect(),
        );
        let req = json!({
            "resource": {
                "id": query.get_or("id", "0"),
                "type": query.get_or("type", "song"),
            },
            "progress": query.get_or("progress", "0").parse::<i64>().unwrap_or(0),
            "sessionId": session_id,
            "playMode": query.get_or("playmode", "list_loop"),
        });
        let data = json!({
            "playStateSubmitReq": serde_json::to_string(&req).unwrap_or_default(),
        });
        self.request(
            "/api/relay/play/state/submit",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
