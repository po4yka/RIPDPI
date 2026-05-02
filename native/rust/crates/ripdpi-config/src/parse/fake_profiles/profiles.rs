use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

use crate::{ConfigError, QuicFakeProfile};

pub fn parse_quic_fake_profile(spec: &str) -> Result<QuicFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "disabled" => Ok(QuicFakeProfile::Disabled),
        "compat_default" => Ok(QuicFakeProfile::CompatDefault),
        "realistic_initial" => Ok(QuicFakeProfile::RealisticInitial),
        _ => Err(ConfigError::invalid("--fake-quic-profile", Some(spec))),
    }
}

pub fn parse_http_fake_profile(spec: &str) -> Result<HttpFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "" | "compat_default" => Ok(HttpFakeProfile::CompatDefault),
        "iana_get" => Ok(HttpFakeProfile::IanaGet),
        "cloudflare_get" => Ok(HttpFakeProfile::CloudflareGet),
        _ => Err(ConfigError::invalid("--fake-http-profile", Some(spec))),
    }
}

pub fn parse_tls_fake_profile(spec: &str) -> Result<TlsFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "" | "compat_default" => Ok(TlsFakeProfile::CompatDefault),
        "iana_firefox" => Ok(TlsFakeProfile::IanaFirefox),
        "google_chrome" => Ok(TlsFakeProfile::GoogleChrome),
        "google_chrome_hrr" => Ok(TlsFakeProfile::GoogleChromeHrr),
        "vk_chrome" => Ok(TlsFakeProfile::VkChrome),
        "sberbank_chrome" => Ok(TlsFakeProfile::SberbankChrome),
        "rutracker_kyber" => Ok(TlsFakeProfile::RutrackerKyber),
        "bigsize_iana" => Ok(TlsFakeProfile::BigsizeIana),
        _ => Err(ConfigError::invalid("--fake-tls-profile", Some(spec))),
    }
}

pub fn parse_udp_fake_profile(spec: &str) -> Result<UdpFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "" | "compat_default" => Ok(UdpFakeProfile::CompatDefault),
        "zero_256" => Ok(UdpFakeProfile::Zero256),
        "zero_512" => Ok(UdpFakeProfile::Zero512),
        "dns_query" => Ok(UdpFakeProfile::DnsQuery),
        "stun_binding" => Ok(UdpFakeProfile::StunBinding),
        "wireguard_initiation" => Ok(UdpFakeProfile::WireGuardInitiation),
        "dht_get_peers" => Ok(UdpFakeProfile::DhtGetPeers),
        _ => Err(ConfigError::invalid("--fake-udp-profile", Some(spec))),
    }
}
