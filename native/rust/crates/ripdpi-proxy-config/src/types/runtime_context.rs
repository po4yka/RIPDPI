use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyRuntimeContext {
    #[serde(default)]
    pub encrypted_dns: Option<ProxyEncryptedDnsContext>,
    #[serde(default)]
    pub protect_path: Option<String>,
    #[serde(default)]
    pub preferred_edges: std::collections::BTreeMap<String, Vec<ProxyPreferredEdge>>,
    #[serde(default)]
    pub direct_path_capabilities: Vec<ProxyDirectPathCapability>,
    #[serde(default)]
    pub morph_policy: Option<ProxyMorphPolicy>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyMorphPolicy {
    pub id: String,
    #[serde(default)]
    pub first_flight_size_min: i32,
    #[serde(default)]
    pub first_flight_size_max: i32,
    #[serde(default)]
    pub padding_envelope_min: i32,
    #[serde(default)]
    pub padding_envelope_max: i32,
    #[serde(default)]
    pub entropy_target_permil: i32,
    #[serde(default)]
    pub tcp_burst_cadence_ms: Vec<i32>,
    #[serde(default)]
    pub tls_burst_cadence_ms: Vec<i32>,
    #[serde(default)]
    pub quic_burst_profile: String,
    #[serde(default)]
    pub fake_packet_shape_profile: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyPreferredEdge {
    pub ip: String,
    pub transport_kind: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyDirectPathCapability {
    pub authority: String,
    #[serde(default)]
    pub quic_usable: Option<bool>,
    #[serde(default)]
    pub udp_usable: Option<bool>,
    #[serde(default)]
    pub fallback_required: Option<bool>,
    #[serde(default)]
    pub repeated_handshake_failure_class: Option<String>,
    #[serde(default)]
    pub transport_policy_version: i32,
    #[serde(default)]
    pub ip_set_digest: String,
    #[serde(default)]
    pub dns_classification: Option<String>,
    #[serde(default = "default_quic_mode")]
    pub quic_mode: String,
    #[serde(default = "default_preferred_stack")]
    pub preferred_stack: String,
    #[serde(default = "default_transport_dns_mode")]
    pub dns_mode: String,
    #[serde(default = "default_tcp_family")]
    pub tcp_family: String,
    #[serde(default = "default_direct_mode_outcome")]
    pub outcome: String,
    #[serde(default)]
    pub transport_class: Option<String>,
    #[serde(default)]
    pub reason_code: Option<String>,
    #[serde(default)]
    pub cooldown_until: Option<i64>,
    #[serde(default)]
    pub updated_at: i64,
}

fn default_quic_mode() -> String {
    "ALLOW".to_string()
}

fn default_preferred_stack() -> String {
    "H3".to_string()
}

fn default_transport_dns_mode() -> String {
    "SYSTEM".to_string()
}

fn default_tcp_family() -> String {
    "NONE".to_string()
}

fn default_direct_mode_outcome() -> String {
    "TRANSPARENT_OK".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyLogContext {
    #[serde(default)]
    pub runtime_id: Option<String>,
    #[serde(default)]
    pub mode: Option<String>,
    #[serde(default)]
    pub policy_signature: Option<String>,
    #[serde(default)]
    pub fingerprint_hash: Option<String>,
    #[serde(default)]
    pub diagnostics_session_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyEncryptedDnsContext {
    #[serde(default)]
    pub resolver_id: Option<String>,
    pub protocol: String,
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub tls_server_name: Option<String>,
    #[serde(default)]
    pub bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub doh_url: Option<String>,
    #[serde(default)]
    pub dnscrypt_provider_name: Option<String>,
    #[serde(default)]
    pub dnscrypt_public_key: Option<String>,
}
