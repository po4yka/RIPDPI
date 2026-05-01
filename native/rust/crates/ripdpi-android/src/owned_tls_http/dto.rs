use std::collections::BTreeMap;
use std::io;

use bytes::Bytes;
use http::StatusCode;
use serde::{Deserialize, Serialize};

const DEFAULT_TLS_PROFILE: &str = "chrome_stable";

#[derive(Debug, Deserialize)]
pub(crate) struct NativeOwnedTlsHttpRequest {
    #[serde(default = "default_method")]
    pub(crate) method: String,
    pub(crate) url: String,
    #[serde(default)]
    pub(crate) headers: BTreeMap<String, String>,
    #[serde(rename = "tlsProfileId", default = "default_tls_profile")]
    pub(crate) tls_profile_id: String,
    #[serde(rename = "connectTimeoutMs", default = "default_connect_timeout_ms")]
    pub(crate) connect_timeout_ms: u64,
    #[serde(rename = "readTimeoutMs", default = "default_read_timeout_ms")]
    pub(crate) read_timeout_ms: u64,
    #[serde(rename = "callTimeoutMs", default = "default_call_timeout_ms")]
    pub(crate) call_timeout_ms: u64,
    #[serde(rename = "maxRedirects", default = "default_max_redirects")]
    pub(crate) max_redirects: usize,
}

#[derive(Debug, Serialize)]
pub(crate) struct NativeOwnedTlsHttpResponse {
    #[serde(rename = "statusCode")]
    pub(crate) status_code: Option<u16>,
    #[serde(rename = "bodyBase64")]
    pub(crate) body_base64: Option<String>,
    #[serde(rename = "finalUrl")]
    pub(crate) final_url: Option<String>,
    #[serde(rename = "tlsProfileId")]
    pub(crate) tls_profile_id: Option<String>,
    #[serde(rename = "tlsProfileCatalogVersion")]
    pub(crate) tls_profile_catalog_version: Option<String>,
    #[serde(rename = "tlsJa3ParityTarget")]
    pub(crate) tls_ja3_parity_target: Option<String>,
    #[serde(rename = "tlsJa4ParityTarget")]
    pub(crate) tls_ja4_parity_target: Option<String>,
    #[serde(rename = "tlsBrowserFamily")]
    pub(crate) tls_browser_family: Option<String>,
    #[serde(rename = "tlsBrowserTrack")]
    pub(crate) tls_browser_track: Option<String>,
    #[serde(rename = "tlsTemplateAlpn")]
    pub(crate) tls_template_alpn: Option<String>,
    #[serde(rename = "tlsTemplateExtensionOrderFamily")]
    pub(crate) tls_template_extension_order_family: Option<String>,
    #[serde(rename = "tlsTemplateGreaseStyle")]
    pub(crate) tls_template_grease_style: Option<String>,
    #[serde(rename = "tlsTemplateSupportedGroupsProfile")]
    pub(crate) tls_template_supported_groups_profile: Option<String>,
    #[serde(rename = "tlsTemplateKeyShareProfile")]
    pub(crate) tls_template_key_share_profile: Option<String>,
    #[serde(rename = "tlsTemplateRecordChoreography")]
    pub(crate) tls_template_record_choreography: Option<String>,
    #[serde(rename = "tlsTemplateEchCapable")]
    pub(crate) tls_template_ech_capable: Option<bool>,
    #[serde(rename = "tlsTemplateEchBootstrapPolicy")]
    pub(crate) tls_template_ech_bootstrap_policy: Option<String>,
    #[serde(rename = "tlsTemplateEchBootstrapResolverId")]
    pub(crate) tls_template_ech_bootstrap_resolver_id: Option<String>,
    #[serde(rename = "tlsTemplateEchOuterExtensionPolicy")]
    pub(crate) tls_template_ech_outer_extension_policy: Option<String>,
    #[serde(rename = "clientHelloSizeHint")]
    pub(crate) client_hello_size_hint: Option<usize>,
    #[serde(rename = "clientHelloInvariantStatus")]
    pub(crate) client_hello_invariant_status: Option<String>,
    pub(crate) error: Option<String>,
}

impl NativeOwnedTlsHttpResponse {
    pub(crate) fn error(error: io::Error) -> Self {
        Self {
            status_code: None,
            body_base64: None,
            final_url: None,
            tls_profile_id: None,
            tls_profile_catalog_version: None,
            tls_ja3_parity_target: None,
            tls_ja4_parity_target: None,
            tls_browser_family: None,
            tls_browser_track: None,
            tls_template_alpn: None,
            tls_template_extension_order_family: None,
            tls_template_grease_style: None,
            tls_template_supported_groups_profile: None,
            tls_template_key_share_profile: None,
            tls_template_record_choreography: None,
            tls_template_ech_capable: None,
            tls_template_ech_bootstrap_policy: None,
            tls_template_ech_bootstrap_resolver_id: None,
            tls_template_ech_outer_extension_policy: None,
            client_hello_size_hint: None,
            client_hello_invariant_status: None,
            error: Some(error.to_string()),
        }
    }
}

#[derive(Debug)]
pub(crate) struct RawHttpResponse {
    pub(crate) status_code: StatusCode,
    pub(crate) headers: http::HeaderMap,
    pub(crate) body: Bytes,
}

fn default_method() -> String {
    "GET".to_string()
}

fn default_tls_profile() -> String {
    DEFAULT_TLS_PROFILE.to_string()
}

const fn default_connect_timeout_ms() -> u64 {
    20_000
}

const fn default_read_timeout_ms() -> u64 {
    90_000
}

const fn default_call_timeout_ms() -> u64 {
    120_000
}

const fn default_max_redirects() -> usize {
    5
}
