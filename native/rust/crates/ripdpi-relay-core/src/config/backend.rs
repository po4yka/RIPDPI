include!("backend/hysteria2.rs");
include!("backend/tuic.rs");
include!("backend/vless.rs");
include!("backend/cloudflare.rs");
include!("backend/chain.rs");
include!("backend/masque.rs");
include!("backend/shadowtls.rs");
include!("backend/trojan.rs");
include!("backend/anytls.rs");
include!("backend/shadowsocks.rs");
include!("backend/naive.rs");
include!("backend/unsupported.rs");

#[derive(Debug, Clone)]
pub enum RelayBackendConfig {
    Hysteria2(Hysteria2RelayConfig),
    TuicV5(TuicRelayConfig),
    VlessReality(VlessRealityRelayConfig),
    CloudflareTunnel(CloudflareTunnelRelayConfig),
    ChainRelay(ChainRelayConfig),
    Masque(MasqueRelayConfig),
    ShadowTlsV3(ShadowTlsRelayConfig),
    Trojan(TrojanRelayConfig),
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
            Self::VlessReality(_) => "vless_reality",
            Self::CloudflareTunnel(_) => "cloudflare_tunnel",
            Self::ChainRelay(_) => "chain_relay",
            Self::Masque(_) => "masque",
            Self::ShadowTlsV3(_) => "shadowtls_v3",
            Self::Trojan(_) => "trojan",
            Self::AnyTls(_) => "anytls",
            Self::Shadowsocks(_) => "shadowsocks",
            Self::NaiveProxy(_) => "naiveproxy",
            Self::Unsupported(config) => &config.kind,
        }
    }
}
