use std::net::SocketAddr;

use crate::{ConfigError, UpstreamSocksConfig};

mod host_autolearn;

use super::super::helpers::{next_value, parse_numeric_addr, parse_timeout, parse_ttl_byte, seconds_to_millis};
use super::super::state::CliState;

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

pub(super) fn handle(arg: &str, args: &[String], idx: &mut usize, state: &mut CliState) -> Result<bool, ConfigError> {
    if host_autolearn::handle(arg, args, idx, state)? {
        return Ok(true);
    }

    match arg {
        "-N" | "--no-domain" => state.config.network.resolve = false,
        "-X" => state.config.network.ipv6 = false,
        "-U" | "--no-udp" => state.config.network.udp = false,
        "-G" | "--http-connect" => state.config.network.http_connect = true,
        "--mixed" => state.config.network.mixed = true,
        "-E" | "--transparent" => state.config.network.transparent = true,
        "-D" | "--daemon" => state.config.process.daemonize = true,
        "-w" | "--pidfile" => {
            let value = next_value(args, idx, arg)?;
            state.config.process.pid_file = Some(value.to_owned());
        }
        "-F" | "--tfo" => state.config.network.tfo = true,
        "-Z" | "--wait-send" => state.config.timeouts.wait_send = true,
        "-i" | "--ip" => {
            let value = next_value(args, idx, arg)?;
            let (ip, port) = parse_numeric_addr(value)?;
            state.config.network.listen.listen_ip = ip;
            if let Some(port) = port {
                state.config.network.listen.listen_port = port;
            }
        }
        "-p" | "--port" => {
            let value = next_value(args, idx, arg)?;
            let port = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if port == 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.network.listen.listen_port = port;
        }
        "-I" | "--conn-ip" => {
            let value = next_value(args, idx, arg)?;
            let (ip, _) = parse_numeric_addr(value)?;
            state.config.network.listen.bind_ip = ip;
        }
        "-b" | "--buf-size" => {
            let value = next_value(args, idx, arg)?;
            let size = value.parse::<usize>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if size == 0 || size >= (i32::MAX as usize) / 4 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.network.buffer_size = size;
        }
        "-c" | "--max-conn" => {
            let value = next_value(args, idx, arg)?;
            let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if count <= 0 || count >= (0xffff / 2) {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.network.max_open = count;
        }
        "-x" | "--debug" => {
            let value = next_value(args, idx, arg)?;
            let level = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if level < 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.process.debug = level;
        }
        "-T" | "--timeout" => {
            parse_timeout(next_value(args, idx, arg)?, &mut state.config)?;
        }
        "-g" | "--def-ttl" => {
            let value = next_value(args, idx, arg)?;
            state.config.network.default_ttl = parse_ttl_byte(arg, value)?;
            state.config.network.custom_ttl = true;
        }
        "-W" | "--await-int" => {
            parse_value_into!(args, idx, arg, state.config.timeouts.await_interval, i32);
        }
        "-C" | "--to-socks5" => {
            let value = next_value(args, idx, arg)?;
            let (ip, port) = parse_numeric_addr(value)?;
            let port = port.ok_or_else(|| ConfigError::invalid(arg, Some(value)))?;
            state.group().policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::new(ip, port) });
            state.config.network.delay_conn = true;
        }
        "--connect-timeout" => {
            parse_value_into!(args, idx, arg, state.config.timeouts.connect_timeout_ms, secs_to_ms);
        }
        "-P" | "--protect-path" => {
            state.config.process.protect_path = Some(next_value(args, idx, arg)?.to_owned());
        }
        "--geoip-db" => {
            state.config.process.geoip_db_path = Some(next_value(args, idx, arg)?.to_owned());
        }
        "--geosite-db" => {
            state.config.process.geosite_db_path = Some(next_value(args, idx, arg)?.to_owned());
        }
        "--ws-tunnel-fake-sni" => {
            state.config.adaptive.ws_tunnel_fake_sni = Some(next_value(args, idx, arg)?.to_string());
        }
        "--ws-tunnel-allow-insecure-sni" => state.config.adaptive.ws_tunnel_allow_insecure_sni = true,
        "--strategy-evolution" => state.config.adaptive.strategy_evolution = true,
        "--evolution-epsilon" => {
            let value = next_value(args, idx, arg)?;
            let f = value.parse::<f64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            state.config.adaptive.evolution_epsilon_permil = (f * 1000.0).clamp(0.0, 1_000.0) as u32;
        }
        "--evolution-experiment-ttl-ms" => {
            parse_value_into!(args, idx, arg, state.config.adaptive.evolution_experiment_ttl_ms, u64);
        }
        "--evolution-decay-half-life-ms" => {
            parse_value_into!(args, idx, arg, state.config.adaptive.evolution_decay_half_life_ms, u64);
        }
        "--evolution-cooldown-after-failures" => {
            parse_value_into!(args, idx, arg, state.config.adaptive.evolution_cooldown_after_failures, u32);
        }
        "--evolution-cooldown-ms" => {
            parse_value_into!(args, idx, arg, state.config.adaptive.evolution_cooldown_ms, u64);
        }
        "--freeze-window" => {
            parse_value_into!(args, idx, arg, state.config.timeouts.freeze_window_ms, secs_to_ms);
        }
        "--freeze-min-bytes" => {
            parse_value_into!(args, idx, arg, state.config.timeouts.freeze_min_bytes, u32);
        }
        "--freeze-max-stalls" => {
            parse_value_into!(args, idx, arg, state.config.timeouts.freeze_max_stalls, u32);
        }
        _ => return Ok(false),
    }

    Ok(true)
}
