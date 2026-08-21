#[derive(Clone, Default)]
pub struct VlessRelayConfig {
    pub vless_flow: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub xhttp_mode: String,
    pub uuid: Option<String>,
}

impl_redacted_debug!(VlessRelayConfig { vless_flow, vless_transport, xhttp_mode });

#[derive(Clone, Default)]
pub struct VlessRealityRelayConfig {
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_flow: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub xhttp_mode: String,
    pub vless_mux_protocol: String,
    pub vless_mux_max_concurrent_streams: u32,
    pub vless_mux_per_connection_kbps: u32,
    pub vless_mux_padding_max: u32,
    pub uuid: Option<String>,
}

impl_redacted_debug!(VlessRealityRelayConfig {
    vless_flow,
    vless_transport,
    xhttp_mode,
    vless_mux_protocol,
    vless_mux_max_concurrent_streams,
    vless_mux_per_connection_kbps,
    vless_mux_padding_max,
});
