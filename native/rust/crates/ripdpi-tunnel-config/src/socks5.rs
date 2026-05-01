use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct Socks5Config {
    pub port: u16,
    pub address: String,
    pub udp: Option<String>,
    pub udp_address: Option<String>,
    pub pipeline: Option<bool>,
    pub username: Option<String>,
    pub password: Option<String>,
    pub mark: Option<u32>,
}
