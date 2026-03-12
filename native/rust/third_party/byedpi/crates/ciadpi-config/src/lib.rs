#![forbid(unsafe_code)]

use std::fmt;
use std::fs;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::str::FromStr;

use ciadpi_packets::{IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP, MH_DMIX, MH_HMIX, MH_SPACE};

pub const VERSION: &str = "17.3";

pub const DETECT_HTTP_LOCAT: u32 = 1;
pub const DETECT_TLS_ERR: u32 = 2;
pub const DETECT_TORST: u32 = 8;
pub const DETECT_RECONN: u32 = 16;
pub const DETECT_CONNECT: u32 = 32;

pub const AUTO_RECONN: u32 = 1;
pub const AUTO_NOPOST: u32 = 2;
pub const AUTO_SORT: u32 = 4;

pub const FM_RAND: u32 = 1;
pub const FM_ORIG: u32 = 2;
pub const FM_RNDSNI: u32 = 4;
pub const FM_DUPSID: u32 = 8;
pub const FM_PADENCAP: u32 = 16;
pub const HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS: i64 = 6 * 60 * 60;
pub const HOST_AUTOLEARN_DEFAULT_MAX_HOSTS: usize = 512;
pub const HOST_AUTOLEARN_DEFAULT_STORE_FILE: &str = "host-autolearn-v1.json";

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncGroup {
    pub id: usize,
    pub bit: u64,
    pub detect: u32,
    pub proto: u32,
    pub ttl: Option<u8>,
    pub md5sig: bool,
    pub fake_data: Option<Vec<u8>>,
    pub udp_fake_count: i32,
    pub fake_offset: Option<OffsetExpr>,
    pub fake_sni_list: Vec<String>,
    pub fake_mod: u32,
    pub fake_tls_size: i32,
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

impl DesyncGroup {
    pub fn new(id: usize) -> Self {
        Self {
            id,
            bit: 1u64 << id,
            detect: 0,
            proto: 0,
            ttl: None,
            md5sig: false,
            fake_data: None,
            udp_fake_count: 0,
            fake_offset: None,
            fake_sni_list: Vec::new(),
            fake_mod: 0,
            fake_tls_size: 0,
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
            vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: self.udp_fake_count }]
        } else {
            Vec::new()
        }
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
    pub groups: Vec<DesyncGroup>,
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
            groups: vec![DesyncGroup::new(0)],
        }
    }
}

impl RuntimeConfig {
    pub fn actionable_group(&self) -> usize {
        self.groups.iter().position(DesyncGroup::is_actionable).unwrap_or(0)
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
    Run(RuntimeConfig),
    Help,
    Version,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheEntry {
    pub addr: IpAddr,
    pub bits: u16,
    pub port: u16,
    pub time: i64,
    pub host: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigError {
    pub option: String,
    pub value: Option<String>,
}

impl ConfigError {
    fn invalid(option: impl Into<String>, value: Option<impl Into<String>>) -> Self {
        Self { option: option.into(), value: value.map(Into::into) }
    }
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.value {
            Some(value) => write!(f, "invalid value for {}: {}", self.option, value),
            None => write!(f, "invalid option: {}", self.option),
        }
    }
}

impl std::error::Error for ConfigError {}

fn ipv6_supported() -> bool {
    TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).is_ok()
}

fn lower_host_char(ch: char) -> Option<char> {
    if ch.is_ascii_uppercase() {
        Some(ch.to_ascii_lowercase())
    } else if ('-'..='9').contains(&ch) || ch.is_ascii_lowercase() {
        Some(ch)
    } else {
        None
    }
}

fn host_template_char(ch: char) -> Option<char> {
    match ch {
        '.' => Some('.'),
        _ => lower_host_char(ch),
    }
}

fn is_ip_literal(value: &str) -> bool {
    value.parse::<IpAddr>().is_ok()
}

fn normalize_domain_host(spec: &str, option: &str) -> Result<String, ConfigError> {
    let trimmed = spec.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    if trimmed.contains(':') || is_ip_literal(trimmed) {
        return Err(ConfigError::invalid(option, Some(spec)));
    }

    let mut normalized = String::with_capacity(trimmed.len());
    for ch in trimmed.chars() {
        let Some(lower) = host_template_char(ch) else {
            return Err(ConfigError::invalid(option, Some(spec)));
        };
        normalized.push(lower);
    }

    if normalized.starts_with('.') || normalized.ends_with('.') || normalized.contains("..") {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    for label in normalized.split('.') {
        if label.is_empty() || label.starts_with('-') || label.ends_with('-') {
            return Err(ConfigError::invalid(option, Some(spec)));
        }
    }
    Ok(normalized)
}

pub fn normalize_fake_host_template(spec: &str) -> Result<String, ConfigError> {
    normalize_domain_host(spec, "hostfake-template")
}

pub fn normalize_quic_fake_host(spec: &str) -> Result<String, ConfigError> {
    normalize_domain_host(spec, "fake-quic-host")
}

pub fn parse_quic_fake_profile(spec: &str) -> Result<QuicFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "disabled" => Ok(QuicFakeProfile::Disabled),
        "compat_default" => Ok(QuicFakeProfile::CompatDefault),
        "realistic_initial" => Ok(QuicFakeProfile::RealisticInitial),
        _ => Err(ConfigError::invalid("--fake-quic-profile", Some(spec))),
    }
}

