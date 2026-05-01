use std::net::IpAddr;

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

/// Returns a short list of fallback encrypted DNS endpoints to try when the
/// primary resolver is blocked. The list intentionally uses providers from
/// different ASNs and geographies to maximise the chance that at least one
/// remains reachable on a censored network.
pub fn build_fallback_encrypted_dns_endpoints(primary_resolver_id: Option<&str>) -> Vec<EncryptedDnsEndpoint> {
    let candidates: Vec<(&str, &str, u16, &[&str], &str)> = vec![
        (
            "adguard",
            "dns.adguard-dns.com",
            443,
            &["94.140.14.14", "94.140.15.15"],
            "https://dns.adguard-dns.com/dns-query",
        ),
        ("dnssb", "dns.sb", 443, &["185.222.222.222", "45.11.45.11"], "https://doh.dns.sb/dns-query"),
        ("google_ip", "8.8.8.8", 443, &["8.8.8.8", "8.8.4.4"], "https://8.8.8.8/dns-query"),
        ("mullvad", "dns.mullvad.net", 443, &["194.242.2.2"], "https://dns.mullvad.net/dns-query"),
    ];

    candidates
        .into_iter()
        .filter(|(id, ..)| primary_resolver_id != Some(*id))
        .filter_map(|(id, host, port, bootstrap_strs, doh_url)| {
            let bootstrap_ips: Vec<IpAddr> = bootstrap_strs.iter().filter_map(|s| s.parse::<IpAddr>().ok()).collect();
            if bootstrap_ips.is_empty() {
                return None;
            }
            Some(EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some(id.to_string()),
                host: host.to_string(),
                port,
                tls_server_name: None,
                bootstrap_ips,
                doh_url: Some(doh_url.to_string()),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            })
        })
        .collect()
}
