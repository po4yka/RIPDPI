include!("backend/hysteria2.rs");
include!("backend/tuic.rs");
include!("backend/vless.rs");
include!("backend/vmess.rs");
include!("backend/trojan_go.rs");
include!("backend/mieru.rs");
include!("backend/hysteria_v1.rs");
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
    VlessReality(VlessRealityRelayConfig),
    Vmess(VmessRelayConfig),
    TrojanGo(TrojanGoRelayConfig),
    Mieru(MieruRelayConfig),
    HysteriaV1(HysteriaV1RelayConfig),
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
            Self::VlessReality(_) => "vless_reality",
            Self::Vmess(_) => "vmess",
            Self::TrojanGo(_) => "trojan_go",
            Self::Mieru(_) => "mieru",
            Self::HysteriaV1(_) => "hysteria_v1",
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
