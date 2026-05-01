use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub uuid: String,
    pub password: String,
    pub zero_rtt: bool,
    pub congestion_control: String,
    pub udp_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
}
