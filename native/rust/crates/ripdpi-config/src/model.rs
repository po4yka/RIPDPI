use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};
use std::path::Path;

use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

use crate::{
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS, HOST_AUTOLEARN_DEFAULT_STORE_FILE,
};

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
    pub detect: u32,
    pub proto: u32,
    pub ttl: Option<u8>,
    pub auto_ttl: Option<AutoTtlConfig>,
    pub md5sig: bool,
    pub fake_data: Option<Vec<u8>>,
    pub udp_fake_count: i32,
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
    pub parts: Vec<PartSpec>,
    pub tcp_chain: Vec<TcpChainStep>,
    pub udp_chain: Vec<UdpChainStep>,
    pub mod_http: u32,
    pub tls_records: Vec<OffsetExpr>,
    pub tlsminor: Option<u8>,
    pub activation_filter: Option<ActivationFilter>,
    pub filters: FilterSet,
    pub port_filter: Option<(u16, u16)>,
    pub rounds: [i32; 2],
    pub ext_socks: Option<UpstreamSocksConfig>,
    pub label: String,
    pub pri: i32,
    pub fail_count: i32,
    pub cache_ttl: i64,
    pub cache_file: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroupMatchSettings {
    pub detect: u32,
    pub proto: u32,
    pub filters: FilterSet,
    pub port_filter: Option<(u16, u16)>,
    pub activation_filter: Option<ActivationFilter>,
    pub rounds: [i32; 2],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroupActionSettings {
    pub ttl: Option<u8>,
    pub auto_ttl: Option<AutoTtlConfig>,
    pub md5sig: bool,
    pub fake_data: Option<Vec<u8>>,
    pub udp_fake_count: i32,
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
    pub parts: Vec<PartSpec>,
    pub tcp_chain: Vec<TcpChainStep>,
    pub udp_chain: Vec<UdpChainStep>,
    pub mod_http: u32,
    pub tls_records: Vec<OffsetExpr>,
    pub tlsminor: Option<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroupCacheSettings {
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
            detect: 0,
            proto: 0,
            ttl: None,
            auto_ttl: None,
            md5sig: false,
            fake_data: None,
            udp_fake_count: 0,
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
            parts: Vec::new(),
            tcp_chain: Vec::new(),
            udp_chain: Vec::new(),
            mod_http: 0,
            tls_records: Vec::new(),
            tlsminor: None,
            activation_filter: None,
            filters: FilterSet::default(),
            port_filter: None,
            rounds: [0, 0],
            ext_socks: None,
            label: String::new(),
            pri: 0,
            fail_count: 0,
            cache_ttl: 0,
            cache_file: None,
        }
    }

    pub fn is_actionable(&self) -> bool {
        !self.tcp_chain.is_empty()
            || !self.udp_chain.is_empty()
            || !self.parts.is_empty()
            || !self.tls_records.is_empty()
            || self.mod_http != 0
            || self.tlsminor.is_some()
            || self.fake_data.is_some()
            || !self.fake_sni_list.is_empty()
            || self.fake_offset.is_some()
            || self.udp_fake_count != 0
            || self.detect != 0
            || !self.filters.hosts.is_empty()
            || !self.filters.ipset.is_empty()
            || self.port_filter.is_some()
            || self.ext_socks.is_some()
    }

    pub fn effective_tcp_chain(&self) -> Vec<TcpChainStep> {
        if !self.tcp_chain.is_empty() {
            return self.tcp_chain.clone();
        }

        let mut chain = Vec::with_capacity(self.tls_records.len() + self.parts.len());
        for offset in &self.tls_records {
            chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, *offset));
        }
        for part in &self.parts {
            if let Some(kind) = TcpChainStepKind::from_mode(part.mode) {
                chain.push(TcpChainStep::new(kind, part.offset));
            }
        }
        chain
    }

    pub fn effective_udp_chain(&self) -> Vec<UdpChainStep> {
        if !self.udp_chain.is_empty() {
            return self.udp_chain.clone();
        }
        if self.udp_fake_count > 0 {
            vec![UdpChainStep {
                kind: UdpChainStepKind::FakeBurst,
                count: self.udp_fake_count,
                activation_filter: None,
            }]
        } else {
            Vec::new()
        }
    }

    pub fn activation_filter(&self) -> Option<ActivationFilter> {
        self.activation_filter.filter(|filter| !filter.is_unbounded())
    }

    pub fn set_activation_filter(&mut self, filter: ActivationFilter) {
        self.activation_filter = (!filter.is_unbounded()).then_some(filter);
        self.rounds = self
            .activation_filter
            .and_then(|value| value.round)
            .and_then(|range| {
                let start = i32::try_from(range.start).ok()?;
                let end = i32::try_from(range.end).ok()?;
                Some([start, end])
            })
            .unwrap_or([0, 0]);
    }

    pub fn set_round_activation(&mut self, range: Option<NumericRange<i64>>) {
        let mut filter = self.activation_filter.unwrap_or_default();
        filter.round = range;
        self.set_activation_filter(filter);
    }

    pub fn sync_legacy_views_from_chains(&mut self) {
        if !self.tcp_chain.is_empty() {
            self.parts.clear();
            self.tls_records.clear();
            for step in &self.tcp_chain {
                match step.kind {
                    TcpChainStepKind::TlsRec => self.tls_records.push(step.offset),
                    TcpChainStepKind::TlsRandRec => {}
                    _ => {
                        if let Some(mode) = step.kind.as_mode() {
                            self.parts.push(PartSpec { mode, offset: step.offset });
                        }
                    }
                }
            }
        }

        if !self.udp_chain.is_empty() {
            self.udp_fake_count = self
                .udp_chain
                .iter()
                .filter_map(|step| matches!(step.kind, UdpChainStepKind::FakeBurst).then_some(step.count))
                .sum();
        }
    }

    pub fn match_settings(&self) -> DesyncGroupMatchSettings {
        DesyncGroupMatchSettings {
            detect: self.detect,
            proto: self.proto,
            filters: self.filters.clone(),
            port_filter: self.port_filter,
            activation_filter: self.activation_filter(),
            rounds: self.rounds,
        }
    }

    pub fn apply_match_settings(&mut self, settings: DesyncGroupMatchSettings) {
        self.detect = settings.detect;
        self.proto = settings.proto;
        self.filters = settings.filters;
        self.port_filter = settings.port_filter;
        if let Some(filter) = settings.activation_filter {
            self.set_activation_filter(filter);
        } else if settings.rounds != [0, 0] {
            self.set_round_activation(Some(NumericRange::new(
                i64::from(settings.rounds[0]),
                i64::from(settings.rounds[1]),
            )));
        } else {
            self.set_round_activation(None);
        }
    }

    pub fn action_settings(&self) -> DesyncGroupActionSettings {
        DesyncGroupActionSettings {
            ttl: self.ttl,
            auto_ttl: self.auto_ttl,
            md5sig: self.md5sig,
            fake_data: self.fake_data.clone(),
            udp_fake_count: self.udp_fake_count,
            fake_offset: self.fake_offset,
            fake_sni_list: self.fake_sni_list.clone(),
            fake_mod: self.fake_mod,
            fake_tls_size: self.fake_tls_size,
            http_fake_profile: self.http_fake_profile,
            tls_fake_profile: self.tls_fake_profile,
            udp_fake_profile: self.udp_fake_profile,
            quic_fake_profile: self.quic_fake_profile,
            quic_fake_host: self.quic_fake_host.clone(),
            drop_sack: self.drop_sack,
            oob_data: self.oob_data,
            parts: self.parts.clone(),
            tcp_chain: self.tcp_chain.clone(),
            udp_chain: self.udp_chain.clone(),
            mod_http: self.mod_http,
            tls_records: self.tls_records.clone(),
            tlsminor: self.tlsminor,
        }
    }

    pub fn apply_action_settings(&mut self, settings: DesyncGroupActionSettings) {
        self.ttl = settings.ttl;
        self.auto_ttl = settings.auto_ttl;
        self.md5sig = settings.md5sig;
        self.fake_data = settings.fake_data;
        self.udp_fake_count = settings.udp_fake_count;
        self.fake_offset = settings.fake_offset;
        self.fake_sni_list = settings.fake_sni_list;
        self.fake_mod = settings.fake_mod;
        self.fake_tls_size = settings.fake_tls_size;
        self.http_fake_profile = settings.http_fake_profile;
        self.tls_fake_profile = settings.tls_fake_profile;
        self.udp_fake_profile = settings.udp_fake_profile;
        self.quic_fake_profile = settings.quic_fake_profile;
        self.quic_fake_host = settings.quic_fake_host;
        self.drop_sack = settings.drop_sack;
        self.oob_data = settings.oob_data;
        self.parts = settings.parts;
        self.tcp_chain = settings.tcp_chain;
        self.udp_chain = settings.udp_chain;
        self.mod_http = settings.mod_http;
        self.tls_records = settings.tls_records;
        self.tlsminor = settings.tlsminor;
        self.sync_legacy_views_from_chains();
    }

    pub fn cache_settings(&self) -> DesyncGroupCacheSettings {
        DesyncGroupCacheSettings {
            ext_socks: self.ext_socks,
            label: self.label.clone(),
            pri: self.pri,
            fail_count: self.fail_count,
            cache_ttl: self.cache_ttl,
            cache_file: self.cache_file.clone(),
        }
    }

    pub fn apply_cache_settings(&mut self, settings: DesyncGroupCacheSettings) {
        self.ext_socks = settings.ext_socks;
        self.label = settings.label;
        self.pri = settings.pri;
        self.fail_count = settings.fail_count;
        self.cache_ttl = settings.cache_ttl;
        self.cache_file = settings.cache_file;
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
    pub debug: i32,
    pub buffer_size: usize,
    pub default_ttl: u8,
    pub custom_ttl: bool,
    pub timeout_ms: u32,
    pub partial_timeout_ms: u32,
    pub timeout_count_limit: i32,
    pub timeout_bytes_limit: i32,
    pub auto_level: u32,
    pub cache_ttl: i64,
    pub cache_prefix: u8,
    pub wait_send: bool,
    pub await_interval: i32,
    pub protect_path: Option<String>,
    pub daemonize: bool,
    pub pid_file: Option<String>,
    pub quic_initial_mode: QuicInitialMode,
    pub quic_support_v1: bool,
    pub quic_support_v2: bool,
    pub host_autolearn_enabled: bool,
    pub host_autolearn_penalty_ttl_secs: i64,
    pub host_autolearn_max_hosts: usize,
    pub host_autolearn_store_path: Option<String>,
    pub network_scope_key: Option<String>,
    pub ws_tunnel_mode: WsTunnelMode,
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

#[derive(Debug, Clone, PartialEq, Eq)]
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

impl Default for RuntimeConfig {
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
            debug: 0,
            buffer_size: 16_384,
            default_ttl: 0,
            custom_ttl: false,
            timeout_ms: 0,
            partial_timeout_ms: 0,
            timeout_count_limit: 0,
            timeout_bytes_limit: 0,
            auto_level: 0,
            cache_ttl: 0,
            cache_prefix: 0,
            wait_send: false,
            await_interval: 10,
            protect_path: None,
            daemonize: false,
            pid_file: None,
            quic_initial_mode: QuicInitialMode::RouteAndCache,
            quic_support_v1: true,
            quic_support_v2: true,
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
            ws_tunnel_mode: WsTunnelMode::Off,
            groups: vec![DesyncGroup::new(0)],
        }
    }
}