pub fn parse_hosts_spec(spec: &str) -> Result<Vec<String>, ConfigError> {
    let mut out = Vec::new();
    for token in spec.split_whitespace() {
        let mut normalized = String::with_capacity(token.len());
        let mut valid = true;
        for ch in token.chars() {
            match lower_host_char(ch) {
                Some(lower) => normalized.push(lower),
                None => {
                    valid = false;
                    break;
                }
            }
        }
        if valid && !normalized.is_empty() {
            out.push(normalized);
        }
    }
    Ok(out)
}

fn parse_ip_token(token: &str) -> Result<Cidr, ConfigError> {
    let (addr_str, bits) = match token.split_once('/') {
        Some((addr, bits_str)) => {
            let bits = bits_str.parse::<u16>().map_err(|_| ConfigError::invalid("--ipset", Some(token)))?;
            if bits == 0 {
                return Err(ConfigError::invalid("--ipset", Some(token)));
            }
            (addr, bits)
        }
        None => (token, 0),
    };
    let addr = IpAddr::from_str(addr_str).map_err(|_| ConfigError::invalid("--ipset", Some(token)))?;
    let max_bits = match addr {
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    };
    let bits = if bits == 0 || bits > max_bits { max_bits } else { bits };
    Ok(Cidr { addr, bits: bits as u8 })
}

pub fn parse_ipset_spec(spec: &str) -> Result<Vec<Cidr>, ConfigError> {
    let mut out = Vec::new();
    for token in spec.split_whitespace() {
        out.push(parse_ip_token(token)?);
    }
    Ok(out)
}

fn cform_byte(ch: char) -> Option<u8> {
    Some(match ch {
        'r' => b'\r',
        'n' => b'\n',
        't' => b'\t',
        '\\' => b'\\',
        'f' => 0x0c,
        'b' => 0x08,
        'v' => 0x0b,
        'a' => 0x07,
        _ => return None,
    })
}

pub fn data_from_str(spec: &str) -> Result<Vec<u8>, ConfigError> {
    if spec.is_empty() {
        return Err(ConfigError::invalid("inline-data", Some(spec)));
    }
    let bytes = spec.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut idx = 0;
    while idx < bytes.len() {
        if bytes[idx] != b'\\' {
            out.push(bytes[idx]);
            idx += 1;
            continue;
        }
        idx += 1;
        if idx >= bytes.len() {
            out.push(b'\\');
            break;
        }
        let ch = bytes[idx] as char;
        if let Some(mapped) = cform_byte(ch) {
            out.push(mapped);
            idx += 1;
            continue;
        }
        if ch == 'x' && idx + 2 < bytes.len() {
            let hex = &spec[idx + 1..idx + 3];
            if let Ok(value) = u8::from_str_radix(hex, 16) {
                out.push(value);
                idx += 3;
                continue;
            }
        }
        let mut oct_end = idx;
        while oct_end < bytes.len() && oct_end < idx + 3 && (b'0'..=b'7').contains(&bytes[oct_end]) {
            oct_end += 1;
        }
        if oct_end > idx {
            if let Ok(value) = u8::from_str_radix(&spec[idx..oct_end], 8) {
                out.push(value);
                idx = oct_end;
                continue;
            }
        }
        out.push(ch as u8);
        idx += 1;
    }
    if out.is_empty() {
        return Err(ConfigError::invalid("inline-data", Some(spec)));
    }
    Ok(out)
}

pub fn file_or_inline_bytes(spec: &str) -> Result<Vec<u8>, ConfigError> {
    if let Some(inline) = spec.strip_prefix(':') {
        return data_from_str(inline);
    }
    let data = fs::read(spec).map_err(|_| ConfigError::invalid("file", Some(spec)))?;
    if data.is_empty() {
        return Err(ConfigError::invalid("file", Some(spec)));
    }
    Ok(data)
}

