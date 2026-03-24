use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;

use ripdpi_packets::{
    IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
};

use crate::{
    Cidr, ConfigError, DesyncGroup, DesyncMode, ParseResult, RuntimeConfig, StartupEnv, TcpChainStep, TcpChainStepKind,
    UdpChainStep, UdpChainStepKind, UpstreamSocksConfig, AUTO_NOPOST, AUTO_RECONN, AUTO_SORT, DETECT_CONNECT,
    DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_QUIC_BREAKAGE, DETECT_RECONN,
    DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_ERR, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST,
    HOST_AUTOLEARN_DEFAULT_STORE_FILE,
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
                        Some('0' | '2') => {
                            config.auto_level |= AUTO_NOPOST;
                            if token.starts_with('2') {
                                config.auto_level |= AUTO_SORT;
                            }
                        }
                        Some('1') => {}
                        Some('3' | 's') => config.auto_level |= AUTO_SORT,
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
                    if token.starts_with("p=") {
                        let (_, pri) =
                            token.split_once('=').ok_or_else(|| ConfigError::invalid("--auto", Some(token)))?;
                        let pri = pri.parse::<f32>().map_err(|_| ConfigError::invalid("--auto", Some(token)))?;
                        if let Some(prev) = config.groups.get_mut(current_group_index - 1) {
                            prev.pri = pri as i32;
                        }
                        continue;
                    }
                    match parse_auto_detect_token(token) {
                        Some(bits) => group!().detect |= bits,
                        None => return Err(ConfigError::invalid("--auto", Some(token))),
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
                group!().parts.push(crate::PartSpec { mode, offset });
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
            "--auto-ttl" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().auto_ttl = Some(parse_auto_ttl_spec(value)?);
            }
            "-O" | "--fake-offset" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let expr = parse_offset_expr(value)?;
                if expr.base.is_adaptive() {
                    return Err(ConfigError::invalid(arg, Some(value)));
                }
                group!().fake_offset = Some(expr);
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
            "--fake-http-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().http_fake_profile = parse_http_fake_profile(value)?;
            }
            "--fake-tls-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().tls_fake_profile = parse_tls_fake_profile(value)?;
            }
            "--fake-udp-profile" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().udp_fake_profile = parse_udp_fake_profile(value)?;
            }
            "-e" | "--oob-data" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let bytes = super::fake_profiles::data_from_str(value)?;
                group!().oob_data = bytes.first().copied();
            }
            "-M" | "--mod-http" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                for token in value.split(',') {
                    match token.chars().next() {
                        Some('r') => group!().mod_http |= MH_SPACE,
                        Some('h') => group!().mod_http |= MH_HMIX,
                        Some('d') => group!().mod_http |= MH_DMIX,
                        Some('m') => group!().mod_http |= MH_METHODEOL,
                        Some('u') => group!().mod_http |= MH_UNIXEOL,
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
                    group!().udp_chain.push(UdpChainStep {
                        kind: UdpChainStepKind::FakeBurst,
                        count,
                        activation_filter: None,
                    });
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
                let range = parse_round_range_spec(value)?;
                group!().set_round_activation(Some(range));
            }
            "--payload-size-range" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let range = parse_payload_size_range_spec(value)?;
                let mut filter = group!().activation_filter.unwrap_or_default();
                filter.payload_size = Some(range);
                group!().set_activation_filter(filter);
            }
            "--stream-byte-range" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let range = parse_stream_byte_range_spec(value)?;
                let mut filter = group!().activation_filter.unwrap_or_default();
                filter.stream_bytes = Some(range);
                group!().set_activation_filter(filter);
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

    Ok(ParseResult::Run(Box::new(config)))
}
