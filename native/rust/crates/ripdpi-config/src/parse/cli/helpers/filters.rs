use ipnet::IpNet;
use ripdpi_packets::ip::parse_cidr;

use crate::{ConfigError, FilterSet};

use super::super::super::fake_profiles::lower_host_char;

pub fn parse_hosts_spec(spec: &str) -> Result<Vec<String>, ConfigError> {
    let hosts = parse_host_filter_spec(spec)?.hosts;
    if !spec.trim().is_empty() && hosts.is_empty() {
        return Err(ConfigError::invalid("--hosts", Some(spec)));
    }
    Ok(hosts)
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
    if !spec.trim().is_empty()
        && filters.hosts.is_empty()
        && filters.geoip_countries.is_empty()
        && filters.geosite_categories.is_empty()
    {
        return Err(ConfigError::invalid("--hosts", Some(spec)));
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

fn parse_ip_token(token: &str) -> Result<IpNet, ConfigError> {
    parse_cidr(token, Some((32, 128)))
        .map(|network| network.trunc())
        .map_err(|_| ConfigError::invalid("--ipset", Some(token)))
}

pub fn parse_ipset_spec(spec: &str) -> Result<Vec<IpNet>, ConfigError> {
    let mut out = Vec::new();
    for token in spec.split_whitespace() {
        out.push(parse_ip_token(token)?);
    }
    Ok(out)
}