fn apply_fake_tls_mod_token(group: &mut DesyncGroup, token: &str, arg: &str, raw_value: &str) -> Result<(), ConfigError> {
    let token = token.trim();
    if token.is_empty() {
        return Err(ConfigError::invalid(arg, Some(raw_value)));
    }
    match token {
        "rand" => group.fake_mod |= FM_RAND,
        "orig" => group.fake_mod |= FM_ORIG,
        "rndsni" => group.fake_mod |= FM_RNDSNI,
        "dupsid" => group.fake_mod |= FM_DUPSID,
        "padencap" => group.fake_mod |= FM_PADENCAP,
        _ => {
            let Some((name, value)) = token.split_once('=') else {
                return Err(ConfigError::invalid(arg, Some(raw_value)));
            };
            match name {
                "m" | "msize" => {
                    group.fake_tls_size =
                        value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(raw_value)))?;
                }
                _ => return Err(ConfigError::invalid(arg, Some(raw_value))),
            }
        }
    }
    Ok(())
}

fn parse_numeric_addr(spec: &str) -> Result<(IpAddr, Option<u16>), ConfigError> {
    let (host, port) = if let Some(rest) = spec.strip_prefix('[') {
        let end = rest.find(']').ok_or_else(|| ConfigError::invalid("address", Some(spec)))?;
        let host = &rest[..end];
        let suffix = &rest[end + 1..];
        let port = if let Some(port_str) = suffix.strip_prefix(':') {
            Some(port_str.parse::<u16>().map_err(|_| ConfigError::invalid("address", Some(spec)))?)
        } else if suffix.is_empty() {
            None
        } else {
            return Err(ConfigError::invalid("address", Some(spec)));
        };
        (host, port)
    } else {
        let colon_count = spec.bytes().filter(|&byte| byte == b':').count();
        if colon_count == 1 {
            match spec.rsplit_once(':') {
                Some((host, port_str)) if !port_str.is_empty() && port_str.as_bytes()[0].is_ascii_digit() => {
                    let port = port_str.parse::<u16>().map_err(|_| ConfigError::invalid("address", Some(spec)))?;
                    (host, Some(port))
                }
                _ => (spec, None),
            }
        } else {
            (spec, None)
        }
    };
    let ip = IpAddr::from_str(host).map_err(|_| ConfigError::invalid("address", Some(spec)))?;
    Ok((ip, port))
}

fn parse_named_offset_expr(spec: &str) -> Result<Option<OffsetExpr>, ConfigError> {
    let Some(split_at) = spec.char_indices().skip(1).find_map(|(idx, ch)| matches!(ch, '+' | '-').then_some(idx))
    else {
        return Ok(marker_from_name(spec).map(|base| OffsetExpr::marker(base, 0)));
    };
    let name = &spec[..split_at];
    let Some(base) = marker_from_name(name) else {
        return Ok(None);
    };
    let delta = spec[split_at..].parse::<i64>().map_err(|_| ConfigError::invalid("offset", Some(spec)))?;
    Ok(Some(OffsetExpr::marker(base, delta)))
}

fn marker_from_name(name: &str) -> Option<OffsetBase> {
    match name {
        "abs" => Some(OffsetBase::Abs),
        "host" => Some(OffsetBase::Host),
        "endhost" => Some(OffsetBase::EndHost),
        "sld" => Some(OffsetBase::Sld),
        "midsld" => Some(OffsetBase::MidSld),
        "endsld" => Some(OffsetBase::EndSld),
        "method" => Some(OffsetBase::Method),
        "extlen" => Some(OffsetBase::ExtLen),
        "sniext" => Some(OffsetBase::SniExt),
        _ => None,
    }
}

fn parse_legacy_offset_expr(spec: &str) -> Result<Option<OffsetExpr>, ConfigError> {
    let Some((prefix, suffix)) = spec.split_once('+') else {
        return Ok(None);
    };
    let delta = match prefix.parse::<i64>() {
        Ok(value) => value,
        Err(_) => return Ok(None),
    };
    if suffix.is_empty() || suffix.len() > 2 {
        return Err(ConfigError::invalid("offset", Some(spec)));
    }

    let bytes = suffix.as_bytes();
    let proto = match bytes.first().copied() {
        Some(b's') => OffsetProto::TlsOnly,
        Some(b'h') | Some(b'n') => OffsetProto::Any,
        _ => return Err(ConfigError::invalid("offset", Some(spec))),
    };
    let second = bytes.get(1).copied();

    let expr = match (bytes[0], second) {
        (b's' | b'h', None | Some(b's')) => match proto {
            OffsetProto::Any => OffsetExpr::marker(OffsetBase::Host, delta),
            OffsetProto::TlsOnly => OffsetExpr::tls_marker(OffsetBase::Host, delta),
        },
        (b's' | b'h', Some(b'e')) => match proto {
            OffsetProto::Any => OffsetExpr::marker(OffsetBase::EndHost, delta),
            OffsetProto::TlsOnly => OffsetExpr::tls_marker(OffsetBase::EndHost, delta),
        },
        (b's' | b'h', Some(b'm')) => match proto {
            OffsetProto::Any => OffsetExpr::marker(OffsetBase::HostMid, delta),
            OffsetProto::TlsOnly => OffsetExpr::tls_marker(OffsetBase::HostMid, delta),
        },
        (b's' | b'h', Some(b'r')) => match proto {
            OffsetProto::Any => OffsetExpr::marker(OffsetBase::HostRand, delta),
            OffsetProto::TlsOnly => OffsetExpr::tls_marker(OffsetBase::HostRand, delta),
        },
        (b'n', None | Some(b's')) => OffsetExpr::absolute(delta),
        (b'n', Some(b'e')) => OffsetExpr::marker(OffsetBase::PayloadEnd, delta),
        (b'n', Some(b'm')) => OffsetExpr::marker(OffsetBase::PayloadMid, delta),
        (b'n', Some(b'r')) => OffsetExpr::marker(OffsetBase::PayloadRand, delta),
        _ => return Err(ConfigError::invalid("offset", Some(spec))),
    };

    Ok(Some(expr))
}

