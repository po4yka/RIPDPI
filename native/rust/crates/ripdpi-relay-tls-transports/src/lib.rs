#![forbid(unsafe_code)]

mod anytls;
mod shadowsocks;
mod shadowtls;
mod trojan;

pub use anytls::{AnyTlsClientConfig, AnyTlsSession, AnyTlsSessionFactory, AnyTlsUdpSession};
pub use shadowsocks::{ShadowsocksSession, ShadowsocksSessionFactory, ShadowsocksUdpSession};
pub use shadowtls::{ShadowTlsClientConfig, ShadowTlsInnerConfig, ShadowTlsSessionFactory};
pub use trojan::{
    connect_trojan_tcp, connect_trojan_tcp_over, trojan_proxy_target, TrojanClientConfig, TrojanSession,
    TrojanSessionFactory, TrojanUdpSession,
};
