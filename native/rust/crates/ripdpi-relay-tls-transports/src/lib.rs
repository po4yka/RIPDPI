#![forbid(unsafe_code)]

mod anytls;
mod hysteria_v1;
mod mieru;
mod shadowsocks;
mod shadowtls;
mod ssh;
mod tor;
mod trojan;
mod trojan_go;
mod vmess;

pub use anytls::{
    anytls_proxy_target, connect_anytls_tcp, connect_anytls_tcp_over, AnyTlsClientConfig, AnyTlsSession,
    AnyTlsSessionFactory, AnyTlsUdpSession,
};
pub use hysteria_v1::{
    HysteriaV1AuthType, HysteriaV1Config, HysteriaV1Protocol, HysteriaV1Session, HysteriaV1SessionFactory,
};
pub use mieru::{MieruConfig, MieruMux, MieruProtocol, MieruSession, MieruSessionFactory};
pub use shadowsocks::{
    connect_shadowsocks_tcp, connect_shadowsocks_tcp_over, shadowsocks_proxy_target, ShadowsocksSession,
    ShadowsocksSessionFactory, ShadowsocksUdpSession,
};
pub use shadowtls::{
    connect_shadowtls_tcp, connect_shadowtls_tcp_over, shadowtls_proxy_target, ShadowTlsClientConfig,
    ShadowTlsInnerConfig, ShadowTlsSessionFactory,
};
pub use ssh::{SshAuth, SshConfig, SshHostKeyPolicy, SshSession, SshSessionFactory};
pub use tor::{TorBridgePtRelayConfig, TorPluggableTransportConfig, TorRelayBackend, TorRelayStream, TorRelayTarget};
pub use trojan::{
    connect_trojan_tcp, connect_trojan_tcp_over, trojan_proxy_target, TrojanClientConfig, TrojanSession,
    TrojanSessionFactory, TrojanUdpSession,
};
pub use trojan_go::{TrojanGoConfig, TrojanGoInner, TrojanGoMux, TrojanGoSession, TrojanGoSessionFactory};
pub use vmess::{VmessConfig, VmessSecurity, VmessSession, VmessSessionFactory, VmessTransport};