pub fn parse_offset_expr(spec: &str) -> Result<OffsetExpr, ConfigError> {
    let mut parts = spec.split(':');
    let base = parts.next().ok_or_else(|| ConfigError::invalid("offset", Some(spec)))?;
    let repeats = match parts.next() {
        Some(value) => {
            let parsed = value.parse::<i32>().map_err(|_| ConfigError::invalid("offset", Some(spec)))?;
            if parsed <= 0 {
                return Err(ConfigError::invalid("offset", Some(spec)));
            }
            parsed
        }
        None => 0,
    };
    let skip = match parts.next() {
        Some(value) => value.parse::<i32>().map_err(|_| ConfigError::invalid("offset", Some(spec)))?,
        None => 0,
    };

    let expr = if let Ok(delta) = base.parse::<i64>() {
        OffsetExpr::absolute(delta)
    } else if let Some(expr) = parse_named_offset_expr(base)? {
        expr
    } else if let Some(expr) = parse_legacy_offset_expr(base)? {
        expr
    } else {
        return Err(ConfigError::invalid("offset", Some(spec)));
    };

    Ok(expr.with_repeat_skip(repeats, skip))
}

fn parse_timeout(spec: &str, config: &mut RuntimeConfig) -> Result<(), ConfigError> {
    let mut parts = spec.split(':');
    config.timeout_ms = seconds_to_millis(parts.next().ok_or_else(|| ConfigError::invalid("--timeout", Some(spec)))?)?;
    if let Some(value) = parts.next() {
        config.partial_timeout_ms = seconds_to_millis(value)?;
    }
    if let Some(value) = parts.next() {
        config.timeout_count_limit = value.parse::<i32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if let Some(value) = parts.next() {
        config.timeout_bytes_limit = value.parse::<i32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if parts.next().is_some() {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok(())
}

fn seconds_to_millis(spec: &str) -> Result<u32, ConfigError> {
    let seconds = spec.parse::<f32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    if seconds < 0.0 {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok((seconds * 1000.0) as u32)
}

fn split_plugin_options(spec: &str) -> Vec<String> {
    spec.split(' ').filter(|token| !token.is_empty()).map(ToOwned::to_owned).collect()
}

fn next_value<'a>(args: &'a [String], idx: &mut usize, option: &str) -> Result<&'a str, ConfigError> {
    *idx += 1;
    args.get(*idx).map(String::as_str).ok_or_else(|| ConfigError::invalid(option, Option::<String>::None))
}

fn add_group(groups: &mut Vec<DesyncGroup>) -> Result<&mut DesyncGroup, ConfigError> {
    if groups.len() >= 64 {
        return Err(ConfigError::invalid("groups", Some("too many groups")));
    }
    let id = groups.len();
    groups.push(DesyncGroup::new(id));
    Ok(groups.last_mut().expect("new group"))
}

pub fn parse_cli(args: &[String], startup: &StartupEnv) -> Result<ParseResult, ConfigError> {
    let mut config = RuntimeConfig::default();
    if let Some(port) = &startup.ss_local_port {
        if let Ok(port) = port.parse::<u16>() {
            config.listen.listen_port = port;
        } else {
            config.listen.listen_port = 0;
        }
        config.shadowsocks = true;
        if startup.protect_path_present {
            config.protect_path = Some("protect_path".to_owned());
        }
    }

    let effective_args =
        if let Some(options) = &startup.ss_plugin_options { split_plugin_options(options) } else { args.to_vec() };

    let mut all_limited = true;
    let mut current_group_index = 0usize;
    let mut idx = 0usize;

    while idx < effective_args.len() {
        let arg = &effective_args[idx];
        macro_rules! group {
            () => {
                config.groups.get_mut(current_group_index).expect("current group exists")
            };
        }

        match arg.as_str() {
            "-h" | "--help" => return Ok(ParseResult::Help),
            "-v" | "--version" => return Ok(ParseResult::Version),
            "-N" | "--no-domain" => config.resolve = false,
            "-X" => config.ipv6 = false,
            "-U" | "--no-udp" => config.udp = false,
            "-G" | "--http-connect" => config.http_connect = true,
            "-E" | "--transparent" => config.transparent = true,
            "-D" | "--daemon" => config.daemonize = true,
            "-w" | "--pidfile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.pid_file = Some(value.to_owned());
            }
            "-F" | "--tfo" => config.tfo = true,
            "-S" | "--md5sig" => group!().md5sig = true,
            "-Y" | "--drop-sack" => group!().drop_sack = true,
            "-Z" | "--wait-send" => config.wait_send = true,
            "-i" | "--ip" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, port) = parse_numeric_addr(value)?;
                config.listen.listen_ip = ip;
                if let Some(port) = port {
                    config.listen.listen_port = port;
                }
            }
            "-p" | "--port" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let port = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if port == 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.listen.listen_port = port;
            }
            "-I" | "--conn-ip" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, _) = parse_numeric_addr(value)?;
                config.listen.bind_ip = ip;
            }
            "-b" | "--buf-size" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let size = value.parse::<usize>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if size == 0 || size >= (i32::MAX as usize) / 4 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.buffer_size = size;
            }
            "-c" | "--max-conn" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if count <= 0 || count >= (0xffff / 2) {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.max_open = count;
            }
            "-x" | "--debug" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let level = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if level < 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.debug = level;
            }
            "-y" | "--cache-file" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().cache_file = Some(value.to_owned());
            }
            "-L" | "--auto-mode" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('0') | Some('2') => {
                            config.auto_level |= AUTO_NOPOST;
                            if token.starts_with('2') {
                                config.auto_level |= AUTO_SORT;
                            }
                        }
                        Some('1') => {}
                        Some('3') | Some('s') => config.auto_level |= AUTO_SORT,
                        Some('r') => config.auto_level = 0,
                        _ => return Err(ConfigError::invalid(arg, Some(value))),
                    }
                }
            }
            "-A" | "--auto" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let current = config.groups.get(current_group_index).expect("group");
                if current.filters.hosts.is_empty()
                    && current.proto == 0
                    && current.port_filter.is_none()
                    && current.detect == 0
                    && current.filters.ipset.is_empty()
                {
                    all_limited = false;
                }
                add_group(&mut config.groups)?;
                current_group_index = config.groups.len() - 1;
                for token in value.split(',') {
                    match token.as_bytes().first().copied() {
                        Some(b't') => group!().detect |= DETECT_TORST,
                        Some(b'r') => group!().detect |= DETECT_HTTP_LOCAT,
                        Some(b'a') | Some(b's') => group!().detect |= DETECT_TLS_ERR,
                        Some(b'k') => group!().detect |= DETECT_RECONN,
                        Some(b'c') => group!().detect |= DETECT_CONNECT,
                        Some(b'n') => {}
                        Some(b'p') => {
                            let (_, pri) =
                                token.split_once('=').ok_or_else(|| ConfigError::invalid("--auto", Some(token)))?;
                            let pri = pri.parse::<f32>().map_err(|_| ConfigError::invalid("--auto", Some(token)))?;
                            if let Some(prev) = config.groups.get_mut(current_group_index - 1) {
                                prev.pri = pri as i32;
                            }
                        }
                        _ => return Err(ConfigError::invalid("--auto", Some(token))),
                    }
                }
                if group!().detect != 0 {
                    config.auto_level |= AUTO_RECONN;
                }
            }
            "-u" | "--cache-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<i64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl <= 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                if config.cache_ttl == 0 {
                    config.cache_ttl = ttl;
                }
                group!().cache_ttl = ttl;
            }
            "--cache-merge" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let merge = value.parse::<u8>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if merge > 32 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.cache_prefix = 32 - merge;
            }
            "--host-autolearn" => {
                config.host_autolearn_enabled = true;
            }
            "--host-autolearn-penalty-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<i64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl <= 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.host_autolearn_enabled = true;
                config.host_autolearn_penalty_ttl_secs = ttl;
            }
            "--host-autolearn-max-hosts" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let max_hosts = value.parse::<usize>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if max_hosts == 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.host_autolearn_enabled = true;
                config.host_autolearn_max_hosts = max_hosts;
            }
            "--host-autolearn-file" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                if value.trim().is_empty() {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.host_autolearn_enabled = true;
                config.host_autolearn_store_path = Some(value.to_owned());
            }
            "-T" | "--timeout" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                parse_timeout(value, &mut config)?;
            }
            "-K" | "--proto" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('t') => group!().proto |= IS_TCP | IS_HTTPS,
                        Some('h') => group!().proto |= IS_TCP | IS_HTTP,
                        Some('u') => group!().proto |= IS_UDP,
                        Some('i') => group!().proto |= IS_IPV4,
                        _ => return Err(ConfigError::invalid(arg, Some(value))),
                    }
                }
            }
            "-H" | "--hosts" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let data = file_or_inline_bytes(value)?;
                let text = String::from_utf8_lossy(&data);
                group!().filters.hosts.extend(parse_hosts_spec(&text)?);
            }
            "-j" | "--ipset" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let data = file_or_inline_bytes(value)?;
                let text = String::from_utf8_lossy(&data);
                group!().filters.ipset.extend(parse_ipset_spec(&text)?);
            }
            "-s" | "--split" | "-d" | "--disorder" | "-o" | "--oob" | "-q" | "--disoob" | "-f" | "--fake" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let offset = parse_offset_expr(value)?;
                let mode = match arg.as_str() {
                    "-s" | "--split" => DesyncMode::Split,
                    "-d" | "--disorder" => DesyncMode::Disorder,
                    "-o" | "--oob" => DesyncMode::Oob,
                    "-q" | "--disoob" => DesyncMode::Disoob,
                    _ => DesyncMode::Fake,
                };
                group!().parts.push(PartSpec { mode, offset });
                if let Some(kind) = TcpChainStepKind::from_mode(mode) {
                    group!().tcp_chain.push(TcpChainStep::new(kind, offset));
                }
            }
            "-t" | "--ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl == 0 || ttl > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().ttl = Some(ttl as u8);
            }
            "-O" | "--fake-offset" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().fake_offset = Some(parse_offset_expr(value)?);
            }
            "-Q" | "--fake-tls-mod" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    apply_fake_tls_mod_token(group!(), token, arg, value)?;
                }
            }
            "-n" | "--fake-sni" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().fake_sni_list.push(value.to_owned());
            }
            "-l" | "--fake-data" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                if group!().fake_data.is_none() {
                    group!().fake_data = Some(file_or_inline_bytes(value)?);
                }
            }
            "-e" | "--oob-data" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let bytes = data_from_str(value)?;
                group!().oob_data = bytes.first().copied();
            }
            "-M" | "--mod-http" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('r') => group!().mod_http |= MH_SPACE,
                        Some('h') => group!().mod_http |= MH_HMIX,
                        Some('d') => group!().mod_http |= MH_DMIX,
                        _ => return Err(ConfigError::invalid(arg, Some(value))),
                    }
                }
            }
            "-r" | "--tlsrec" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let expr = parse_offset_expr(value)?;
                if expr.absolute_positive().is_some_and(|pos| pos > u16::MAX as i64) {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().tls_records.push(expr);
                group!().tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, expr));
            }
            "-m" | "--tlsminor" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let tlsminor = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if tlsminor == 0 || tlsminor > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().tlsminor = Some(tlsminor as u8);
            }
            "-a" | "--udp-fake" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if count < 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().udp_fake_count += count;
                if count > 0 {
                    group!().udp_chain.push(UdpChainStep { kind: UdpChainStepKind::FakeBurst, count });
                }
            }
            "--fake-quic-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().quic_fake_profile = parse_quic_fake_profile(value)?;
            }
            "--fake-quic-host" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().quic_fake_host = Some(normalize_quic_fake_host(value)?);
            }
            "-V" | "--pf" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (start, end) = match value.split_once('-') {
                    Some((start, end)) => (start, end),
                    None => (value, value),
                };
                let start = start.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                let end = end.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if start == 0 || end == 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().port_filter = Some((start, end));
            }
            "-R" | "--round" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (start, end) = match value.split_once('-') {
                    Some((start, end)) => (start, end),
                    None => (value, value),
                };
                let start = start.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                let end = end.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if start <= 0 || end <= 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().rounds = [start, end];
            }
            "-g" | "--def-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl == 0 || ttl > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.default_ttl = ttl as u8;
                config.custom_ttl = true;
            }
            "-W" | "--await-int" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.await_interval = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "-C" | "--to-socks5" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, port) = parse_numeric_addr(value)?;
                let port = port.ok_or_else(|| ConfigError::invalid(arg, Some(value)))?;
                group!().ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::new(ip, port) });
                config.delay_conn = true;
            }
            "-P" | "--protect-path" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.protect_path = Some(value.to_owned());
            }
            "--comment" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().label = value.to_owned();
            }
            _ => return Err(ConfigError::invalid(arg, Option::<String>::None)),
        }

        idx += 1;
    }

    if all_limited {
        add_group(&mut config.groups)?;
    }
    if !matches!(config.listen.bind_ip, IpAddr::V6(_)) {
        config.ipv6 = false;
    }
    if config.host_autolearn_enabled && config.host_autolearn_store_path.is_none() {
        config.host_autolearn_store_path = Some(HOST_AUTOLEARN_DEFAULT_STORE_FILE.to_owned());
    }

    Ok(ParseResult::Run(config))
}

