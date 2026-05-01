use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct TunnelConfig {
    #[serde(default = "default_tun_name")]
    pub name: String,
    #[serde(default = "default_tun_mtu")]
    pub mtu: u32,
    #[serde(default)]
    pub multi_queue: bool,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub post_up_script: Option<String>,
    pub pre_down_script: Option<String>,
}

impl Default for TunnelConfig {
    fn default() -> Self {
        Self {
            name: default_tun_name(),
            mtu: default_tun_mtu(),
            multi_queue: false,
            ipv4: None,
            ipv6: None,
            post_up_script: None,
            pre_down_script: None,
        }
    }
}

fn default_tun_name() -> String {
    "tun0".to_string()
}

fn default_tun_mtu() -> u32 {
    1500
}
