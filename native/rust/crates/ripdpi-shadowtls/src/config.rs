use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    pub password: String,
    pub server_name: String,
    pub inner_profile_id: String,
}
