/// 插播相似歌曲
///
/// 对应 Node 版 `module/song_simi_get.js`（NeteaseCloudMusicApiEnhanced）：
/// 底层是 `/api/link/position/show/resource`，`positionCode=toolBarRcmdSong`，
/// **走 eapi**（不是 weapi）。参数名为 `resourceId`，不是 `songId`。
use super::Query;
use crate::error::Result;
use crate::request::{ApiClient, ApiResponse, CryptoType};
use serde_json::json;

impl ApiClient {
    /// 插播相似歌曲：传一首歌的 id，拿"接着这一首插下去"的相似歌。
    pub async fn song_simi_get(&self, query: &Query) -> Result<ApiResponse> {
        let data = json!({
            "positionCode": "toolBarRcmdSong",
            "resourceId": query.get_or("id", "0"),
            "resourceType": "song",
        });
        self.request(
            "/api/link/position/show/resource",
            data,
            query.to_option(CryptoType::Eapi),
        )
        .await
    }
}
