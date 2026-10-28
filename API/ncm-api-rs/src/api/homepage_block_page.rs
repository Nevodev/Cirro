use super::Query;
use crate::error::Result;
/// 首页 Block Page
/// 对应 Node.js module/homepage_block_page.js
use crate::request::{ApiClient, ApiResponse, CryptoType};
use crate::request::RequestOption;
use serde_json::{json, Value};

impl ApiClient {
    pub async fn homepage_feed(&self, data: Value) -> Result<ApiResponse> {
        let mut cookies = crate::util::cookie::cookie_to_json(self.raw_cookie().unwrap_or_default());
        cookies.insert("os".into(), "android".into());
        cookies.insert("appver".into(), crate::util::config::OS_ANDROID.appver.into());
        self.request(
            "/api/link/page/rcmd/resource/show",
            data,
            RequestOption {
                crypto: CryptoType::Eapi,
                cookie: Some(crate::util::cookie::cookie_obj_to_string(&cookies)),
                ..Default::default()
            },
        ).await
    }

    pub async fn homepage_block_config(&self) -> Result<ApiResponse> {
        self.request(
            "/api/homepage/block/page/config/get",
            json!({}),
            RequestOption { crypto: CryptoType::Eapi, ..Default::default() },
        ).await
    }

    /// 首页内容
    /// 对应 /homepage/block/page
    pub async fn homepage_block_page(&self, query: &Query) -> Result<ApiResponse> {
        let data = json!({
            "refresh": query.get_or("refresh", "false") == "true",
            "cursor": query.get_or("cursor", "")
        });
        self.request(
            "/api/homepage/block/page",
            data,
            query.to_option(CryptoType::Weapi),
        )
        .await
    }
}
