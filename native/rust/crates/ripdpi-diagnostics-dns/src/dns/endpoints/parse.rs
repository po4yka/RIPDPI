use std::net::IpAddr;

use ripdpi_dns_resolver::EncryptedDnsProtocol;

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
    let (_, remainder) = trimmed.split_once("://")?;
    let authority = remainder.split('/').next()?;
    if authority.is_empty() {
        return None;
    }
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, suffix)| suffix);
    if host_port.starts_with('[') {
        let end = host_port.find(']')?;
        return Some(host_port[1..end].to_string());
    }
    host_port.split(':').next().map(ToOwned::to_owned)
}

pub fn parse_bootstrap_ips(values: &[String]) -> Result<Vec<IpAddr>, String> {
    values.iter().map(|value| value.parse::<IpAddr>().map_err(|err| err.to_string())).collect()
}
