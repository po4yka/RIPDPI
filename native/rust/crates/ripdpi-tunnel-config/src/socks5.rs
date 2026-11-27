use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct Socks5Config {
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub address: String,
    pub unix_path: Option<String>,
    pub udp: Option<String>,
    pub udp_address: Option<String>,
    pub pipeline: Option<bool>,
    pub username: Option<String>,
    pub password: Option<String>,
    pub mark: Option<u32>,
}
