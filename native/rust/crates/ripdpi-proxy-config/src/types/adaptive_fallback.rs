use serde::{Deserialize, Serialize};

use super::constants::{ADAPTIVE_FALLBACK_DEFAULT_CACHE_PREFIX_V4, ADAPTIVE_FALLBACK_DEFAULT_CACHE_TTL_SECS};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiAdaptiveFallbackConfig {
    #[serde(default = "super::common::default_true")]
    pub enabled: bool,
    #[serde(default = "super::common::default_true")]
    pub torst: bool,
    #[serde(default = "super::common::default_true")]
    pub tls_err: bool,
    #[serde(default = "super::common::default_true")]
    pub http_redirect: bool,
    #[serde(default = "super::common::default_true")]
    pub connect_failure: bool,
    #[serde(default = "super::common::default_true")]
    pub auto_sort: bool,
    #[serde(default = "default_adaptive_fallback_cache_ttl_secs")]
    pub cache_ttl_seconds: i64,
    #[serde(default = "default_adaptive_fallback_cache_prefix_v4")]
    pub cache_prefix_v4: u8,
}

impl Default for ProxyUiAdaptiveFallbackConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            torst: true,
            tls_err: true,
            http_redirect: true,
            connect_failure: true,
            auto_sort: true,
            cache_ttl_seconds: default_adaptive_fallback_cache_ttl_secs(),
            cache_prefix_v4: default_adaptive_fallback_cache_prefix_v4(),
        }
    }
}

fn default_adaptive_fallback_cache_ttl_secs() -> i64 {
    ADAPTIVE_FALLBACK_DEFAULT_CACHE_TTL_SECS
}

fn default_adaptive_fallback_cache_prefix_v4() -> u8 {
    ADAPTIVE_FALLBACK_DEFAULT_CACHE_PREFIX_V4
}
