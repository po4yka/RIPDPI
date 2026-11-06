use ipnet::Ipv4Net;
use ripdpi_packets::ip::parse_cidr;
use std::net::{IpAddr, Ipv4Addr};

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
    in_range("misc.max-session-count", config.misc.max_session_count, 1, 100_000)?;
    in_range("misc.connect-timeout", config.misc.connect_timeout, 1, MAX_TIMEOUT_MS)?;
    in_range("misc.tcp-read-write-timeout", config.misc.tcp_read_write_timeout, 1, MAX_TIMEOUT_MS)?;
    in_range("misc.udp-read-write-timeout", config.misc.udp_read_write_timeout, 1, MAX_TIMEOUT_MS)?;
    in_range("misc.limit-nofile", config.misc.limit_nofile, 64, 1_048_576)?;
    if !matches!(config.misc.uid_policy_mode.as_str(), "disarmed" | "allowlist" | "denylist") {
        return invalid("misc.uid-policy-mode", "must be disarmed, allowlist, or denylist");
    }
    if config.misc.uid_policy_uids.len() > 4_096 {
        return invalid("misc.uid-policy-uids", "must contain at most 4096 entries");
    }

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
        if Ipv4Net::with_netmask(Ipv4Addr::UNSPECIFIED, Ipv4Addr::from(mask)).is_err() {
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
        if let Some(protocol) = &mapdns.encrypted_dns_protocol
            && !matches!(protocol.trim().to_ascii_lowercase().as_str(), "dot" | "dnscrypt" | "doh" | "doq" | "odoh")
        {
            return invalid("mapdns.encrypted-dns-protocol", "must be dot, dnscrypt, doh, doq, or odoh");
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
    let network = parse_cidr(value, Some((24, 128)))
        .map_err(|error| ConfigError::InvalidValue { field, message: error.to_string() })?;
    if network.addr().is_ipv4() != ipv4 {
        return invalid(field, "address family does not match the tunnel field");
    }
    Ok(())
}

fn parse_ipv4(field: &'static str, value: &str) -> Result<Ipv4Addr, ConfigError> {
    value
        .parse()
        .map_err(|err| ConfigError::InvalidValue { field, message: format!("'{value}' is not an IPv4 address: {err}") })
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