impl RuntimeConfig {
    pub fn actionable_group(&self) -> usize {
        self.groups.iter().position(DesyncGroup::is_actionable).unwrap_or(0)
    }

    pub fn network_settings(&self) -> RuntimeNetworkSettings {
        RuntimeNetworkSettings {
            listen: self.listen.clone(),
            resolve: self.resolve,
            ipv6: self.ipv6,
            udp: self.udp,
            transparent: self.transparent,
            http_connect: self.http_connect,
            shadowsocks: self.shadowsocks,
            delay_conn: self.delay_conn,
            tfo: self.tfo,
            max_open: self.max_open,
            buffer_size: self.buffer_size,
            default_ttl: self.default_ttl,
            custom_ttl: self.custom_ttl,
        }
    }

    pub fn apply_network_settings(&mut self, settings: RuntimeNetworkSettings) {
        self.listen = settings.listen;
        self.resolve = settings.resolve;
        self.ipv6 = settings.ipv6;
        self.udp = settings.udp;
        self.transparent = settings.transparent;
        self.http_connect = settings.http_connect;
        self.shadowsocks = settings.shadowsocks;
        self.delay_conn = settings.delay_conn;
        self.tfo = settings.tfo;
        self.max_open = settings.max_open;
        self.buffer_size = settings.buffer_size;
        self.default_ttl = settings.default_ttl;
        self.custom_ttl = settings.custom_ttl;
    }

