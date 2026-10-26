use std::io;
use std::net::{IpAddr, SocketAddr};

use ripdpi_dns_resolver::{
    EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport, extract_ip_answers,
};

use crate::socket_protection::owned_fetch_dns_connect_hooks;

const DNS_RECORD_TYPE_A: u16 = 1;
const DNS_RECORD_TYPE_AAAA: u16 = 28;

struct OwnedFetchDohResolver {
    resolver_id: &'static str,
    host: &'static str,
    url: &'static str,
    bootstrap_ips: &'static [&'static str],
}

/// DoH resolvers tried in order. The second is used only when the first fails
/// or returns no address, so one blocked resolver does not break every fetch.
const OWNED_FETCH_DOH_RESOLVERS: &[OwnedFetchDohResolver] = &[
    OwnedFetchDohResolver {
        resolver_id: "adguard",
        host: "dns.adguard-dns.com",
        url: "https://dns.adguard-dns.com/dns-query",
        bootstrap_ips: &["94.140.14.14", "94.140.15.15"],
    },
    OwnedFetchDohResolver {
        resolver_id: "quad9",
        host: "dns.quad9.net",
        url: "https://dns.quad9.net/dns-query",
        bootstrap_ips: &["9.9.9.9", "149.112.112.112"],
    },
];

pub(crate) async fn resolve_connect_targets(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }

    let mut last_error = None;
    for endpoint in OWNED_FETCH_DOH_RESOLVERS {
        let resolver = owned_fetch_encrypted_resolver(endpoint)?;
        let ipv4 = encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_A).await;
        let ipv6 = encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_AAAA).await;
        match merge_family_answers(ipv4, ipv6) {
            Ok(targets) if !targets.is_empty() => return Ok(targets),
            Ok(_) => {
                last_error = Some(io::Error::new(
                    io::ErrorKind::AddrNotAvailable,
                    format!("encrypted DNS resolved no addresses for {host}:{port}"),
                ));
            }
            Err(error) => last_error = Some(error),
        }
    }
    Err(last_error.unwrap_or_else(|| io::Error::other(format!("no encrypted DNS resolver for {host}:{port}"))))
}

/// Combine A and AAAA answers. One family failing is not fatal when the other
/// answered (RFC 8305 section 3). Families alternate, IPv4 first, so an
/// unreachable family cannot hold every early connect attempt.
fn merge_family_answers(
    ipv4: io::Result<Vec<SocketAddr>>,
    ipv6: io::Result<Vec<SocketAddr>>,
) -> io::Result<Vec<SocketAddr>> {
    let (ipv4, ipv6) = match (ipv4, ipv6) {
        (Err(error), Err(_)) => return Err(error),
        (ipv4, ipv6) => (ipv4.unwrap_or_default(), ipv6.unwrap_or_default()),
    };
    let mut merged = Vec::with_capacity(ipv4.len() + ipv6.len());
    let (mut ipv4, mut ipv6) = (ipv4.into_iter(), ipv6.into_iter());
    loop {
        match (ipv4.next(), ipv6.next()) {
            (None, None) => return Ok(merged),
            (first, second) => merged.extend(first.into_iter().chain(second)),
        }
    }
}

fn owned_fetch_encrypted_resolver(endpoint: &OwnedFetchDohResolver) -> io::Result<EncryptedDnsResolver> {
    let bootstrap_ips = endpoint
        .bootstrap_ips
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
            resolver_id: Some(endpoint.resolver_id.to_string()),
            host: endpoint.host.to_string(),
            port: 443,
            tls_server_name: Some(endpoint.host.to_string()),
            bootstrap_ips,
            doh_url: Some(endpoint.url.to_string()),
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

#[cfg(test)]
mod tests {
    use super::*;

    fn addr(value: &str) -> SocketAddr {
        value.parse().expect("socket addr")
    }

    #[test]
    fn failed_aaaa_lookup_keeps_the_a_answers() {
        let merged = merge_family_answers(Ok(vec![addr("192.0.2.1:443")]), Err(io::Error::other("AAAA SERVFAIL")))
            .expect("A answers must survive an AAAA failure");
        assert_eq!(merged, vec![addr("192.0.2.1:443")]);
    }

    #[test]
    fn failed_a_lookup_keeps_the_aaaa_answers() {
        let merged = merge_family_answers(Err(io::Error::other("A timeout")), Ok(vec![addr("[2001:db8::1]:443")]))
            .expect("AAAA answers must survive an A failure");
        assert_eq!(merged, vec![addr("[2001:db8::1]:443")]);
    }

    #[test]
    fn both_failed_lookups_report_the_a_error() {
        let error = merge_family_answers(Err(io::Error::other("A timeout")), Err(io::Error::other("AAAA timeout")))
            .expect_err("no family answered");
        assert_eq!(error.to_string(), "A timeout");
    }

    #[test]
    fn answers_alternate_between_families() {
        let merged = merge_family_answers(
            Ok(vec![addr("192.0.2.1:443"), addr("192.0.2.2:443"), addr("192.0.2.3:443")]),
            Ok(vec![addr("[2001:db8::1]:443")]),
        )
        .expect("merged");
        assert_eq!(
            merged,
            vec![addr("192.0.2.1:443"), addr("[2001:db8::1]:443"), addr("192.0.2.2:443"), addr("192.0.2.3:443")]
        );
    }
}
