use std::net::IpAddr;
use std::str::FromStr;

use crate::{Cidr, ConfigError, FilterSet};

use super::super::super::fake_profiles::lower_host_char;

pub fn parse_hosts_spec(spec: &str) -> Result<Vec<String>, ConfigError> {
    Ok(parse_host_filter_spec(spec)?.hosts)
}

pub fn parse_host_filter_spec(spec: &str) -> Result<FilterSet, ConfigError> {
    let mut filters = FilterSet::default();
    for token in spec.split_whitespace() {
        if let Some(country) = token.strip_prefix("geoip:") {
            filters.geoip_countries.push(normalize_geo_token(country, "geoip")?);
            continue;
        }
        if let Some(category) = token.strip_prefix("geosite:") {
            filters.geosite_categories.push(normalize_geo_token(category, "geosite")?);
            continue;
        }
        if let Some(host) = normalize_host_token(token) {
            filters.hosts.push(host);
        }
    }
    Ok(filters)
}

fn normalize_host_token(token: &str) -> Option<String> {
    let mut normalized = String::with_capacity(token.len());
    for ch in token.chars() {
        normalized.push(lower_host_char(ch)?);
    }
    (!normalized.is_empty()).then_some(normalized)
}

fn normalize_geo_token(token: &str, prefix: &str) -> Result<String, ConfigError> {
    let normalized = token.trim().to_ascii_lowercase();
    if normalized.is_empty()
        || !normalized.chars().all(|ch| ch.is_ascii_lowercase() || ch.is_ascii_digit() || matches!(ch, '-' | '_'))
    {
        return Err(ConfigError::invalid("--hosts", Some(format!("{prefix}:{token}"))));
    }
    Ok(normalized)
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
