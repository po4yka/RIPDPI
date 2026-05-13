use ripdpi_packets::{IS_HTTP, IS_HTTPS, IS_IPV4, IS_TCP, IS_UDP};

use crate::ConfigError;

mod auto;

use super::super::helpers::{next_value, parse_host_filter_spec, parse_ipset_spec};
use super::super::state::CliState;
use crate::parse::fake_profiles::file_or_inline_bytes;
use crate::parse::offsets::{parse_payload_size_range_spec, parse_round_range_spec, parse_stream_byte_range_spec};
use auto::{handle_auto, handle_auto_mode};

pub(super) fn handle(arg: &str, args: &[String], idx: &mut usize, state: &mut CliState) -> Result<bool, ConfigError> {
    match arg {
        "-y" | "--cache-file" => {
            state.group().policy.cache_file = Some(next_value(args, idx, arg)?.to_owned());
        }
        "-L" | "--auto-mode" => {
            handle_auto_mode(arg, args, idx, state)?;
        }
        "-A" | "--auto" => {
            handle_auto(arg, args, idx, state)?;
        }
        "-u" | "--cache-ttl" => {
            let value = next_value(args, idx, arg)?;
            let ttl = value.parse::<i64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if ttl <= 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            if state.config.adaptive.cache_ttl == 0 {
                state.config.adaptive.cache_ttl = ttl;
            }
            state.group().policy.cache_ttl = ttl;
        }
        "--cache-merge" => {
            let value = next_value(args, idx, arg)?;
            let merge = value.parse::<u8>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if merge > 32 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.adaptive.cache_prefix = 32 - merge;
        }
        "-K" | "--proto" => {
            let value = next_value(args, idx, arg)?;
            for token in value.split(',') {
                match token.chars().next() {
                    Some('t') => state.group().matches.proto |= IS_TCP | IS_HTTPS,
                    Some('h') => state.group().matches.proto |= IS_TCP | IS_HTTP,
                    Some('u') => state.group().matches.proto |= IS_UDP,
                    Some('i') => state.group().matches.proto |= IS_IPV4,
                    _ => return Err(ConfigError::invalid(arg, Some(value))),
                }
            }
        }
        "-H" | "--hosts" => {
            let value = next_value(args, idx, arg)?;
            let data = file_or_inline_bytes(value)?;
            let text = String::from_utf8_lossy(&data);
            let parsed = parse_host_filter_spec(&text)?;
            state.group().matches.filters.hosts.extend(parsed.hosts);
            state.group().matches.filters.geoip_countries.extend(parsed.geoip_countries);
            state.group().matches.filters.geosite_categories.extend(parsed.geosite_categories);
        }
        "-j" | "--ipset" => {
            let value = next_value(args, idx, arg)?;
            let data = file_or_inline_bytes(value)?;
            let text = String::from_utf8_lossy(&data);
            state.group().matches.filters.ipset.extend(parse_ipset_spec(&text)?);
        }
        "-V" | "--pf" => {
            let value = next_value(args, idx, arg)?;
            let (start, end) = match value.split_once('-') {
                Some((start, end)) => (start, end),
                None => (value, value),
            };
            let start = start.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            let end = end.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if start == 0 || end == 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.group().matches.port_filter = Some((start, end));
        }
        "-R" | "--round" => {
            let value = next_value(args, idx, arg)?;
            let range = parse_round_range_spec(value)?;
            state.group().set_round_activation(Some(range));
        }
        "--payload-size-range" => {
            let value = next_value(args, idx, arg)?;
            let range = parse_payload_size_range_spec(value)?;
            let mut filter = state.group().activation_filter().unwrap_or_default();
            filter.payload_size = Some(range);
            state.group().set_activation_filter(filter);
        }
        "--stream-byte-range" => {
            let value = next_value(args, idx, arg)?;
            let range = parse_stream_byte_range_spec(value)?;
            let mut filter = state.group().activation_filter().unwrap_or_default();
            filter.stream_bytes = Some(range);
            state.group().set_activation_filter(filter);
        }
        "--comment" => {
            state.group().policy.label = next_value(args, idx, arg)?.to_owned();
        }
        _ => return Ok(false),
    }

    Ok(true)
}
