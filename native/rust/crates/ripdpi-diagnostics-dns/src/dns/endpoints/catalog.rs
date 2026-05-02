use std::net::{IpAddr, Ipv4Addr};

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

use crate::util::{DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL};

pub(super) struct ResolverCatalogEntry {
    pub resolver_id: &'static str,
    pub host: &'static str,
    pub port: u16,
    pub doh_url: &'static str,
    pub tls_server_name: Option<&'static str>,
}

pub fn bootstrap_ips_for_resolver(resolver_id: &str) -> Vec<IpAddr> {
    match resolver_id {
        "cloudflare" => vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        "adguard" => vec![IpAddr::V4(Ipv4Addr::new(94, 140, 14, 14)), IpAddr::V4(Ipv4Addr::new(94, 140, 15, 15))],
        "google" | "google_ip" => vec![IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), IpAddr::V4(Ipv4Addr::new(8, 8, 4, 4))],
        "quad9" => vec![IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9)), IpAddr::V4(Ipv4Addr::new(149, 112, 112, 112))],
        "dnssb" => vec![IpAddr::V4(Ipv4Addr::new(185, 222, 222, 222)), IpAddr::V4(Ipv4Addr::new(45, 11, 45, 11))],
        "mullvad" => vec![IpAddr::V4(Ipv4Addr::new(194, 242, 2, 2))],
        _ => vec![],
    }
}

pub(super) fn bootstrap_strings_for_resolver(resolver_id: Option<&str>) -> Vec<String> {
    resolver_id
        .and_then(|rid| {
            let pinned = bootstrap_ips_for_resolver(rid);
            (!pinned.is_empty()).then(|| pinned.iter().map(ToString::to_string).collect::<Vec<_>>())
        })
        .unwrap_or_else(default_bootstrap_strings)
}

pub(super) fn default_bootstrap_ips() -> Vec<IpAddr> {
    DEFAULT_DOH_BOOTSTRAP_IPS.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect()
}

pub(super) fn default_bootstrap_strings() -> Vec<String> {
    DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect()
}

pub(super) fn resolver_catalog_entry(resolver_id: &str) -> ResolverCatalogEntry {
    match resolver_id {
        "cloudflare" => ResolverCatalogEntry {
            resolver_id: "cloudflare",
            host: "cloudflare-dns.com",
            port: 443,
            doh_url: "https://cloudflare-dns.com/dns-query",
            tls_server_name: Some("cloudflare-dns.com"),
        },
        "google" => ResolverCatalogEntry {
            resolver_id: "google",
            host: "dns.google",
            port: 443,
            doh_url: "https://dns.google/dns-query",
            tls_server_name: Some("dns.google"),
        },
        "google_ip" => ResolverCatalogEntry {
            resolver_id: "google_ip",
            host: "8.8.8.8",
            port: 443,
            doh_url: "https://8.8.8.8/dns-query",
            tls_server_name: None,
        },
        "quad9" => ResolverCatalogEntry {
            resolver_id: "quad9",
            host: "dns.quad9.net",
            port: 443,
            doh_url: "https://dns.quad9.net/dns-query",
            tls_server_name: Some("dns.quad9.net"),
        },
        "dnssb" => ResolverCatalogEntry {
            resolver_id: "dnssb",
            host: "dns.sb",
            port: 443,
            doh_url: "https://doh.dns.sb/dns-query",
            tls_server_name: Some("dns.sb"),
        },
        "mullvad" => ResolverCatalogEntry {
            resolver_id: "mullvad",
            host: "dns.mullvad.net",
            port: 443,
            doh_url: "https://dns.mullvad.net/dns-query",
            tls_server_name: Some("dns.mullvad.net"),
        },
        _ => ResolverCatalogEntry {
            resolver_id: "adguard",
            host: DEFAULT_DOH_HOST,
            port: DEFAULT_DOH_PORT,
            doh_url: DEFAULT_DOH_URL,
            tls_server_name: Some(DEFAULT_DOH_HOST),
        },
    }
}

pub(super) fn default_port(protocol: EncryptedDnsProtocol) -> u16 {
    match protocol {
        EncryptedDnsProtocol::Doh => DEFAULT_DOH_PORT,
        EncryptedDnsProtocol::Dot => 853,
        EncryptedDnsProtocol::DnsCrypt => 443,
        EncryptedDnsProtocol::Doq => 853,
    }
}

pub(super) fn resolver_entry_endpoint(entry: ResolverCatalogEntry) -> EncryptedDnsEndpoint {
    let pinned = bootstrap_ips_for_resolver(entry.resolver_id);
    EncryptedDnsEndpoint {
        protocol: EncryptedDnsProtocol::Doh,
        resolver_id: Some(entry.resolver_id.to_string()),
        host: entry.host.to_string(),
        port: entry.port,
        tls_server_name: entry.tls_server_name.map(ToString::to_string),
        bootstrap_ips: if pinned.is_empty() { default_bootstrap_ips() } else { pinned },
        doh_url: Some(entry.doh_url.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}
