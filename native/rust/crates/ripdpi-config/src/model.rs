use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};

use crate::{HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NumericRange<T> {
    pub start: T,
    pub end: T,
}

impl<T> NumericRange<T> {
    pub const fn new(start: T, end: T) -> Self {
        Self { start, end }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ActivationFilter {
    pub round: Option<NumericRange<i64>>,
    pub payload_size: Option<NumericRange<i64>>,
    pub stream_bytes: Option<NumericRange<i64>>,
}

impl ActivationFilter {
    pub const fn is_unbounded(self) -> bool {
        self.round.is_none() && self.payload_size.is_none() && self.stream_bytes.is_none()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesyncMode {
    None = 0,
    Split = 1,
    Disorder = 2,
    Oob = 3,
    Disoob = 4,
    Fake = 5,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OffsetProto {
    Any,
    TlsOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OffsetBase {
    Abs,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    SniExt,
    AutoBalanced,
    AutoHost,
    AutoMidSld,
    AutoEndHost,
    AutoMethod,
    AutoSniExt,
    AutoExtLen,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OffsetExpr {
    pub base: OffsetBase,
    pub proto: OffsetProto,
    pub delta: i64,
    pub repeats: i32,
    pub skip: i32,
}

impl OffsetExpr {
    pub const fn absolute(delta: i64) -> Self {
        Self { base: OffsetBase::Abs, proto: OffsetProto::Any, delta, repeats: 0, skip: 0 }
    }

    pub const fn marker(base: OffsetBase, delta: i64) -> Self {
        Self { base, proto: OffsetProto::Any, delta, repeats: 0, skip: 0 }
    }

    pub const fn tls_marker(base: OffsetBase, delta: i64) -> Self {
        Self { base, proto: OffsetProto::TlsOnly, delta, repeats: 0, skip: 0 }
    }

    pub const fn host(delta: i64) -> Self {
        Self::marker(OffsetBase::Host, delta)
    }

    pub const fn tls_host(delta: i64) -> Self {
        Self::tls_marker(OffsetBase::Host, delta)
    }

    pub const fn adaptive(base: OffsetBase) -> Self {
        Self { base, proto: OffsetProto::Any, delta: 0, repeats: 0, skip: 0 }
    }

    pub const fn with_repeat_skip(self, repeats: i32, skip: i32) -> Self {
        Self { repeats, skip, ..self }
    }

    pub const fn needs_tls_record_adjustment(self) -> bool {
        !matches!(self.base, OffsetBase::Abs) || self.delta < 0
    }

    pub const fn absolute_positive(self) -> Option<i64> {
        if matches!(self.base, OffsetBase::Abs) && self.delta >= 0 {
            Some(self.delta)
        } else {
            None
        }
    }
}

impl OffsetBase {
    pub const fn is_adaptive(self) -> bool {
        matches!(
            self,
            Self::AutoBalanced
                | Self::AutoHost
                | Self::AutoMidSld
                | Self::AutoEndHost
                | Self::AutoMethod
                | Self::AutoSniExt
                | Self::AutoExtLen
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartSpec {
    pub mode: DesyncMode,
    pub offset: OffsetExpr,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TcpChainStepKind {
    Split,
    Disorder,
    Fake,
    FakeSplit,
    FakeDisorder,
    HostFake,
    Oob,
    Disoob,
    TlsRec,
    TlsRandRec,
}

impl TcpChainStepKind {
    pub const fn from_mode(mode: DesyncMode) -> Option<Self> {
        match mode {
            DesyncMode::None | DesyncMode::Split => Some(Self::Split),
            DesyncMode::Disorder => Some(Self::Disorder),
            DesyncMode::Oob => Some(Self::Oob),
            DesyncMode::Disoob => Some(Self::Disoob),
            DesyncMode::Fake => Some(Self::Fake),
        }
    }

    pub const fn as_mode(self) -> Option<DesyncMode> {
        match self {
            Self::Split => Some(DesyncMode::Split),
            Self::Disorder => Some(DesyncMode::Disorder),
            Self::Fake => Some(DesyncMode::Fake),
            Self::FakeSplit => Some(DesyncMode::Fake),
            Self::FakeDisorder => Some(DesyncMode::Disorder),
            Self::HostFake => None,
            Self::Oob => Some(DesyncMode::Oob),
            Self::Disoob => Some(DesyncMode::Disoob),
            Self::TlsRec => None,
            Self::TlsRandRec => None,
        }
    }

    pub const fn is_tls_prelude(self) -> bool {
        matches!(self, Self::TlsRec | Self::TlsRandRec)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpChainStep {
    pub kind: TcpChainStepKind,
    pub offset: OffsetExpr,
    pub activation_filter: Option<ActivationFilter>,
    pub midhost_offset: Option<OffsetExpr>,
    pub fake_host_template: Option<String>,
    pub fragment_count: i32,
    pub min_fragment_size: i32,
    pub max_fragment_size: i32,
}

impl TcpChainStep {
    pub const fn new(kind: TcpChainStepKind, offset: OffsetExpr) -> Self {
        Self {
            kind,
            offset,
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpChainStepKind {
    FakeBurst,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpChainStep {
    pub kind: UdpChainStepKind,
    pub count: i32,
    pub activation_filter: Option<ActivationFilter>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cidr {
    pub addr: IpAddr,
    pub bits: u8,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FilterSet {
    pub hosts: Vec<String>,
    pub ipset: Vec<Cidr>,
}

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

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum QuicInitialMode {
    Disabled,
    Route,
    #[default]
    RouteAndCache,
}

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
    pub filters: FilterSet,
    pub port_filter: Option<(u16, u16)>,
    pub activation_filter: Option<ActivationFilter>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroupActionSettings {
    pub ttl: Option<u8>,
    pub auto_ttl: Option<AutoTtlConfig>,
    pub md5sig: bool,
    pub fake_data: Option<Vec<u8>>,
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
    pub oob_data: Option<u8>,
    pub tcp_chain: Vec<TcpChainStep>,
    pub udp_chain: Vec<UdpChainStep>,
    pub mod_http: u32,
    pub tlsminor: Option<u8>,
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

impl Default for DesyncGroupActionSettings {
    fn default() -> Self {
        Self {
            ttl: None,
            auto_ttl: None,
            md5sig: false,
            fake_data: None,
            fake_offset: None,
            fake_sni_list: Vec::new(),
            fake_mod: 0,
            fake_tls_size: 0,
            http_fake_profile: HttpFakeProfile::CompatDefault,
            tls_fake_profile: TlsFakeProfile::CompatDefault,
            udp_fake_profile: UdpFakeProfile::CompatDefault,
            quic_fake_profile: QuicFakeProfile::Disabled,
            quic_fake_host: None,
            drop_sack: false,
            oob_data: None,
            tcp_chain: Vec::new(),
            udp_chain: Vec::new(),
            mod_http: 0,
            tlsminor: None,
        }
    }
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListenConfig {
    pub listen_ip: IpAddr,
    pub listen_port: u16,
    pub bind_ip: IpAddr,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeConfig {
    pub network: RuntimeNetworkSettings,
    pub timeouts: RuntimeTimeoutSettings,
    pub process: RuntimeProcessSettings,
    pub quic: RuntimeQuicSettings,
    pub adaptive: RuntimeAdaptiveSettings,
    pub host_autolearn: HostAutolearnSettings,
    pub groups: Vec<DesyncGroup>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeNetworkSettings {
    pub listen: ListenConfig,
    pub resolve: bool,
    pub ipv6: bool,
    pub udp: bool,
    pub transparent: bool,
    pub http_connect: bool,
    pub shadowsocks: bool,
    pub delay_conn: bool,
    pub tfo: bool,
    pub max_open: i32,
    pub buffer_size: usize,
    pub default_ttl: u8,
    pub custom_ttl: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeTimeoutSettings {
    pub timeout_ms: u32,
    pub partial_timeout_ms: u32,
    pub timeout_count_limit: i32,
    pub timeout_bytes_limit: i32,
    pub wait_send: bool,
    pub await_interval: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RuntimeProcessSettings {
    pub debug: i32,
    pub protect_path: Option<String>,
    pub daemonize: bool,
    pub pid_file: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeQuicSettings {
    pub initial_mode: QuicInitialMode,
    pub support_v1: bool,
    pub support_v2: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeAdaptiveSettings {
    pub auto_level: u32,
    pub cache_ttl: i64,
    pub cache_prefix: u8,
    pub network_scope_key: Option<String>,
    pub ws_tunnel_mode: WsTunnelMode,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostAutolearnSettings {
    pub enabled: bool,
    pub penalty_ttl_secs: i64,
    pub max_hosts: usize,
    pub store_path: Option<String>,
}

impl Default for RuntimeNetworkSettings {
    fn default() -> Self {
        let ipv6 = ipv6_supported();
        Self {
            listen: ListenConfig {
                listen_ip: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
                listen_port: 1080,
                bind_ip: if ipv6 { IpAddr::V6(Ipv6Addr::UNSPECIFIED) } else { IpAddr::V4(Ipv4Addr::UNSPECIFIED) },
            },
            resolve: true,
            ipv6,
            udp: true,
            transparent: false,
            http_connect: false,
            shadowsocks: false,
            delay_conn: false,
            tfo: false,
            max_open: 512,
            buffer_size: 16_384,
            default_ttl: 0,
            custom_ttl: false,
        }
    }
}

impl Default for RuntimeTimeoutSettings {
    fn default() -> Self {
        Self {
            timeout_ms: 0,
            partial_timeout_ms: 0,
            timeout_count_limit: 0,
            timeout_bytes_limit: 0,
            wait_send: false,
            await_interval: 10,
        }
    }
}

impl Default for RuntimeQuicSettings {
    fn default() -> Self {
        Self { initial_mode: QuicInitialMode::RouteAndCache, support_v1: true, support_v2: true }
    }
}

impl Default for RuntimeAdaptiveSettings {
    fn default() -> Self {
        Self {
            auto_level: 0,
            cache_ttl: 0,
            cache_prefix: 0,
            network_scope_key: None,
            ws_tunnel_mode: WsTunnelMode::Off,
        }
    }
}

impl Default for HostAutolearnSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            store_path: None,
        }
    }
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            network: RuntimeNetworkSettings::default(),
            timeouts: RuntimeTimeoutSettings::default(),
            process: RuntimeProcessSettings::default(),
            quic: RuntimeQuicSettings::default(),
            adaptive: RuntimeAdaptiveSettings::default(),
            host_autolearn: HostAutolearnSettings::default(),
            groups: vec![DesyncGroup::new(0)],
        }
    }
}

impl RuntimeConfig {
    pub fn actionable_group(&self) -> usize {
        self.groups.iter().position(DesyncGroup::is_actionable).unwrap_or(0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseResult {
    Run(Box<RuntimeConfig>),
    Help,
    Version,
}

fn ipv6_supported() -> bool {
    TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).is_ok()
}

pub(crate) fn common_suffix_match(host: &str, rule: &str) -> bool {
    host == rule || host.strip_suffix(rule).is_some_and(|prefix| prefix.ends_with('.'))
}

impl FilterSet {
    pub fn hosts_match(&self, host: &str) -> bool {
        self.hosts.iter().any(|rule| common_suffix_match(host, rule))
    }

    pub fn ipset_match(&self, ip: IpAddr) -> bool {
        self.ipset.iter().any(|rule| rule.matches(ip))
    }
}

impl Cidr {
    pub fn matches(&self, ip: IpAddr) -> bool {
        match (self.addr, ip) {
            (IpAddr::V4(lhs), IpAddr::V4(rhs)) => prefix_match_bytes(&lhs.octets(), &rhs.octets(), self.bits),
            (IpAddr::V6(lhs), IpAddr::V6(rhs)) => prefix_match_bytes(&lhs.octets(), &rhs.octets(), self.bits),
            _ => false,
        }
    }
}

pub fn prefix_match_bytes(lhs: &[u8], rhs: &[u8], bits: u8) -> bool {
    let full_bytes = (bits / 8) as usize;
    let rem = bits % 8;
    if lhs.get(..full_bytes) != rhs.get(..full_bytes) {
        return false;
    }
    if rem == 0 {
        return true;
    }
    let mask = 0xffu8 << (8 - rem);
    lhs[full_bytes] & mask == rhs[full_bytes] & mask
}
