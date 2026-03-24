//! Test configuration builders for the tunnel.

use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_tunnel_config::{Config, MiscConfig, Socks5Config, TunnelConfig};

/// Build a `Config` suitable for E2E tunnel tests.
///
/// The tunnel is configured with a private IPv4 address and the SOCKS5 proxy
/// is pointed at the given `socks5_addr` (typically a local-network-fixture).
pub fn test_tunnel_config(socks5_addr: SocketAddr) -> Arc<Config> {
    Arc::new(Config {
        tunnel: TunnelConfig {
            name: "test-tun".to_string(),
            mtu: 1500,
            multi_queue: false,
            ipv4: Some("10.0.0.1/24".to_string()),
            ipv6: None,
            post_up_script: None,
            pre_down_script: None,
        },
        socks5: Socks5Config {
            port: socks5_addr.port(),
            address: socks5_addr.ip().to_string(),
            udp: Some("udp".to_string()),
            udp_address: None,
            pipeline: None,
            username: None,
            password: None,
            mark: None,
        },
        mapdns: None,
        misc: MiscConfig {
            connect_timeout: 5000,
            tcp_read_write_timeout: 10000,
            udp_read_write_timeout: 10000,
            filter_injected_resets: false,
            ..MiscConfig::default()
        },
    })
}
