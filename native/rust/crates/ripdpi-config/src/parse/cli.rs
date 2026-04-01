use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;

use ripdpi_packets::{
    IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
};

use crate::{
    Cidr, ConfigError, DesyncGroup, DesyncMode, EntropyMode, ParseResult, RuntimeConfig, SeqOverlapFakeMode,
    StartupEnv, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind, UpstreamSocksConfig, AUTO_NOPOST,
    AUTO_RECONN, AUTO_SORT, DETECT_CONNECT, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT,
    DETECT_QUIC_BREAKAGE, DETECT_RECONN, DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_ERR,
    DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST, HOST_AUTOLEARN_DEFAULT_STORE_FILE,
};

use super::fake_profiles::{
    apply_fake_tls_mod_token, file_or_inline_bytes, lower_host_char, normalize_quic_fake_host, parse_http_fake_profile,
    parse_quic_fake_profile, parse_tls_fake_profile, parse_udp_fake_profile,
};
use super::offsets::{
    parse_auto_ttl_spec, parse_offset_expr, parse_payload_size_range_spec, parse_round_range_spec,
    parse_stream_byte_range_spec,
};

fn parse_auto_detect_token(token: &str) -> Option<u32> {
    match token.trim().to_ascii_lowercase().as_str() {
        "t" | "torst" => Some(DETECT_TORST),
        "tcp_reset" => Some(DETECT_TCP_RESET),
        "silent_drop" => Some(DETECT_SILENT_DROP),
        "r" | "redirect" => Some(DETECT_HTTP_LOCAT),
        "http_blockpage" => Some(DETECT_HTTP_BLOCKPAGE),
        "a" | "s" | "ssl_err" => Some(DETECT_TLS_ERR),
        "tls_handshake_failure" => Some(DETECT_TLS_HANDSHAKE_FAILURE),
        "tls_alert" => Some(DETECT_TLS_ALERT),
        "k" | "reconn" => Some(DETECT_RECONN),
        "c" | "connect" => Some(DETECT_CONNECT),
        "dns_tamper" => Some(DETECT_DNS_TAMPER),
        "quic_breakage" => Some(DETECT_QUIC_BREAKAGE),
        "n" | "none" => Some(0),
        _ => None,
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

pub(crate) fn parse_numeric_addr(spec: &str) -> Result<(IpAddr, Option<u16>), ConfigError> {
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

fn parse_timeout(spec: &str, config: &mut RuntimeConfig) -> Result<(), ConfigError> {
    let mut parts = spec.split(':');
    config.timeouts.timeout_ms =
        seconds_to_millis(parts.next().ok_or_else(|| ConfigError::invalid("--timeout", Some(spec)))?)?;
    if let Some(value) = parts.next() {
        config.timeouts.partial_timeout_ms = seconds_to_millis(value)?;
    }
    if let Some(value) = parts.next() {
        config.timeouts.timeout_count_limit =
            value.parse::<i32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if let Some(value) = parts.next() {
        config.timeouts.timeout_bytes_limit =
            value.parse::<i32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if parts.next().is_some() {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok(())
}

pub(crate) fn seconds_to_millis(spec: &str) -> Result<u32, ConfigError> {
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
    groups.push(DesyncGroup::new(groups.len()));
    Ok(groups.last_mut().expect("new group"))
}

fn seqovl_step_mut(group: &mut DesyncGroup) -> Option<&mut TcpChainStep> {
    group.actions.tcp_chain.iter_mut().rev().find(|step| step.kind == TcpChainStepKind::SeqOverlap)
}

pub fn parse_cli(args: &[String], startup: &StartupEnv) -> Result<ParseResult, ConfigError> {
    let mut config = RuntimeConfig::default();
    if let Some(port) = &startup.ss_local_port {
        if let Ok(port) = port.parse::<u16>() {
            config.network.listen.listen_port = port;
        } else {
            config.network.listen.listen_port = 0;
        }
        config.network.shadowsocks = true;
        if startup.protect_path_present {
            config.process.protect_path = Some("protect_path".to_owned());
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
            "-N" | "--no-domain" => config.network.resolve = false,
            "-X" => config.network.ipv6 = false,
            "-U" | "--no-udp" => config.network.udp = false,
            "-G" | "--http-connect" => config.network.http_connect = true,
            "-E" | "--transparent" => config.network.transparent = true,
            "-D" | "--daemon" => config.process.daemonize = true,
            "-w" | "--pidfile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.process.pid_file = Some(value.to_owned());
            }
            "-F" | "--tfo" => config.network.tfo = true,
            "-S" | "--md5sig" => group!().actions.md5sig = true,
            "-Y" | "--drop-sack" => group!().actions.drop_sack = true,
            "--window-clamp" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.window_clamp =
                    Some(value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?);
            }
            "--strip-timestamps" => group!().actions.strip_timestamps = true,
            "--entropy-target" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let f = value.parse::<f32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                group!().actions.entropy_padding_target_permil = Some((f * 1000.0) as u32);
            }
            "--entropy-max-pad" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.entropy_padding_max =
                    value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "--entropy-mode" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.entropy_mode = match value {
                    "popcount" => EntropyMode::Popcount,
                    "shannon" => EntropyMode::Shannon,
                    "combined" | "auto" => EntropyMode::Combined,
                    _ => return Err(ConfigError::invalid(arg, Some(value))),
                };
            }
            "--shannon-target" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let f = value.parse::<f32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if !(0.0..=8.0).contains(&f) {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().actions.shannon_entropy_target_permil = Some((f * 1000.0) as u32);
            }
            "--quic-sni-split" => {
                group!().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::QuicSniSplit, 1));
            }
            "--quic-low-port" => group!().actions.quic_bind_low_port = true,
            "--quic-dummy-prepend" => {
                group!().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::DummyPrepend, 1));
            }
            "--quic-fake-version" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let version = u32::from_str_radix(value.trim_start_matches("0x").trim_start_matches("0X"), 16)
                    .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                group!().actions.quic_fake_version = version;
                group!().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::QuicFakeVersion, 1));
            }
            "--quic-migrate" => group!().actions.quic_migrate_after_handshake = true,
            "-Z" | "--wait-send" => config.timeouts.wait_send = true,
            "-i" | "--ip" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, port) = parse_numeric_addr(value)?;
                config.network.listen.listen_ip = ip;
                if let Some(port) = port {
                    config.network.listen.listen_port = port;
                }
            }
            "-p" | "--port" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let port = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if port == 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.network.listen.listen_port = port;
            }
            "-I" | "--conn-ip" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, _) = parse_numeric_addr(value)?;
                config.network.listen.bind_ip = ip;
            }
            "-b" | "--buf-size" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let size = value.parse::<usize>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if size == 0 || size >= (i32::MAX as usize) / 4 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.network.buffer_size = size;
            }
            "-c" | "--max-conn" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if count <= 0 || count >= (0xffff / 2) {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.network.max_open = count;
            }
            "-x" | "--debug" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let level = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if level < 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.process.debug = level;
            }
            "-y" | "--cache-file" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().policy.cache_file = Some(value.to_owned());
            }
            "-L" | "--auto-mode" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('0' | '2') => {
                            config.adaptive.auto_level |= AUTO_NOPOST;
                            if token.starts_with('2') {
                                config.adaptive.auto_level |= AUTO_SORT;
                            }
                        }
                        Some('1') => {}
                        Some('3' | 's') => config.adaptive.auto_level |= AUTO_SORT,
                        Some('r') => config.adaptive.auto_level = 0,
                        _ => return Err(ConfigError::invalid(arg, Some(value))),
                    }
                }
            }
            "-A" | "--auto" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let current = config.groups.get(current_group_index).expect("group");
                if current.matches.filters.hosts.is_empty()
                    && current.matches.proto == 0
                    && current.matches.port_filter.is_none()
                    && current.matches.detect == 0
                    && current.matches.filters.ipset.is_empty()
                {
                    all_limited = false;
                }
                add_group(&mut config.groups)?;
                current_group_index = config.groups.len() - 1;
                for token in value.split(',') {
                    if token.starts_with("p=") {
                        let (_, pri) =
                            token.split_once('=').ok_or_else(|| ConfigError::invalid("--auto", Some(token)))?;
                        let pri = pri.parse::<f32>().map_err(|_| ConfigError::invalid("--auto", Some(token)))?;
                        if let Some(prev) = config.groups.get_mut(current_group_index - 1) {
                            prev.policy.pri = pri as i32;
                        }
                        continue;
                    }
                    match parse_auto_detect_token(token) {
                        Some(bits) => group!().matches.detect |= bits,
                        None => return Err(ConfigError::invalid("--auto", Some(token))),
                    }
                }
                if group!().matches.detect != 0 {
                    config.adaptive.auto_level |= AUTO_RECONN;
                }
            }
            "-u" | "--cache-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<i64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl <= 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                if config.adaptive.cache_ttl == 0 {
                    config.adaptive.cache_ttl = ttl;
                }
                group!().policy.cache_ttl = ttl;
            }
            "--cache-merge" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let merge = value.parse::<u8>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if merge > 32 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.adaptive.cache_prefix = 32 - merge;
            }
            "--host-autolearn" => {
                config.host_autolearn.enabled = true;
            }
            "--host-autolearn-penalty-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<i64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl <= 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.host_autolearn.enabled = true;
                config.host_autolearn.penalty_ttl_secs = ttl;
            }
            "--host-autolearn-max-hosts" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let max_hosts = value.parse::<usize>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if max_hosts == 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.host_autolearn.enabled = true;
                config.host_autolearn.max_hosts = max_hosts;
            }
            "--host-autolearn-file" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                if value.trim().is_empty() {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.host_autolearn.enabled = true;
                config.host_autolearn.store_path = Some(value.to_owned());
            }
            "-T" | "--timeout" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                parse_timeout(value, &mut config)?;
            }
            "-K" | "--proto" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('t') => group!().matches.proto |= IS_TCP | IS_HTTPS,
                        Some('h') => group!().matches.proto |= IS_TCP | IS_HTTP,
                        Some('u') => group!().matches.proto |= IS_UDP,
                        Some('i') => group!().matches.proto |= IS_IPV4,
                        _ => return Err(ConfigError::invalid(arg, Some(value))),
                    }
                }
            }
            "-H" | "--hosts" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let data = file_or_inline_bytes(value)?;
                let text = String::from_utf8_lossy(&data);
                group!().matches.filters.hosts.extend(parse_hosts_spec(&text)?);
            }
            "-j" | "--ipset" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let data = file_or_inline_bytes(value)?;
                let text = String::from_utf8_lossy(&data);
                group!().matches.filters.ipset.extend(parse_ipset_spec(&text)?);
            }
            "-s" | "--split" | "--seqovl" | "-d" | "--disorder" | "-o" | "--oob" | "-q" | "--disoob" | "-f"
            | "--fake" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let offset = parse_offset_expr(value)?;
                if arg == "--seqovl" {
                    if group!().actions.tcp_chain.iter().any(|step| step.kind == TcpChainStepKind::SeqOverlap) {
                        return Err(ConfigError::invalid(arg, Some("seqovl already declared for this group")));
                    }
                    if group!().actions.tcp_chain.iter().any(|step| !step.kind.is_tls_prelude()) {
                        return Err(ConfigError::invalid(arg, Some("seqovl must be the first tcp send step")));
                    }
                    let mut step = TcpChainStep::new(TcpChainStepKind::SeqOverlap, offset);
                    step.overlap_size = 12;
                    step.seqovl_fake_mode = SeqOverlapFakeMode::Profile;
                    group!().actions.tcp_chain.push(step);
                } else {
                    let mode = match arg.as_str() {
                        "-s" | "--split" => DesyncMode::Split,
                        "-d" | "--disorder" => DesyncMode::Disorder,
                        "-o" | "--oob" => DesyncMode::Oob,
                        "-q" | "--disoob" => DesyncMode::Disoob,
                        _ => DesyncMode::Fake,
                    };
                    if let Some(kind) = TcpChainStepKind::from_mode(mode) {
                        group!().actions.tcp_chain.push(TcpChainStep::new(kind, offset));
                    }
                }
            }
            "--seqovl-overlap" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let overlap = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if !(1..=32).contains(&overlap) {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                let step =
                    seqovl_step_mut(group!()).ok_or_else(|| ConfigError::invalid(arg, Some("missing --seqovl")))?;
                step.overlap_size = overlap;
            }
            "--seqovl-fake-mode" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let step =
                    seqovl_step_mut(group!()).ok_or_else(|| ConfigError::invalid(arg, Some("missing --seqovl")))?;
                step.seqovl_fake_mode = match value.trim().to_ascii_lowercase().as_str() {
                    "profile" => SeqOverlapFakeMode::Profile,
                    "rand" => SeqOverlapFakeMode::Rand,
                    _ => return Err(ConfigError::invalid(arg, Some(value))),
                };
            }
            "-t" | "--ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl == 0 || ttl > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().actions.ttl = Some(ttl as u8);
            }
            "--auto-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.auto_ttl = Some(parse_auto_ttl_spec(value)?);
            }
            "-O" | "--fake-offset" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let expr = parse_offset_expr(value)?;
                if !expr.supports_fake_offset() {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().actions.fake_offset = Some(expr);
            }
            "-Q" | "--fake-tls-mod" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    apply_fake_tls_mod_token(group!(), token, arg, value)?;
                }
            }
            "-n" | "--fake-sni" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.fake_sni_list.push(value.to_owned());
            }
            "-l" | "--fake-data" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                if group!().actions.fake_data.is_none() {
                    group!().actions.fake_data = Some(file_or_inline_bytes(value)?);
                }
            }
            "--fake-http-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.http_fake_profile = parse_http_fake_profile(value)?;
            }
            "--fake-tls-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.tls_fake_profile = parse_tls_fake_profile(value)?;
            }
            "--fake-udp-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.udp_fake_profile = parse_udp_fake_profile(value)?;
            }
            "-e" | "--oob-data" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let bytes = super::fake_profiles::data_from_str(value)?;
                group!().actions.oob_data = bytes.first().copied();
            }
            "-M" | "--mod-http" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('r') => group!().actions.mod_http |= MH_SPACE,
                        Some('h') => group!().actions.mod_http |= MH_HMIX,
                        Some('d') => group!().actions.mod_http |= MH_DMIX,
                        Some('m') => group!().actions.mod_http |= MH_METHODEOL,
                        Some('u') => group!().actions.mod_http |= MH_UNIXEOL,
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
                group!().actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, expr));
            }
            "-m" | "--tlsminor" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let tlsminor = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if tlsminor == 0 || tlsminor > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().actions.tlsminor = Some(tlsminor as u8);
            }
            "-a" | "--udp-fake" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if count < 0 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                if count > 0 {
                    group!().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::FakeBurst, count));
                }
            }
            "--fake-quic-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.quic_fake_profile = parse_quic_fake_profile(value)?;
            }
            "--fake-quic-host" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.quic_fake_host = Some(normalize_quic_fake_host(value)?);
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
                group!().matches.port_filter = Some((start, end));
            }
            "-R" | "--round" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let range = parse_round_range_spec(value)?;
                group!().set_round_activation(Some(range));
            }
            "--payload-size-range" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let range = parse_payload_size_range_spec(value)?;
                let mut filter = group!().activation_filter().unwrap_or_default();
                filter.payload_size = Some(range);
                group!().set_activation_filter(filter);
            }
            "--stream-byte-range" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let range = parse_stream_byte_range_spec(value)?;
                let mut filter = group!().activation_filter().unwrap_or_default();
                filter.stream_bytes = Some(range);
                group!().set_activation_filter(filter);
            }
            "-g" | "--def-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let ttl = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                if ttl == 0 || ttl > 255 {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                config.network.default_ttl = ttl as u8;
                config.network.custom_ttl = true;
            }
            "-W" | "--await-int" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.timeouts.await_interval =
                    value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "-C" | "--to-socks5" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, port) = parse_numeric_addr(value)?;
                let port = port.ok_or_else(|| ConfigError::invalid(arg, Some(value)))?;
                group!().policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::new(ip, port) });
                config.network.delay_conn = true;
            }
            "--connect-timeout" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.timeouts.connect_timeout_ms =
                    seconds_to_millis(value).map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "-P" | "--protect-path" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.process.protect_path = Some(value.to_owned());
            }
            "--comment" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().policy.label = value.to_owned();
            }
            "--strategy-evolution" => config.adaptive.strategy_evolution = true,
            "--evolution-epsilon" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let f = value.parse::<f64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                config.adaptive.evolution_epsilon_permil = (f * 1000.0).clamp(0.0, 1000.0) as u32;
            }
            "--freeze-window" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.timeouts.freeze_window_ms =
                    seconds_to_millis(value).map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "--freeze-min-bytes" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.timeouts.freeze_min_bytes =
                    value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            "--freeze-max-stalls" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                config.timeouts.freeze_max_stalls =
                    value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            }
            _ => return Err(ConfigError::invalid(arg, Option::<String>::None)),
        }

        idx += 1;
    }

    if all_limited {
        add_group(&mut config.groups)?;
    }
    if !matches!(config.network.listen.bind_ip, IpAddr::V6(_)) {
        config.network.ipv6 = false;
    }
    if config.host_autolearn.enabled && config.host_autolearn.store_path.is_none() {
        config.host_autolearn.store_path = Some(HOST_AUTOLEARN_DEFAULT_STORE_FILE.to_owned());
    }

    Ok(ParseResult::Run(Box::new(config)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        AutoTtlConfig, EntropyMode, NumericRange, OffsetBase, OffsetExpr, ParseResult, QuicFakeProfile,
        SeqOverlapFakeMode, TcpChainStepKind, UdpChainStep, UdpChainStepKind,
    };
    use ripdpi_packets::{
        HttpFakeProfile, TlsFakeProfile, UdpFakeProfile, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
    };
    use std::net::IpAddr;
    use std::str::FromStr;

    #[test]
    fn parse_hosts_spec_normalizes_and_skips_invalid_tokens() {
        let hosts = parse_hosts_spec("Example.COM bad^host api-1.test").expect("parse hosts spec");
        assert_eq!(hosts, vec!["example.com", "api-1.test"]);
    }

    #[test]
    fn parse_hosts_spec_trims_whitespace() {
        let hosts = parse_hosts_spec("  example.com  ").unwrap();
        assert_eq!(hosts, vec!["example.com"]);
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
    fn seconds_to_millis_negative_rejected() {
        assert!(seconds_to_millis("-1").is_err());
    }

    #[test]
    fn seconds_to_millis_valid_values() {
        assert_eq!(seconds_to_millis("1").unwrap(), 1000);
        assert_eq!(seconds_to_millis("0.5").unwrap(), 500);
        assert_eq!(seconds_to_millis("0").unwrap(), 0);
    }

    #[test]
    fn seconds_to_millis_non_numeric_rejected() {
        assert!(seconds_to_millis("abc").is_err());
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
    fn parse_cli_maps_activation_ranges_into_group_filter() {
        let args = vec![
            "--round".to_string(),
            "2-4".to_string(),
            "--payload-size-range".to_string(),
            "64-512".to_string(),
            "--stream-byte-range".to_string(),
            "0-2047".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];
        let activation = group.activation_filter().expect("group activation filter");

        assert_eq!(group.matches.activation_filter.and_then(|filter| filter.round), Some(NumericRange::new(2, 4)));
        assert_eq!(activation.round, Some(NumericRange::new(2, 4)));
        assert_eq!(activation.payload_size, Some(NumericRange::new(64, 512)));
        assert_eq!(activation.stream_bytes, Some(NumericRange::new(0, 2047)));
    }

    #[test]
    fn parse_cli_reads_auto_ttl_and_fixed_ttl_fallback() {
        let args = vec!["--ttl".to_string(), "9".to_string(), "--auto-ttl".to_string(), "-1,3-12".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.ttl, Some(9));
        assert_eq!(group.actions.auto_ttl, Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }));
    }

    #[test]
    fn parse_cli_rejects_ech_fake_offset_marker() {
        for value in ["echext", "echext+4"] {
            let args = vec!["--fake-offset".to_string(), value.to_string()];
            assert!(parse_cli(&args, &StartupEnv::default()).is_err(), "{value} should be rejected");
        }
    }

    #[test]
    fn parse_cli_accepts_ech_split_marker() {
        let args = vec!["--split".to_string(), "echext".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert_eq!(config.groups[0].actions.tcp_chain.len(), 1);
        assert_eq!(config.groups[0].actions.tcp_chain[0].kind, TcpChainStepKind::Split);
        assert_eq!(config.groups[0].actions.tcp_chain[0].offset, OffsetExpr::marker(OffsetBase::EchExt, 0));
    }

    #[test]
    fn parse_cli_parses_seqovl_step_and_fields() {
        let args = vec![
            "--tlsrec".to_string(),
            "extlen".to_string(),
            "--seqovl".to_string(),
            "auto(midsld)".to_string(),
            "--seqovl-overlap".to_string(),
            "14".to_string(),
            "--seqovl-fake-mode".to_string(),
            "rand".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let tcp_chain = &config.groups[0].actions.tcp_chain;

        assert_eq!(tcp_chain.len(), 2);
        assert_eq!(tcp_chain[0].kind, TcpChainStepKind::TlsRec);
        assert_eq!(tcp_chain[1].kind, TcpChainStepKind::SeqOverlap);
        assert_eq!(tcp_chain[1].offset, OffsetExpr::adaptive(OffsetBase::AutoMidSld));
        assert_eq!(tcp_chain[1].overlap_size, 14);
        assert_eq!(tcp_chain[1].seqovl_fake_mode, SeqOverlapFakeMode::Rand);
    }

    #[test]
    fn parse_cli_rejects_duplicate_seqovl_step() {
        let args = vec!["--seqovl".to_string(), "host+1".to_string(), "--seqovl".to_string(), "midsld".to_string()];
        let err = parse_cli(&args, &StartupEnv::default()).expect_err("duplicate seqovl");
        assert!(err.to_string().contains("seqovl already declared"));
    }

    #[test]
    fn parse_cli_rejects_non_leading_seqovl_step() {
        let args = vec!["--split".to_string(), "host+1".to_string(), "--seqovl".to_string(), "midsld".to_string()];
        let err = parse_cli(&args, &StartupEnv::default()).expect_err("non-leading seqovl");
        assert!(err.to_string().contains("seqovl must be the first tcp send step"));
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

        assert_eq!(group.actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(group.actions.quic_fake_host.as_deref(), Some("video.example.test"));
        assert_eq!(
            group.actions.udp_chain,
            vec![UdpChainStep {
                kind: UdpChainStepKind::FakeBurst,
                count: 2,
                split_bytes: 0,
                activation_filter: None,
                ip_frag_disorder: false,
                ipv6_hop_by_hop: false,
                ipv6_dest_opt: false,
                ipv6_dest_opt2: false,
                ipv6_frag_next_override: None
            }]
        );
    }

    #[test]
    fn parse_cli_reads_fake_payload_profiles() {
        let args = vec![
            "--fake-http-profile".to_string(),
            "cloudflare_get".to_string(),
            "--fake-tls-profile".to_string(),
            "google_chrome".to_string(),
            "--udp-fake".to_string(),
            "1".to_string(),
            "--fake-udp-profile".to_string(),
            "dns_query".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.http_fake_profile, HttpFakeProfile::CloudflareGet);
        assert_eq!(group.actions.tls_fake_profile, TlsFakeProfile::GoogleChrome);
        assert_eq!(group.actions.udp_fake_profile, UdpFakeProfile::DnsQuery);
    }

    #[test]
    fn parse_cli_reads_extended_http_parser_evasions() {
        let args = vec!["--mod-http".to_string(), "h,d,r,m,u".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.mod_http, MH_HMIX | MH_DMIX | MH_SPACE | MH_METHODEOL | MH_UNIXEOL);
    }

    #[test]
    fn parse_cli_rejects_unknown_extended_http_parser_evasion_letter() {
        let args = vec!["--mod-http".to_string(), "h,u,x".to_string()];
        let err = parse_cli(&args, &StartupEnv::default()).expect_err("unknown modifier should fail");
        assert!(err.to_string().contains("--mod-http"));
    }

    #[test]
    fn parse_cli_uses_shadowsocks_startup_port_and_protect_path() {
        let startup = StartupEnv {
            ss_local_port: Some("15432".to_string()),
            ss_plugin_options: None,
            protect_path_present: true,
        };

        let ParseResult::Run(config) = parse_cli(&[], &startup).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert_eq!(config.network.listen.listen_port, 15432);
        assert!(config.network.shadowsocks);
        assert_eq!(config.process.protect_path.as_deref(), Some("protect_path"));
    }

    #[test]
    fn parse_cli_prefers_ss_plugin_options_over_explicit_args() {
        let startup = StartupEnv {
            ss_local_port: None,
            ss_plugin_options: Some("--port 2442 --debug 3".to_string()),
            protect_path_present: false,
        };
        let args = vec!["--port".to_string(), "1080".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &startup).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert_eq!(config.network.listen.listen_port, 2442);
        assert_eq!(config.process.debug, 3);
    }

    #[test]
    fn cli_parses_quic_sni_split() {
        let args = vec!["--quic-sni-split".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.udp_chain.len(), 1);
        assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::QuicSniSplit);
        assert_eq!(group.actions.udp_chain[0].count, 1);
    }

    #[test]
    fn cli_parses_quic_low_port() {
        let args = vec!["--quic-low-port".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert!(config.groups[0].actions.quic_bind_low_port);
    }

    #[test]
    fn cli_parses_quic_dummy_prepend() {
        let args = vec!["--quic-dummy-prepend".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.udp_chain.len(), 1);
        assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::DummyPrepend);
        assert_eq!(group.actions.udp_chain[0].count, 1);
    }

    #[test]
    fn cli_parses_quic_fake_version() {
        let args = vec!["--quic-fake-version".to_string(), "0x1a2a3a4a".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.quic_fake_version, 0x1a2a_3a4a);
        assert_eq!(group.actions.udp_chain.len(), 1);
        assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::QuicFakeVersion);
    }

    #[test]
    fn cli_parses_quic_migrate() {
        let args = vec!["--quic-migrate".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert!(config.groups[0].actions.quic_migrate_after_handshake);
    }

    #[test]
    fn cli_parses_window_clamp_flag() {
        let args: Vec<String> = ["--window-clamp", "2"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.window_clamp, Some(2));
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_strip_timestamps_flag() {
        let args: Vec<String> = ["--strip-timestamps"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert!(config.groups[0].actions.strip_timestamps);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_strategy_evolution() {
        let args: Vec<String> =
            ["--strategy-evolution", "--evolution-epsilon", "0.2"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert!(config.adaptive.strategy_evolution);
                assert_eq!(config.adaptive.evolution_epsilon_permil, 200);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_target() {
        let args: Vec<String> =
            ["--entropy-target", "3.4", "--entropy-max-pad", "128"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_padding_target_permil, Some(3400));
                assert_eq!(config.groups[0].actions.entropy_padding_max, 128);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_shannon() {
        let args: Vec<String> =
            ["--entropy-mode", "shannon", "--shannon-target", "7.92"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Shannon);
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, Some(7920));
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_combined() {
        let args: Vec<String> = ["--entropy-mode", "combined"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Combined);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_auto_as_combined() {
        let args: Vec<String> = ["--entropy-mode", "auto"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Combined);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_popcount() {
        let args: Vec<String> = ["--entropy-mode", "popcount"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Popcount);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_rejects_invalid_entropy_mode() {
        let args: Vec<String> = ["--entropy-mode", "invalid"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err());
    }

    #[test]
    fn cli_rejects_shannon_target_out_of_range() {
        // Above 8.0
        let args: Vec<String> = ["--shannon-target", "9.0"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err());

        // Negative
        let args: Vec<String> = ["--shannon-target", "-1.0"].iter().map(ToString::to_string).collect();
        assert!(parse_cli(&args, &startup).is_err());
    }

    #[test]
    fn cli_accepts_shannon_target_boundary_values() {
        // 0.0 is valid (extreme but allowed)
        let args: Vec<String> = ["--shannon-target", "0.0"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, Some(0));
            }
            _ => panic!("expected Run"),
        }

        // 8.0 is valid
        let args: Vec<String> = ["--shannon-target", "8.0"].iter().map(ToString::to_string).collect();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, Some(8000));
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_rejects_non_numeric_shannon_target() {
        let args: Vec<String> = ["--shannon-target", "abc"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err());
    }

    #[test]
    fn cli_entropy_mode_default_is_disabled() {
        // No entropy flags: mode should remain Disabled
        let args: Vec<String> = ["-p", "1080"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Disabled);
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, None);
            }
            _ => panic!("expected Run"),
        }
    }

    // --- ParseResult variants ---

    #[test]
    fn parse_cli_help_flag() {
        let args = vec!["--help".to_string()];
        let result = parse_cli(&args, &StartupEnv::default()).expect("parse cli");
        assert_eq!(result, ParseResult::Help);
    }

    #[test]
    fn parse_cli_version_flag() {
        let args = vec!["--version".to_string()];
        let result = parse_cli(&args, &StartupEnv::default()).expect("parse cli");
        assert_eq!(result, ParseResult::Version);
    }

    // --- New coverage gap tests ---

    #[test]
    fn parse_cli_empty_args_defaults() {
        let ParseResult::Run(config) = parse_cli(&[], &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        assert_eq!(config.network.listen.listen_port, 1080);
        // Empty args: parser adds a second group when all are "limited"
        assert_eq!(config.groups.len(), 2);
        assert!(!config.groups[0].is_actionable());
    }

    #[test]
    fn parse_cli_port_flag() {
        let args = vec!["-p".to_string(), "9090".to_string()];
        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        assert_eq!(config.network.listen.listen_port, 9090);
    }

    #[test]
    fn parse_cli_debug_flag() {
        let args = vec!["--debug".to_string(), "2".to_string()];
        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        assert_eq!(config.process.debug, 2);
    }

    #[test]
    fn parse_cli_multiple_desync_modes() {
        let args = vec!["--split".to_string(), "host+1".to_string(), "--fake".to_string(), "midsld".to_string()];
        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        assert_eq!(config.groups[0].actions.tcp_chain.len(), 2);
        assert_eq!(config.groups[0].actions.tcp_chain[0].kind, TcpChainStepKind::Split);
        assert_eq!(config.groups[0].actions.tcp_chain[1].kind, TcpChainStepKind::Fake);
    }

    #[test]
    fn parse_cli_host_autolearn_flags() {
        let args = vec![
            "--host-autolearn".to_string(),
            "--host-autolearn-penalty-ttl".to_string(),
            "3600".to_string(),
            "--host-autolearn-max-hosts".to_string(),
            "256".to_string(),
        ];
        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        assert!(config.host_autolearn.enabled);
        assert_eq!(config.host_autolearn.penalty_ttl_secs, 3600);
        assert_eq!(config.host_autolearn.max_hosts, 256);
        // store_path should have default when enabled
        assert!(config.host_autolearn.store_path.is_some());
    }

    #[test]
    fn parse_cli_oob_data_flag() {
        // oob-data uses data_from_str, so hex escape syntax is \x42
        let args = vec!["--oob".to_string(), "host+1".to_string(), "--oob-data".to_string(), "\\x42".to_string()];
        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        assert_eq!(config.groups[0].actions.oob_data, Some(0x42));
    }
}
