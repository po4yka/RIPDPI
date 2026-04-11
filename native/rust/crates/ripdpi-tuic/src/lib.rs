use std::io;

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
}

#[derive(Debug, Clone)]
pub struct TuicClient {
    _config: Config,
}

impl TuicClient {
    pub async fn connect(config: Config) -> io::Result<Self> {
        Ok(Self { _config: config })
    }

    pub async fn tcp_connect(&self, _authority: &str) -> io::Result<tokio::net::TcpStream> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "TUIC relay backend scaffold is not fully implemented yet",
        ))
    }
}
