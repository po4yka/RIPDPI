use std::io;
use std::net::{IpAddr, SocketAddr};

use ripdpi_dns_resolver::{EncryptedDnsResolver, extract_ip_answers};

use crate::doh_resolvers::{OWNED_FETCH_DOH_RESOLVER, merge_family_answers, owned_fetch_encrypted_resolver};

const DNS_RECORD_TYPE_A: u16 = 1;
const DNS_RECORD_TYPE_AAAA: u16 = 28;

pub(crate) async fn resolve_connect_targets(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }

    let resolver = owned_fetch_encrypted_resolver(&OWNED_FETCH_DOH_RESOLVER)?;
    let ipv4 = encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_A).await;
    let ipv6 = encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_AAAA).await;
    let targets = merge_family_answers(ipv4, ipv6)?;
    if targets.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("encrypted DNS resolved no addresses for {host}:{port}"),
        ));
    }
    Ok(targets)
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
