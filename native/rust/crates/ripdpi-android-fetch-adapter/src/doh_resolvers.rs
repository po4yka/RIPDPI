use std::io;
use std::net::{IpAddr, SocketAddr};

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport};

use crate::dns_connect::owned_fetch_dns_connect_hooks;

pub(crate) struct OwnedFetchDohResolver {
    resolver_id: &'static str,
    host: &'static str,
    url: &'static str,
    bootstrap_ips: &'static [&'static str],
}

/// The only DoH resolver for native fetches. There is deliberately no second
/// provider: a fallback would send every hostname to one more third party.
pub(crate) const OWNED_FETCH_DOH_RESOLVER: OwnedFetchDohResolver = OwnedFetchDohResolver {
    resolver_id: "adguard",
    host: "dns.adguard-dns.com",
    url: "https://dns.adguard-dns.com/dns-query",
    bootstrap_ips: &["94.140.14.14", "94.140.15.15"],
};

/// Combine A and AAAA answers. One family failing is not fatal when the other
/// answered (RFC 8305 section 3). Families alternate, IPv4 first, so an
/// unreachable family cannot hold every early connect attempt.
pub(crate) fn merge_family_answers(
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

pub(crate) fn owned_fetch_encrypted_resolver(endpoint: &OwnedFetchDohResolver) -> io::Result<EncryptedDnsResolver> {
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
