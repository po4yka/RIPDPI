use ripdpi_tunnel_config::Socks5Config;

use crate::config::payload::TunnelConfigPayload;

pub(crate) fn socks5_config_from_payload(payload: &TunnelConfigPayload) -> Socks5Config {
    Socks5Config {
        port: payload.socks5_port.unwrap_or_default(),
        address: payload.socks5_address.clone(),
        unix_path: payload.socks5_unix_path.clone(),
        udp: payload.socks5_udp.clone(),
        udp_address: payload.socks5_udp_address.clone(),
        pipeline: payload.socks5_pipeline,
        username: payload.username.clone(),
        password: payload.password.clone(),
        mark: None,
    }
}