    pub fn timeout_settings(&self) -> RuntimeTimeoutSettings {
        RuntimeTimeoutSettings {
            timeout_ms: self.timeout_ms,
            partial_timeout_ms: self.partial_timeout_ms,
            timeout_count_limit: self.timeout_count_limit,
            timeout_bytes_limit: self.timeout_bytes_limit,
            wait_send: self.wait_send,
            await_interval: self.await_interval,
        }
    }

    pub fn apply_timeout_settings(&mut self, settings: RuntimeTimeoutSettings) {
        self.timeout_ms = settings.timeout_ms;
        self.partial_timeout_ms = settings.partial_timeout_ms;
        self.timeout_count_limit = settings.timeout_count_limit;
        self.timeout_bytes_limit = settings.timeout_bytes_limit;
        self.wait_send = settings.wait_send;
        self.await_interval = settings.await_interval;
    }

    pub fn process_settings(&self) -> RuntimeProcessSettings {
        RuntimeProcessSettings {
            debug: self.debug,
            protect_path: self.protect_path.clone(),
            daemonize: self.daemonize,
            pid_file: self.pid_file.clone(),
        }
    }

    pub fn apply_process_settings(&mut self, settings: RuntimeProcessSettings) {
        self.debug = settings.debug;
        self.protect_path = settings.protect_path;
        self.daemonize = settings.daemonize;
        self.pid_file = settings.pid_file;
    }

