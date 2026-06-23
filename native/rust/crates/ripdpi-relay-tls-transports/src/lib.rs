#![forbid(unsafe_code)]

mod anytls;
mod mieru;
mod protect;
mod shadowsocks;
mod shadowtls;
mod ssh;
mod tor;
mod trojan;
mod util;

pub use anytls::{
    AnyTlsClientConfig, AnyTlsSession, AnyTlsSessionFactory, AnyTlsUdpSession, anytls_proxy_target, connect_anytls_tcp,
    connect_anytls_tcp_over,
};
pub use mieru::{MieruConfig, MieruMux, MieruProtocol, MieruSession, MieruSessionFactory};
/// Re-exported so `ripdpi-relay-core` can downcast/classify the ShadowTLS
/// version-mismatch handshake error into `FailureClass::ShadowTlsVersionMismatch`
/// without taking a direct dependency on `ripdpi-shadowtls`.
pub use ripdpi_shadowtls::{ShadowTlsFailureKind, ShadowTlsHandshakeError};
pub use shadowsocks::{
    ShadowsocksSession, ShadowsocksSessionFactory, ShadowsocksUdpSession, connect_shadowsocks_tcp,
    connect_shadowsocks_tcp_over, shadowsocks_proxy_target,
};
pub use shadowtls::{
    ShadowTlsClientConfig, ShadowTlsInnerConfig, ShadowTlsSessionFactory, connect_shadowtls_tcp,
    connect_shadowtls_tcp_over, shadowtls_proxy_target,
};
pub use ssh::{SshAuth, SshConfig, SshHostKeyPolicy, SshSession, SshSessionFactory};
pub use tor::{TorBridgePtRelayConfig, TorPluggableTransportConfig, TorRelayBackend, TorRelayStream, TorRelayTarget};
pub use trojan::{
    TrojanClientConfig, TrojanSession, TrojanSessionFactory, TrojanUdpSession, connect_trojan_tcp,
    connect_trojan_tcp_over, trojan_proxy_target,
};
