#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RelayKind<'a> {
    Hysteria2,
    TuicV5,
    VlessReality { xhttp: bool },
    CloudflareTunnel,
    ChainRelay,
    Masque,
    ShadowTlsV3,
    Trojan,
    AnyTls,
    Shadowsocks,
    NaiveProxy,
    Unsupported(&'a str),
}

impl<'a> RelayKind<'a> {
    pub(crate) fn from_config(config: &'a ResolvedRelayRuntimeConfig) -> Self {
        match &config.backend {
            RelayBackendConfig::Hysteria2(_) => Self::Hysteria2,
            RelayBackendConfig::TuicV5(_) => Self::TuicV5,
            RelayBackendConfig::VlessReality(vless) => Self::VlessReality { xhttp: vless.vless_transport == "xhttp" },
            RelayBackendConfig::CloudflareTunnel(_) => Self::CloudflareTunnel,
            RelayBackendConfig::ChainRelay(_) => Self::ChainRelay,
            RelayBackendConfig::Masque(_) => Self::Masque,
            RelayBackendConfig::ShadowTlsV3(_) => Self::ShadowTlsV3,
            RelayBackendConfig::Trojan(_) => Self::Trojan,
            RelayBackendConfig::AnyTls(_) => Self::AnyTls,
            RelayBackendConfig::Shadowsocks(_) => Self::Shadowsocks,
            RelayBackendConfig::NaiveProxy(_) => Self::NaiveProxy,
            RelayBackendConfig::Unsupported(unsupported) => Self::Unsupported(&unsupported.kind),
        }
    }

    /// Whether finalmask is honoured on this kind's *active transport*.
    ///
    /// Sub-mode dependent — VLESS Reality only supports finalmask on its
    /// `xhttp` transport — so this stays a `match RelayKind` decision rather
    /// than a `RelayTransportDescriptor` field.
    pub(crate) fn supports_finalmask(self) -> bool {
        matches!(self, Self::CloudflareTunnel | Self::VlessReality { xhttp: true })
    }
}