    pub fn quic_settings(&self) -> RuntimeQuicSettings {
        RuntimeQuicSettings {
            initial_mode: self.quic_initial_mode,
            support_v1: self.quic_support_v1,
            support_v2: self.quic_support_v2,
        }
    }

    pub fn apply_quic_settings(&mut self, settings: RuntimeQuicSettings) {
        self.quic_initial_mode = settings.initial_mode;
        self.quic_support_v1 = settings.support_v1;
        self.quic_support_v2 = settings.support_v2;
    }

    pub fn adaptive_settings(&self) -> RuntimeAdaptiveSettings {
        RuntimeAdaptiveSettings {
            auto_level: self.auto_level,
            cache_ttl: self.cache_ttl,
            cache_prefix: self.cache_prefix,
            network_scope_key: self.network_scope_key.clone(),
            ws_tunnel_mode: self.ws_tunnel_mode,
        }
    }

    pub fn apply_adaptive_settings(&mut self, settings: RuntimeAdaptiveSettings) {
        self.auto_level = settings.auto_level;
        self.cache_ttl = settings.cache_ttl;
        self.cache_prefix = settings.cache_prefix;
        self.network_scope_key = settings.network_scope_key;
        self.ws_tunnel_mode = settings.ws_tunnel_mode;
    }

    pub fn host_autolearn_settings(&self) -> HostAutolearnSettings {
        HostAutolearnSettings {
            enabled: self.host_autolearn_enabled,
            penalty_ttl_secs: self.host_autolearn_penalty_ttl_secs,
            max_hosts: self.host_autolearn_max_hosts,
            store_path: self.host_autolearn_store_path.clone(),
        }
    }

    pub fn apply_host_autolearn_settings(&mut self, settings: HostAutolearnSettings) {
        self.host_autolearn_enabled = settings.enabled;
        self.host_autolearn_penalty_ttl_secs = settings.penalty_ttl_secs;
        self.host_autolearn_max_hosts = settings.max_hosts;
        self.host_autolearn_store_path =
            settings.store_path.or_else(|| settings.enabled.then(|| HOST_AUTOLEARN_DEFAULT_STORE_FILE.to_owned()));
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StartupEnv {
    pub ss_local_port: Option<String>,
    pub ss_plugin_options: Option<String>,
    pub protect_path_present: bool,
}

impl StartupEnv {
    pub fn from_env_and_cwd(cwd: &Path) -> Self {
        Self {
            ss_local_port: std::env::var("SS_LOCAL_PORT").ok(),
            ss_plugin_options: std::env::var("SS_PLUGIN_OPTIONS").ok(),
            protect_path_present: cwd.join("protect_path").exists(),
        }
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
