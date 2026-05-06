#[derive(Debug, Clone, Default)]
pub struct CloudflareTunnelRelayConfig {
    pub uuid: Option<String>,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub tunnel_mode: String,
    pub publish_local_origin_url: String,
    pub credentials_ref: String,
    pub tunnel_token: Option<String>,
    pub tunnel_credentials_json: Option<String>,
}
