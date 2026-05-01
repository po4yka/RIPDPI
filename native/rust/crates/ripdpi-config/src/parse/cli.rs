use std::net::{IpAddr, SocketAddr};

use ripdpi_packets::{
    IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
};

use crate::{
    ConfigError, DesyncMode, EntropyMode, ParseResult, RuntimeConfig, SeqOverlapFakeMode, StartupEnv, TcpChainStep,
    TcpChainStepKind, UdpChainStep, UdpChainStepKind, UpstreamSocksConfig, AUTO_NOPOST, AUTO_RECONN, AUTO_SORT,
    HOST_AUTOLEARN_DEFAULT_STORE_FILE,
};

mod helpers;
#[cfg(test)]
mod tests;

pub use helpers::{parse_hosts_spec, parse_ipset_spec};

use helpers::{
    add_group, next_value, parse_auto_detect_token, parse_numeric_addr, parse_timeout, parse_ttl_byte, parse_wsize,
    seconds_to_millis, seqovl_step_mut, split_plugin_options,
};

use super::fake_profiles::{
    apply_fake_tls_mod_token, file_or_inline_bytes, normalize_quic_fake_host, parse_http_fake_profile,
    parse_quic_fake_profile, parse_tls_fake_profile, parse_udp_fake_profile,
};
use super::offsets::{
    parse_auto_ttl_spec, parse_offset_expr, parse_payload_size_range_spec, parse_round_range_spec,
    parse_stream_byte_range_spec,
};

macro_rules! parse_value_into {
    ($args:expr, $idx:expr, $arg:expr, $target:expr, secs_to_ms) => {{
        let value = next_value($args, $idx, $arg)?;
        $target = seconds_to_millis(value).map_err(|_| ConfigError::invalid($arg, Some(value)))?;
    }};
    ($args:expr, $idx:expr, $arg:expr, $target:expr, $ty:ty) => {{
        let value = next_value($args, $idx, $arg)?;
        $target = value.parse::<$ty>().map_err(|_| ConfigError::invalid($arg, Some(value)))?;
    }};
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
            "--wsize" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                group!().actions.wsize = Some(parse_wsize(arg, value)?);
            }
            "--strip-timestamps" => group!().actions.strip_timestamps = true,
            "--entropy-target" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let f = value.parse::<f32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                group!().actions.entropy_padding_target_permil = Some((f * 1000.0) as u32);
            }
            "--entropy-max-pad" => {
                parse_value_into!(&effective_args, &mut idx, arg, group!().actions.entropy_padding_max, u32);
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
                group!().policy.cache_file = Some(next_value(&effective_args, &mut idx, arg)?.to_owned());
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
                parse_timeout(next_value(&effective_args, &mut idx, arg)?, &mut config)?;
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
                seqovl_step_mut(group!())
                    .ok_or_else(|| ConfigError::invalid(arg, Some("missing --seqovl")))?
                    .overlap_size = overlap;
            }
            "--seqovl-fake-mode" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                seqovl_step_mut(group!())
                    .ok_or_else(|| ConfigError::invalid(arg, Some("missing --seqovl")))?
                    .seqovl_fake_mode = match value.trim().to_ascii_lowercase().as_str() {
                    "profile" => SeqOverlapFakeMode::Profile,
                    "rand" => SeqOverlapFakeMode::Rand,
                    _ => return Err(ConfigError::invalid(arg, Some(value))),
                };
            }
            "-t" | "--ttl" => {
                group!().actions.ttl = Some(parse_ttl_byte(arg, next_value(&effective_args, &mut idx, arg)?)?);
            }
            "--auto-ttl" => {
                group!().actions.auto_ttl = Some(parse_auto_ttl_spec(next_value(&effective_args, &mut idx, arg)?)?);
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
                group!().actions.fake_sni_list.push(next_value(&effective_args, &mut idx, arg)?.to_owned());
            }
            "-l" | "--fake-data" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                if group!().actions.fake_data.is_none() {
                    group!().actions.fake_data = Some(file_or_inline_bytes(value)?);
                }
            }
            "--fake-http-profile" => {
                group!().actions.http_fake_profile =
                    parse_http_fake_profile(next_value(&effective_args, &mut idx, arg)?)?;
            }
            "--fake-tls-profile" => {
                group!().actions.tls_fake_profile =
                    parse_tls_fake_profile(next_value(&effective_args, &mut idx, arg)?)?;
            }
            "--fake-udp-profile" => {
                group!().actions.udp_fake_profile =
                    parse_udp_fake_profile(next_value(&effective_args, &mut idx, arg)?)?;
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
                group!().actions.tlsminor = Some(parse_ttl_byte(arg, value)?);
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
                group!().actions.quic_fake_profile =
                    parse_quic_fake_profile(next_value(&effective_args, &mut idx, arg)?)?;
            }
            "--fake-quic-host" => {
                group!().actions.quic_fake_host =
                    Some(normalize_quic_fake_host(next_value(&effective_args, &mut idx, arg)?)?);
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
                config.network.default_ttl = parse_ttl_byte(arg, value)?;
                config.network.custom_ttl = true;
            }
            "-W" | "--await-int" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.timeouts.await_interval, i32);
            }
            "-C" | "--to-socks5" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let (ip, port) = parse_numeric_addr(value)?;
                let port = port.ok_or_else(|| ConfigError::invalid(arg, Some(value)))?;
                group!().policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::new(ip, port) });
                config.network.delay_conn = true;
            }
            "--connect-timeout" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.timeouts.connect_timeout_ms, secs_to_ms);
            }
            "-P" | "--protect-path" => {
                config.process.protect_path = Some(next_value(&effective_args, &mut idx, arg)?.to_owned());
            }
            "--comment" => {
                group!().policy.label = next_value(&effective_args, &mut idx, arg)?.to_owned();
            }
            "--ws-tunnel-fake-sni" => {
                config.adaptive.ws_tunnel_fake_sni = Some(next_value(&effective_args, &mut idx, arg)?.to_string());
            }
            "--strategy-evolution" => config.adaptive.strategy_evolution = true,
            "--evolution-epsilon" => {
                let value = next_value(&effective_args, &mut idx, arg)?;
                let f = value.parse::<f64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
                config.adaptive.evolution_epsilon_permil = (f * 1000.0).clamp(0.0, 1_000.0) as u32;
            }
            "--evolution-experiment-ttl-ms" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.adaptive.evolution_experiment_ttl_ms, u64);
            }
            "--evolution-decay-half-life-ms" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.adaptive.evolution_decay_half_life_ms, u64);
            }
            "--evolution-cooldown-after-failures" => {
                parse_value_into!(
                    &effective_args,
                    &mut idx,
                    arg,
                    config.adaptive.evolution_cooldown_after_failures,
                    u32
                );
            }
            "--evolution-cooldown-ms" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.adaptive.evolution_cooldown_ms, u64);
            }
            "--freeze-window" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.timeouts.freeze_window_ms, secs_to_ms);
            }
            "--freeze-min-bytes" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.timeouts.freeze_min_bytes, u32);
            }
            "--freeze-max-stalls" => {
                parse_value_into!(&effective_args, &mut idx, arg, config.timeouts.freeze_max_stalls, u32);
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
