#![forbid(unsafe_code)]

mod anytls;
mod shadowsocks;
mod shadowtls;
mod trojan;

pub use anytls::{
    anytls_proxy_target, connect_anytls_tcp, connect_anytls_tcp_over, AnyTlsClientConfig, AnyTlsSession,
    AnyTlsSessionFactory, AnyTlsUdpSession,
};
pub use shadowsocks::{
    connect_shadowsocks_tcp, connect_shadowsocks_tcp_over, shadowsocks_proxy_target, ShadowsocksSession,
    ShadowsocksSessionFactory, ShadowsocksUdpSession,
};
pub use shadowtls::{ShadowTlsClientConfig, ShadowTlsInnerConfig, ShadowTlsSessionFactory};
pub use trojan::{
    connect_trojan_tcp, connect_trojan_tcp_over, trojan_proxy_target, TrojanClientConfig, TrojanSession,
    TrojanSessionFactory, TrojanUdpSession,
};