fn common_suffix_match(host: &str, rule: &str) -> bool {
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

pub fn load_cache_entries(text: &str) -> Vec<CacheEntry> {
    let mut out = Vec::new();
    for line in text.lines() {
        let parts: Vec<_> = line.split_whitespace().collect();
        if parts.len() != 6 || parts[0] != "0" {
            continue;
        }
        let Ok(bits) = parts[2].parse::<u16>() else {
            continue;
        };
        let Ok(port) = parts[3].parse::<u16>() else {
            continue;
        };
        let Ok(time) = parts[4].parse::<i64>() else {
            continue;
        };
        let Ok(addr) = IpAddr::from_str(parts[1]) else {
            continue;
        };
        out.push(CacheEntry {
            addr,
            bits,
            port,
            time,
            host: if parts[5] == "-" { None } else { Some(parts[5].to_owned()) },
        });
    }
    out
}

pub fn load_cache_entries_from_path(path: &Path) -> Result<Vec<CacheEntry>, ConfigError> {
    let text =
        fs::read_to_string(path).map_err(|_| ConfigError::invalid("cache-file", Some(path.display().to_string())))?;
    Ok(load_cache_entries(&text))
}

pub fn dump_cache_entries(entries: &[CacheEntry]) -> String {
    let mut out = String::new();
    for entry in entries {
        let host = entry.host.as_deref().unwrap_or("-");
        out.push_str(&format!("0 {} {} {} {} {}\n", entry.addr, entry.bits, entry.port, entry.time, host));
    }
    out
}

pub fn config_path(name: impl Into<PathBuf>) -> PathBuf {
    name.into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_hosts_spec_normalizes_and_skips_invalid_tokens() {
        let hosts = parse_hosts_spec("Example.COM bad^host api-1.test").expect("parse hosts spec");

        assert_eq!(hosts, vec!["example.com", "api-1.test"]);
    }

    #[test]
    fn parse_ipset_spec_defaults_and_clamps_prefix_lengths() {
        let entries = parse_ipset_spec("192.0.2.1 2001:db8::1/129").expect("parse ipset spec");

        assert_eq!(
            entries,
            vec![
                Cidr { addr: IpAddr::from_str("192.0.2.1").expect("ipv4 addr"), bits: 32 },
                Cidr { addr: IpAddr::from_str("2001:db8::1").expect("ipv6 addr"), bits: 128 },
            ]
        );
    }

    #[test]
    fn prefix_match_bytes_honors_partial_bits() {
        assert!(prefix_match_bytes(&[0b1011_0000], &[0b1011_1111], 4));
        assert!(!prefix_match_bytes(&[0b1011_0000], &[0b1001_1111], 4));
    }

    #[test]
    fn data_from_str_all_cform_branches() {
        assert_eq!(data_from_str("\\r").unwrap(), vec![b'\r']);
        assert_eq!(data_from_str("\\n").unwrap(), vec![b'\n']);
        assert_eq!(data_from_str("\\t").unwrap(), vec![b'\t']);
        assert_eq!(data_from_str("\\\\").unwrap(), vec![b'\\']);
        assert_eq!(data_from_str("\\f").unwrap(), vec![0x0c]);
        assert_eq!(data_from_str("\\b").unwrap(), vec![0x08]);
        assert_eq!(data_from_str("\\v").unwrap(), vec![0x0b]);
        assert_eq!(data_from_str("\\a").unwrap(), vec![0x07]);
        // hex escape
        assert_eq!(data_from_str("\\x41").unwrap(), vec![0x41]);
        // octal escape
        assert_eq!(data_from_str("\\101").unwrap(), vec![0x41]);
    }

    #[test]
    fn data_from_str_trailing_backslash() {
        // Trailing backslash is emitted as literal backslash
        assert_eq!(data_from_str("abc\\").unwrap(), vec![97, 98, 99, b'\\']);
    }

    #[test]
    fn common_suffix_match_dot_boundary() {
        // "notexample.com" should NOT match rule "example.com"
        assert!(!common_suffix_match("notexample.com", "example.com"));
        // "sub.example.com" SHOULD match
        assert!(common_suffix_match("sub.example.com", "example.com"));
        // exact match
        assert!(common_suffix_match("example.com", "example.com"));
    }

    #[test]
    fn prefix_match_bytes_full_byte_boundary() {
        // 24-bit prefix (rem == 0 early return)
        assert!(prefix_match_bytes(&[192, 168, 1, 100], &[192, 168, 1, 200], 24));
        assert!(!prefix_match_bytes(&[192, 168, 2, 100], &[192, 168, 1, 200], 24));
    }

    #[test]
    fn seconds_to_millis_negative_rejected() {
        assert!(seconds_to_millis("-1").is_err());
    }

    #[test]
    fn parse_offset_expr_flag_combos() {
        let se = parse_offset_expr("5+se").unwrap();
        assert_eq!(se, OffsetExpr::tls_marker(OffsetBase::EndHost, 5));

        let hm = parse_offset_expr("3+hm").unwrap();
        assert_eq!(hm, OffsetExpr::marker(OffsetBase::HostMid, 3));

        let nr = parse_offset_expr("0+nr").unwrap();
        assert_eq!(nr, OffsetExpr::marker(OffsetBase::PayloadRand, 0));

        let ss = parse_offset_expr("1+ss").unwrap();
        assert_eq!(ss, OffsetExpr::tls_host(1));
    }

    #[test]
    fn parse_offset_expr_named_markers() {
        assert_eq!(parse_offset_expr("method+2").unwrap(), OffsetExpr::marker(OffsetBase::Method, 2));
        assert_eq!(parse_offset_expr("midsld").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, 0));
        assert_eq!(parse_offset_expr("midsld-1").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, -1));
        assert_eq!(parse_offset_expr("sniext+4").unwrap(), OffsetExpr::marker(OffsetBase::SniExt, 4));
        assert_eq!(parse_offset_expr("extlen").unwrap(), OffsetExpr::marker(OffsetBase::ExtLen, 0));
        assert_eq!(parse_offset_expr("abs-5").unwrap(), OffsetExpr::absolute(-5));
        assert_eq!(parse_offset_expr("-5").unwrap(), OffsetExpr::absolute(-5));
    }

    #[test]
    fn parse_offset_expr_rejects_invalid_marker_syntax() {
        for spec in ["host+", "midsld-", "unknown", "host+nope", "5+zz", "method++1"] {
            assert!(parse_offset_expr(spec).is_err(), "{spec} should be rejected");
        }
    }

    #[test]
    fn normalize_quic_fake_host_rejects_invalid_values() {
        assert_eq!(normalize_quic_fake_host(" Example.COM. ").unwrap(), "example.com");
        assert!(normalize_quic_fake_host("127.0.0.1").is_err());
        assert!(normalize_quic_fake_host("::1").is_err());
        assert!(normalize_quic_fake_host("bad..host").is_err());
    }

    #[test]
    fn parse_quic_fake_profile_accepts_known_values() {
        assert_eq!(parse_quic_fake_profile("disabled").unwrap(), QuicFakeProfile::Disabled);
        assert_eq!(parse_quic_fake_profile("compat_default").unwrap(), QuicFakeProfile::CompatDefault);
        assert_eq!(parse_quic_fake_profile("realistic_initial").unwrap(), QuicFakeProfile::RealisticInitial);
        assert!(parse_quic_fake_profile("bogus").is_err());
    }

    #[test]
    fn parse_cli_reads_quic_fake_profile_and_host() {
        let args = vec![
            "--udp-fake".to_string(),
            "2".to_string(),
            "--fake-quic-profile".to_string(),
            "realistic_initial".to_string(),
            "--fake-quic-host".to_string(),
            "Video.Example.TEST.".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(group.quic_fake_host.as_deref(), Some("video.example.test"));
        assert_eq!(group.udp_fake_count, 2);
    }

    #[test]
    fn parse_numeric_addr_ipv6_bracket_forms() {
        let (ip, port) = parse_numeric_addr("[::1]:8080").unwrap();
        assert_eq!(ip, IpAddr::from_str("::1").unwrap());
        assert_eq!(port, Some(8080));

        let (ip, port) = parse_numeric_addr("[::1]").unwrap();
        assert_eq!(ip, IpAddr::from_str("::1").unwrap());
        assert_eq!(port, None);

        let (ip, port) = parse_numeric_addr("192.168.1.1:80").unwrap();
        assert_eq!(ip, IpAddr::from_str("192.168.1.1").unwrap());
        assert_eq!(port, Some(80));
    }

    #[test]
    fn parse_hosts_spec_trims_whitespace() {
        let hosts = parse_hosts_spec("  example.com  ").unwrap();
        assert_eq!(hosts, vec!["example.com"]);
    }

    #[test]
    fn lower_host_char_range_boundaries() {
        // '-' is the low bound of the range
        assert_eq!(lower_host_char('-'), Some('-'));
        // '9' is the high bound
        assert_eq!(lower_host_char('9'), Some('9'));
        // Just below range: ','
        assert_eq!(lower_host_char(','), None);
        // Uppercase converted to lowercase
        assert_eq!(lower_host_char('A'), Some('a'));
    }

    #[test]
    fn cache_entries_round_trip_through_text_format() {
        let entries = vec![
            CacheEntry {
                addr: IpAddr::from_str("192.0.2.10").expect("ipv4 addr"),
                bits: 24,
                port: 443,
                time: 123,
                host: Some("example.com".to_string()),
            },
            CacheEntry {
                addr: IpAddr::from_str("2001:db8::10").expect("ipv6 addr"),
                bits: 128,
                port: 80,
                time: 456,
                host: None,
            },
        ];

        let dumped = dump_cache_entries(&entries);
        let loaded = load_cache_entries(&dumped);

        assert_eq!(loaded, entries);
    }
}
