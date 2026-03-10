#![forbid(unsafe_code)]

use std::fmt;
use std::fs;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::str::FromStr;

use ciadpi_packets::{IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP, MH_DMIX, MH_HMIX, MH_SPACE};

pub const VERSION: &str = "17.3";

pub const OFFSET_END: u32 = 1;
pub const OFFSET_MID: u32 = 2;
pub const OFFSET_RAND: u32 = 4;
pub const OFFSET_SNI: u32 = 8;
pub const OFFSET_HOST: u32 = 16;
pub const OFFSET_START: u32 = 32;

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
pub struct OffsetExpr {
    pub pos: i64,
    pub flag: u32,
    pub repeats: i32,
    pub skip: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartSpec {
    pub mode: DesyncMode,
    pub offset: OffsetExpr,
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
    pub drop_sack: bool,
    pub oob_data: Option<u8>,
    pub parts: Vec<PartSpec>,
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
            drop_sack: false,
            oob_data: None,
            parts: Vec::new(),
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
        !self.parts.is_empty()
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
    pub groups: Vec<DesyncGroup>,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        let ipv6 = ipv6_supported();
        Self {
            listen: ListenConfig {
                listen_ip: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
                listen_port: 1080,
                bind_ip: if ipv6 {
                    IpAddr::V6(Ipv6Addr::UNSPECIFIED)
                } else {
                    IpAddr::V4(Ipv4Addr::UNSPECIFIED)
                },
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
            groups: vec![DesyncGroup::new(0)],
        }
    }
}

impl RuntimeConfig {
    pub fn actionable_group(&self) -> usize {
        self.groups
            .iter()
            .position(DesyncGroup::is_actionable)
            .unwrap_or(0)
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
        Self {
            option: option.into(),
            value: value.map(Into::into),
        }
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
            let bits = bits_str
                .parse::<u16>()
                .map_err(|_| ConfigError::invalid("--ipset", Some(token)))?;
            if bits == 0 {
                return Err(ConfigError::invalid("--ipset", Some(token)));
            }
            (addr, bits)
        }
        None => (token, 0),
    };
    let addr =
        IpAddr::from_str(addr_str).map_err(|_| ConfigError::invalid("--ipset", Some(token)))?;
    let max_bits = match addr {
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    };
    let bits = if bits == 0 || bits > max_bits {
        max_bits
    } else {
        bits
    };
    Ok(Cidr {
        addr,
        bits: bits as u8,
    })
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
        while oct_end < bytes.len() && oct_end < idx + 3 && (b'0'..=b'7').contains(&bytes[oct_end])
        {
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

fn parse_numeric_addr(spec: &str) -> Result<(IpAddr, Option<u16>), ConfigError> {
    let (host, port) = if let Some(rest) = spec.strip_prefix('[') {
        let end = rest
            .find(']')
            .ok_or_else(|| ConfigError::invalid("address", Some(spec)))?;
        let host = &rest[..end];
        let suffix = &rest[end + 1..];
        let port = if let Some(port_str) = suffix.strip_prefix(':') {
            Some(
                port_str
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid("address", Some(spec)))?,
            )
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
                Some((host, port_str))
                    if !port_str.is_empty() && port_str.as_bytes()[0].is_ascii_digit() =>
                {
                    let port = port_str
                        .parse::<u16>()
                        .map_err(|_| ConfigError::invalid("address", Some(spec)))?;
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

pub fn parse_offset_expr(spec: &str) -> Result<OffsetExpr, ConfigError> {
    let mut base = spec;
    let mut flag = 0u32;
    if let Some((prefix, suffix)) = spec.split_once('+') {
        base = prefix;
        let bytes = suffix.as_bytes();
        match bytes.first().copied() {
            Some(b's') => flag |= OFFSET_SNI,
            Some(b'h') => flag |= OFFSET_HOST,
            Some(b'n') | None => {}
            _ => return Err(ConfigError::invalid("offset", Some(spec))),
        }
        match bytes.get(1).copied() {
            Some(b'e') => flag |= OFFSET_END,
            Some(b'm') => flag |= OFFSET_MID,
            Some(b'r') => flag |= OFFSET_RAND,
            Some(b's') => flag |= OFFSET_START,
            _ => {}
        }
    }

    let mut parts = base.split(':');
    let pos = parts
        .next()
        .ok_or_else(|| ConfigError::invalid("offset", Some(spec)))?
        .parse::<i64>()
        .map_err(|_| ConfigError::invalid("offset", Some(spec)))?;
    let repeats = match parts.next() {
        Some(value) => {
            let parsed = value
                .parse::<i32>()
                .map_err(|_| ConfigError::invalid("offset", Some(spec)))?;
            if parsed <= 0 {
                return Err(ConfigError::invalid("offset", Some(spec)));
            }
            parsed
        }
        None => 0,
    };
    let skip = match parts.next() {
        Some(value) => value
            .parse::<i32>()
            .map_err(|_| ConfigError::invalid("offset", Some(spec)))?,
        None => 0,
    };
    Ok(OffsetExpr {
        pos,
        flag,
        repeats,
        skip,
    })
}

fn parse_timeout(spec: &str, config: &mut RuntimeConfig) -> Result<(), ConfigError> {
    let mut parts = spec.split(':');
    config.timeout_ms = seconds_to_millis(
        parts
            .next()
            .ok_or_else(|| ConfigError::invalid("--timeout", Some(spec)))?,
    )?;
    if let Some(value) = parts.next() {
        config.partial_timeout_ms = seconds_to_millis(value)?;
    }
    if let Some(value) = parts.next() {
        config.timeout_count_limit = value
            .parse::<i32>()
            .map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if let Some(value) = parts.next() {
        config.timeout_bytes_limit = value
            .parse::<i32>()
            .map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if parts.next().is_some() {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok(())
}

fn seconds_to_millis(spec: &str) -> Result<u32, ConfigError> {
    let seconds = spec
        .parse::<f32>()
        .map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    if seconds < 0.0 {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok((seconds * 1000.0) as u32)
}

fn split_plugin_options(spec: &str) -> Vec<String> {
    spec.split(' ')
        .filter(|token| !token.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

fn next_value<'a>(
    args: &'a [String],
    idx: &mut usize,
    option: &str,
) -> Result<&'a str, ConfigError> {
    *idx += 1;
    args.get(*idx)
        .map(String::as_str)
        .ok_or_else(|| ConfigError::invalid(option, Option::<String>::None))
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

    let effective_args = if let Some(options) = &startup.ss_plugin_options {
        split_plugin_options(options)
    } else {
        args.to_vec()
    };

    let mut all_limited = true;
    let mut current_group_index = 0usize;
    let mut idx = 0usize;

    while idx < effective_args.len() {
        let arg = &effective_args[idx];
        macro_rules! group {
            () => {
                config
                    .groups
                    .get_mut(current_group_index)
                    .expect("current group exists")
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
                let port = value
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
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
                let size = value
                    .parse::<usize>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if size == 0 || size >= (i32::MAX as usize) / 4 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.buffer_size = size;
            }
            "-c" | "--max-conn" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let count = value
                    .parse::<i32>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if count <= 0 || count >= (0xffff / 2) {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.max_open = count;
            }
            "-x" | "--debug" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let level = value
                    .parse::<i32>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
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
                            let (_, pri) = token
                                .split_once('=')
                                .ok_or_else(|| ConfigError::invalid("--auto", Some(token)))?;
                            let pri = pri
                                .parse::<f32>()
                                .map_err(|_| ConfigError::invalid("--auto", Some(token)))?;
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
                let ttl = value
                    .parse::<i64>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
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
                let merge = value
                    .parse::<u8>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if merge > 32 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.cache_prefix = 32 - merge;
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
            "-s" | "--split" | "-d" | "--disorder" | "-o" | "--oob" | "-q" | "--disoob" | "-f"
            | "--fake" => {
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
            }
            "-t" | "--ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
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
                    match token.chars().next() {
                        Some('r') => group!().fake_mod |= FM_RAND,
                        Some('o') => group!().fake_mod |= FM_ORIG,
                        Some('m') => {
                            let (_, val) = token
                                .split_once('=')
                                .or_else(|| {
                                    token.strip_prefix("msize=").map(|rest| ("msize", rest))
                                })
                                .ok_or_else(|| ConfigError::invalid(arg, Some(value)))?;
                            group!().fake_tls_size = val
                                .parse::<i32>()
                                .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                        }
                        _ => return Err(ConfigError::invalid(arg, Some(value))),
                    }
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
                if expr.pos > u16::MAX as i64 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().tls_records.push(expr);
            }
            "-m" | "--tlsminor" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let tlsminor = value
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if tlsminor == 0 || tlsminor > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().tlsminor = Some(tlsminor as u8);
            }
            "-a" | "--udp-fake" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().udp_fake_count = value
                    .parse::<i32>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if group!().udp_fake_count < 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
            }
            "-V" | "--pf" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (start, end) = match value.split_once('-') {
                    Some((start, end)) => (start, end),
                    None => (value, value),
                };
                let start = start
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                let end = end
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
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
                let start = start
                    .parse::<i32>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                let end = end
                    .parse::<i32>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if start <= 0 || end <= 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().rounds = [start, end];
            }
            "-g" | "--def-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value
                    .parse::<u16>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl == 0 || ttl > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.default_ttl = ttl as u8;
                config.custom_ttl = true;
            }
            "-W" | "--await-int" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.await_interval = value
                    .parse::<i32>()
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "-C" | "--to-socks5" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, port) = parse_numeric_addr(value)?;
                let port = port.ok_or_else(|| ConfigError::invalid(arg, Some(value)))?;
                group!().ext_socks = Some(UpstreamSocksConfig {
                    addr: SocketAddr::new(ip, port),
                });
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

    Ok(ParseResult::Run(config))
}

fn common_suffix_match(host: &str, rule: &str) -> bool {
    host == rule
        || host
            .strip_suffix(rule)
            .is_some_and(|prefix| prefix.ends_with('.'))
}

impl FilterSet {
    pub fn hosts_match(&self, host: &str) -> bool {
        self.hosts
            .iter()
            .any(|rule| common_suffix_match(host, rule))
    }

    pub fn ipset_match(&self, ip: IpAddr) -> bool {
        self.ipset.iter().any(|rule| rule.matches(ip))
    }
}

impl Cidr {
    pub fn matches(&self, ip: IpAddr) -> bool {
        match (self.addr, ip) {
            (IpAddr::V4(lhs), IpAddr::V4(rhs)) => {
                prefix_match_bytes(&lhs.octets(), &rhs.octets(), self.bits)
            }
            (IpAddr::V6(lhs), IpAddr::V6(rhs)) => {
                prefix_match_bytes(&lhs.octets(), &rhs.octets(), self.bits)
            }
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
            host: if parts[5] == "-" {
                None
            } else {
                Some(parts[5].to_owned())
            },
        });
    }
    out
}

pub fn load_cache_entries_from_path(path: &Path) -> Result<Vec<CacheEntry>, ConfigError> {
    let text = fs::read_to_string(path)
        .map_err(|_| ConfigError::invalid("cache-file", Some(path.display().to_string())))?;
    Ok(load_cache_entries(&text))
}

pub fn dump_cache_entries(entries: &[CacheEntry]) -> String {
    let mut out = String::new();
    for entry in entries {
        let host = entry.host.as_deref().unwrap_or("-");
        out.push_str(&format!(
            "0 {} {} {} {} {}\n",
            entry.addr, entry.bits, entry.port, entry.time, host
        ));
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
                Cidr {
                    addr: IpAddr::from_str("192.0.2.1").expect("ipv4 addr"),
                    bits: 32,
                },
                Cidr {
                    addr: IpAddr::from_str("2001:db8::1").expect("ipv6 addr"),
                    bits: 128,
                },
            ]
        );
    }

    #[test]
    fn prefix_match_bytes_honors_partial_bits() {
        assert!(prefix_match_bytes(&[0b1011_0000], &[0b1011_1111], 4));
        assert!(!prefix_match_bytes(&[0b1011_0000], &[0b1001_1111], 4));
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
