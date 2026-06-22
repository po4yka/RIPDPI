include!("backend/hysteria2.rs");
include!("backend/tuic.rs");
include!("backend/vless.rs");
include!("backend/mieru.rs");
include!("backend/ssh.rs");
include!("backend/cloudflare.rs");
include!("backend/chain.rs");
include!("backend/masque.rs");
include!("backend/shadowtls.rs");
include!("backend/trojan.rs");
include!("backend/tor.rs");
include!("backend/anytls.rs");
include!("backend/shadowsocks.rs");
include!("backend/naive.rs");
include!("backend/unsupported.rs");

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
