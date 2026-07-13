use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use crate::Config;
use crate::errors::ConfigError;
use crate::raw::{RawConfig, SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION};

const MAX_ALLOCATION_SIZE: u32 = 16_777_216;
const MAX_DNS_CACHE_SIZE: u32 = 65_536;
const MAX_TIMEOUT_MS: u32 = 300_000;

pub(crate) fn validate_envelope(raw: &RawConfig) -> Result<(), ConfigError> {
    // Reject any payload carrying an unsupported `schemaVersion` envelope value.
    // Runs on every parse path (`from_str` / `from_file`) via `Config::from_raw`.
    if raw.schema_version != SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION {
        return Err(ConfigError::UnsupportedSchemaVersion { found: raw.schema_version });
    }
    match (&raw.socks5.username, &raw.socks5.password) {
        (Some(_), None) | (None, Some(_)) => Err(ConfigError::MismatchedCredentials),
        _ => Ok(()),
    }
}

pub(crate) fn validate_values(config: &Config) -> Result<(), ConfigError> {
    non_blank("tunnel.name", &config.tunnel.name)?;
    in_range("tunnel.mtu", config.tunnel.mtu, 68, 65_535)?;
    validate_tunnel_ip("tunnel.ipv4", config.tunnel.ipv4.as_deref(), true)?;
    validate_tunnel_ip("tunnel.ipv6", config.tunnel.ipv6.as_deref(), false)?;

    non_blank("socks5.address", &config.socks5.address)?;
    non_zero("socks5.port", u32::from(config.socks5.port))?;
    match (&config.socks5.username, &config.socks5.password) {
        (Some(_), None) | (None, Some(_)) => return Err(ConfigError::MismatchedCredentials),
        _ => {}
    }

    in_range("misc.task-stack-size", config.misc.task_stack_size, 8_192, MAX_ALLOCATION_SIZE)?;
    in_range("misc.tcp-buffer-size", config.misc.tcp_buffer_size, 1, MAX_ALLOCATION_SIZE)?;
    in_range("misc.udp-recv-buffer-size", config.misc.udp_recv_buffer_size, 1, MAX_ALLOCATION_SIZE)?;
    in_range("misc.udp-copy-buffer-nums", config.misc.udp_copy_buffer_nums, 1, 4_096)?;
    if config.misc.max_session_count != 0 {
        in_range("misc.max-session-count", config.misc.max_session_count, 1, 100_000)?;
    }
    in_range("misc.connect-timeout", config.misc.connect_timeout, 1, MAX_TIMEOUT_MS)?;
    in_range("misc.tcp-read-write-timeout", config.misc.tcp_read_write_timeout, 1, MAX_TIMEOUT_MS)?;
    in_range("misc.udp-read-write-timeout", config.misc.udp_read_write_timeout, 1, MAX_TIMEOUT_MS)?;
    in_range("misc.limit-nofile", config.misc.limit_nofile, 64, 1_048_576)?;

    if let Some(mapdns) = &config.mapdns {
        parse_ipv4("mapdns.address", &mapdns.address)?;
        non_zero("mapdns.port", u32::from(mapdns.port))?;
        match &mapdns.network {
            Some(value) => parse_ipv4("mapdns.network", value)?,
            None => parse_ipv4("mapdns.address", &mapdns.address)?,
        };
        let mask = match &mapdns.netmask {
            Some(value) => u32::from(parse_ipv4("mapdns.netmask", value)?),
            None => u32::from(Ipv4Addr::new(255, 254, 0, 0)),
        };
        if !is_contiguous_mask(mask) {
            return invalid("mapdns.netmask", "must be a contiguous IPv4 netmask");
        }
        in_range("mapdns.cache-size", mapdns.cache_size, 1, MAX_DNS_CACHE_SIZE)?;
        let addressable = !mask;
        if mapdns.cache_size > addressable {
            return invalid(
                "mapdns.cache-size",
                format!("must not exceed the {addressable} allocatable addresses in mapdns.network"),
            );
        }
        if let Some(port) = mapdns.encrypted_dns_port {
            non_zero("mapdns.encrypted-dns-port", u32::from(port))?;
        }
        for bootstrap in &mapdns.encrypted_dns_bootstrap_ips {
            bootstrap.parse::<IpAddr>().map_err(|err| ConfigError::InvalidValue {
                field: "mapdns.encrypted-dns-bootstrap-ips",
                message: format!("'{bootstrap}' is not an IP address: {err}"),
            })?;
        }
        in_range("mapdns.dns-query-timeout-ms", mapdns.dns_query_timeout_ms, 1, MAX_TIMEOUT_MS)?;
    }

    Ok(())
}

fn validate_tunnel_ip(field: &'static str, value: Option<&str>, ipv4: bool) -> Result<(), ConfigError> {
    let Some(value) = value else { return Ok(()) };
    let (address, prefix) = value.split_once('/').map_or((value, None), |(address, prefix)| (address, Some(prefix)));
    if ipv4 {
        address.parse::<Ipv4Addr>().map_err(|err| ConfigError::InvalidValue {
            field,
            message: format!("'{address}' is not an IPv4 address: {err}"),
        })?;
    } else {
        address.parse::<Ipv6Addr>().map_err(|err| ConfigError::InvalidValue {
            field,
            message: format!("'{address}' is not an IPv6 address: {err}"),
        })?;
    }
    if let Some(prefix) = prefix {
        let max = if ipv4 { 32 } else { 128 };
        let parsed = prefix
            .parse::<u8>()
            .map_err(|err| ConfigError::InvalidValue { field, message: format!("invalid prefix '{prefix}': {err}") })?;
        if parsed > max {
            return invalid(field, format!("prefix must be between 0 and {max}, got {parsed}"));
        }
    }
    Ok(())
}

fn parse_ipv4(field: &'static str, value: &str) -> Result<Ipv4Addr, ConfigError> {
    value
        .parse()
        .map_err(|err| ConfigError::InvalidValue { field, message: format!("'{value}' is not an IPv4 address: {err}") })
}

fn is_contiguous_mask(mask: u32) -> bool {
    let inverted = !mask;
    inverted == 0 || inverted & inverted.wrapping_add(1) == 0
}

fn non_blank(field: &'static str, value: &str) -> Result<(), ConfigError> {
    if value.trim().is_empty() { invalid(field, "must not be blank") } else { Ok(()) }
}

fn non_zero(field: &'static str, value: u32) -> Result<(), ConfigError> {
    if value == 0 { invalid(field, "must be non-zero") } else { Ok(()) }
}

fn in_range(field: &'static str, value: u32, min: u32, max: u32) -> Result<(), ConfigError> {
    if (min..=max).contains(&value) {
        Ok(())
    } else {
        invalid(field, format!("must be between {min} and {max}, got {value}"))
    }
}

fn invalid<T>(field: &'static str, message: impl Into<String>) -> Result<T, ConfigError> {
    Err(ConfigError::InvalidValue { field, message: message.into() })
}
