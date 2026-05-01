use std::net::SocketAddr;

use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

use super::{
    ActivationFilter, FakePacketSource, FilterSet, IpIdMode, NumericRange, OffsetExpr, RotationPolicy, TcpChainStep,
    UdpChainStep,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UpstreamSocksConfig {
    pub addr: SocketAddr,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AutoTtlConfig {
    pub delta: i8,
    pub min_ttl: u8,
    pub max_ttl: u8,
}

/// TCP window size control. Combines pre-connect `SO_RCVBUF` (to influence
/// window scale negotiation) with post-connect `TCP_WINDOW_CLAMP` (to cap the
/// advertised receive window). Together they force the server to send small
/// segments from the very first data packet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WsizeConfig {
    /// Desired TCP receive window size in bytes.
    pub window: u32,
    /// Optional window scale factor override (0-14).
    /// When set, `SO_RCVBUF` is tuned to produce this scale factor in the SYN.
    /// When `None`, `SO_RCVBUF` is set to `window` to let the kernel pick.
    pub scale: Option<u8>,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum WsTunnelMode {
    #[default]
    Off,
    Always,
    Fallback,
}

impl WsTunnelMode {
    pub fn is_enabled(self) -> bool {
        self != Self::Off
    }
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum QuicInitialMode {
    Disabled,
    Route,
    #[default]
    RouteAndCache,
}

#[non_exhaustive]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum QuicFakeProfile {
    #[default]
    Disabled,
    CompatDefault,
    RealisticInitial,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroup {
    pub id: usize,
    pub bit: u64,
    pub matches: DesyncGroupMatchSettings,
    pub actions: DesyncGroupActionSettings,
    pub policy: DesyncGroupPolicySettings,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DesyncGroupMatchSettings {
    pub detect: u32,
    pub proto: u32,
    pub any_protocol: bool,
    pub filters: FilterSet,
    pub port_filter: Option<(u16, u16)>,
    pub activation_filter: Option<ActivationFilter>,
    /// Bitmask of protocol flags (IS_HTTP, IS_HTTPS) to skip during payload
    /// classification, preventing false-positive protocol detection.
    pub payload_disable: u32,
}

/// Which entropy-based DPI detection model to counter with padding.
#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum EntropyMode {
    /// No entropy padding applied.
    #[default]
    Disabled,
    /// GFW popcount-based detection bypass.
    Popcount,
    /// middlebox Shannon entropy analysis bypass.
    Shannon,
    /// Counter both popcount and Shannon detection.
    Combined,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroupActionSettings {
    pub ttl: Option<u8>,
    pub auto_ttl: Option<AutoTtlConfig>,
    pub md5sig: bool,
    pub fake_data: Option<Vec<u8>>,
    pub fake_tls_source: FakePacketSource,
    pub fake_tls_secondary_profile: Option<TlsFakeProfile>,
    pub fake_tcp_timestamp_enabled: bool,
    pub fake_tcp_timestamp_delta_ticks: i32,
    pub fake_offset: Option<OffsetExpr>,
    pub fake_sni_list: Vec<String>,
    pub fake_mod: u32,
    pub fake_tls_size: i32,
    pub http_fake_profile: HttpFakeProfile,
    pub tls_fake_profile: TlsFakeProfile,
    pub udp_fake_profile: UdpFakeProfile,
    pub quic_fake_profile: QuicFakeProfile,
    pub quic_fake_host: Option<String>,
    pub drop_sack: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    pub quic_fake_version: u32,
    pub ip_id_mode: Option<IpIdMode>,
    pub oob_data: Option<u8>,
    pub tcp_chain: Vec<TcpChainStep>,
    pub rotation_policy: Option<RotationPolicy>,
    pub udp_chain: Vec<UdpChainStep>,
    pub mod_http: u32,
    pub tlsminor: Option<u8>,
    pub window_clamp: Option<u32>,
    pub wsize: Option<WsizeConfig>,
    pub strip_timestamps: bool,
    /// GFW popcount bypass: target popcount in permil (e.g. 3400 = 3.4).
    /// None = disabled. Pads fake payloads with printable ASCII to lower
    /// average popcount below the GFW detection threshold.
    pub entropy_padding_target_permil: Option<u32>,
    /// Maximum entropy padding bytes (default 256).
    pub entropy_padding_max: u32,
    /// Which entropy detection model to counter.
    pub entropy_mode: EntropyMode,
    /// Shannon entropy target in permil (e.g. 7920 = 7.92 bits/byte).
    /// Used when entropy_mode is Shannon or Combined.
    pub shannon_entropy_target_permil: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DesyncGroupPolicySettings {
    pub ext_socks: Option<UpstreamSocksConfig>,
    pub label: String,
    pub pri: i32,
    pub fail_count: i32,
    pub cache_ttl: i64,
    pub cache_file: Option<String>,
}

impl DesyncGroup {
    pub fn new(id: usize) -> Self {
        Self {
            id,
            bit: 1u64 << id,
            matches: DesyncGroupMatchSettings::default(),
            actions: DesyncGroupActionSettings::default(),
            policy: DesyncGroupPolicySettings::default(),
        }
    }

    pub fn is_actionable(&self) -> bool {
        !self.actions.tcp_chain.is_empty()
            || !self.actions.udp_chain.is_empty()
            || self.actions.mod_http != 0
            || self.actions.tlsminor.is_some()
            || self.actions.fake_data.is_some()
            || !self.actions.fake_sni_list.is_empty()
            || self.actions.fake_offset.is_some()
            || self.matches.detect != 0
            || !self.matches.filters.hosts.is_empty()
            || !self.matches.filters.ipset.is_empty()
            || self.matches.port_filter.is_some()
            || self.policy.ext_socks.is_some()
    }

    pub fn effective_tcp_chain(&self) -> Vec<TcpChainStep> {
        self.actions.tcp_chain.clone()
    }

    pub fn effective_udp_chain(&self) -> Vec<UdpChainStep> {
        self.actions.udp_chain.clone()
    }

    pub fn activation_filter(&self) -> Option<ActivationFilter> {
        self.matches.activation_filter.filter(|filter| !filter.is_unbounded())
    }

    pub fn set_activation_filter(&mut self, filter: ActivationFilter) {
        self.matches.activation_filter = (!filter.is_unbounded()).then_some(filter);
    }

    pub fn set_round_activation(&mut self, range: Option<NumericRange<i64>>) {
        let mut filter = self.matches.activation_filter.unwrap_or_default();
        filter.round = range;
        self.set_activation_filter(filter);
    }
}
