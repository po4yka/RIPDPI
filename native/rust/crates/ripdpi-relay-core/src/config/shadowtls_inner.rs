pub(crate) use super::*;
fn default_shadowtls_inner_vless_flow() -> String {
    "xtls-rprx-vision".to_string()
}

fn default_shadowtls_inner_vless_transport() -> String {
    "reality_tcp".to_string()
}

fn default_shadowtls_inner_xhttp_mode() -> String {
    "auto".to_string()
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResolvedShadowTlsInnerRelayConfig {
    pub kind: String,
    pub profile_id: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    #[serde(default = "default_shadowtls_inner_vless_flow")]
    pub vless_flow: String,
    #[serde(default = "default_shadowtls_inner_vless_transport")]
    pub vless_transport: String,
    #[serde(default = "default_shadowtls_inner_xhttp_mode")]
    pub xhttp_mode: String,
    pub vless_uuid: Option<String>,
    pub tls_fingerprint_profile: String,
}

impl_redacted_debug!(ResolvedShadowTlsInnerRelayConfig {
    kind,
    profile_id,
    server,
    server_port,
    server_name,
    vless_flow,
    vless_transport,
    xhttp_mode,
    tls_fingerprint_profile,
});
