pub(crate) use super::*;

mod anytls;
mod chain;
mod cloudflare;
mod hysteria2;
mod masque;
mod mieru;
mod naive;
mod shadowsocks;
mod shadowtls;
mod ssh;
mod tor;
mod trojan;
mod tuic;
mod unsupported;
mod vless;

pub use anytls::AnyTlsRelayConfig;
pub use chain::{CHAIN_RELAY_MAX_HOPS, CHAIN_RELAY_MIN_HOPS, ChainRelayConfig, ResolvedChainRelayHopConfig};
pub use cloudflare::CloudflareTunnelRelayConfig;
pub use hysteria2::Hysteria2RelayConfig;
pub use masque::MasqueRelayConfig;
pub use mieru::MieruRelayConfig;
pub use naive::NaiveProxyRelayConfig;
pub use shadowsocks::ShadowsocksRelayConfig;
pub use shadowtls::ShadowTlsRelayConfig;
pub use ssh::SshRelayConfig;
pub use tor::{TorPluggableTransportConfig, TorRelayConfig};
pub use trojan::TrojanRelayConfig;
pub use tuic::TuicRelayConfig;
pub use unsupported::UnsupportedRelayConfig;
pub use vless::{VlessRealityRelayConfig, VlessRelayConfig};

#[derive(Debug, Clone)]
pub enum RelayBackendConfig {
    Hysteria2(Hysteria2RelayConfig),
    TuicV5(TuicRelayConfig),
    Vless(VlessRelayConfig),
    VlessReality(VlessRealityRelayConfig),
    Mieru(MieruRelayConfig),
    Ssh(SshRelayConfig),
    CloudflareTunnel(CloudflareTunnelRelayConfig),
    ChainRelay(ChainRelayConfig),
    Masque(MasqueRelayConfig),
    ShadowTlsV3(ShadowTlsRelayConfig),
    Trojan(TrojanRelayConfig),
    Tor(TorRelayConfig),
    AnyTls(AnyTlsRelayConfig),
    Shadowsocks(ShadowsocksRelayConfig),
    NaiveProxy(NaiveProxyRelayConfig),
    Unsupported(UnsupportedRelayConfig),
}

impl RelayBackendConfig {
    pub(crate) fn kind_id(&self) -> &str {
        match self {
            Self::Hysteria2(_) => "hysteria2",
            Self::TuicV5(_) => "tuic_v5",
            Self::Vless(_) => "vless",
            Self::VlessReality(_) => "vless_reality",
            Self::Mieru(_) => "mieru",
            Self::Ssh(_) => "ssh",
            Self::CloudflareTunnel(_) => "cloudflare_tunnel",
            Self::ChainRelay(_) => "chain_relay",
            Self::Masque(_) => "masque",
            Self::ShadowTlsV3(_) => "shadowtls_v3",
            Self::Trojan(_) => "trojan",
            Self::Tor(_) => "tor",
            Self::AnyTls(_) => "anytls",
            Self::Shadowsocks(_) => "shadowsocks",
            Self::NaiveProxy(_) => "naiveproxy",
            Self::Unsupported(config) => &config.kind,
        }
    }
}
