use std::io;
use std::net::{IpAddr, SocketAddr};

use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};

use crate::socket_protection::owned_fetch_dns_connect_hooks;

const DNS_RECORD_TYPE_A: u16 = 1;
const OWNED_FETCH_DOH_HOST: &str = "dns.adguard-dns.com";
const OWNED_FETCH_DOH_URL: &str = "https://dns.adguard-dns.com/dns-query";
const OWNED_FETCH_DOH_BOOTSTRAP_IPS: &[&str] = &["94.140.14.14", "94.140.15.15"];

pub(crate) async fn resolve_connect_targets(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }

    let resolver = owned_fetch_encrypted_resolver()?;
    let mut targets = encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_A).await?;
    targets.extend(encrypted_dns_targets(&resolver, host, port, 28).await?);
    if targets.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("encrypted DNS resolved no addresses for {host}:{port}"),
        ));
    }
    Ok(targets)
}

fn owned_fetch_encrypted_resolver() -> io::Result<EncryptedDnsResolver> {
    let bootstrap_ips = OWNED_FETCH_DOH_BOOTSTRAP_IPS
        .iter()
        .map(|value| {
            value.parse::<IpAddr>().map_err(|error| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("invalid owned fetch DoH bootstrap IP {value}: {error}"),
                )
            })
        })
        .collect::<io::Result<Vec<_>>>()?;
    EncryptedDnsResolver::with_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("adguard".to_string()),
            host: OWNED_FETCH_DOH_HOST.to_string(),
            port: 443,
            tls_server_name: Some(OWNED_FETCH_DOH_HOST.to_string()),
            bootstrap_ips,
            doh_url: Some(OWNED_FETCH_DOH_URL.to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        owned_fetch_dns_connect_hooks(),
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("build owned fetch resolver: {error}")))
}

async fn encrypted_dns_targets(
    resolver: &EncryptedDnsResolver,
    host: &str,
    port: u16,
    record_type: u16,
) -> io::Result<Vec<SocketAddr>> {
    let query = build_dns_query(host, record_type, dns_query_id())?;
    let response = resolver
        .exchange(&query)
        .await
        .map_err(|error| io::Error::other(format!("encrypted DNS resolve {host}: {error}")))?;
    let answers =
        extract_ip_answers(&response).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()))?;
    Ok(answers
        .into_iter()
        .filter_map(|answer| answer.parse::<IpAddr>().ok())
        .map(|ip| SocketAddr::new(ip, port))
        .collect())
}

fn build_dns_query(domain: &str, record_type: u16, query_id: u16) -> io::Result<Vec<u8>> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.trim_end_matches('.').split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("invalid DNS name: {domain}")));
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(record_type.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

fn dns_query_id() -> u16 {
    use std::time::{SystemTime, UNIX_EPOCH};

    (((SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos() as u64) & 0xffff) as u16).max(1)
}
