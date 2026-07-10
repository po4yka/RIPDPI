use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    pub password: String,
    pub server_name: String,
    pub inner_profile_id: String,
    #[serde(skip, default)]
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}
