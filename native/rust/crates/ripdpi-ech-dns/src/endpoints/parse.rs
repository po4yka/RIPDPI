use std::net::IpAddr;

use ripdpi_dns_resolver::EncryptedDnsProtocol;
use url::{Host, Url};

pub fn encrypted_dns_protocol(value: Option<&str>) -> EncryptedDnsProtocol {
    match value.unwrap_or_default().trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        "doq" => EncryptedDnsProtocol::Doq,
        _ => EncryptedDnsProtocol::Doh,
    }
}

pub fn parse_url_host(value: &str) -> Option<String> {
    let trimmed = value.trim();
    let (_, authority) = trimmed.split_once("://")?;
    // Keep malformed authorities invalid instead of accepting WHATWG slash repair.
    if authority.is_empty()
        || authority.starts_with('/')
        || trimmed.contains('\\')
        || trimmed.chars().any(char::is_control)
    {
        return None;
    }
    let url = Url::parse(trimmed).ok()?;
    if !matches!(url.scheme(), "http" | "https") {
        return None;
    }
    url.host().map(|host| match host {
        Host::Domain(domain) => domain.to_string(),
        Host::Ipv4(address) => address.to_string(),
        Host::Ipv6(address) => address.to_string(),
    })
}

pub fn parse_bootstrap_ips(values: &[String]) -> Result<Vec<IpAddr>, String> {
    values.iter().map(|value| value.parse::<IpAddr>().map_err(|err| err.to_string())).collect()
}
