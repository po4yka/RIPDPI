#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpFakeProfile {
    CompatDefault,
    IanaGet,
    CloudflareGet,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TlsFakeProfile {
    CompatDefault,
    IanaFirefox,
    GoogleChrome,
    VkChrome,
    SberbankChrome,
    RutrackerKyber,
    BigsizeIana,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpFakeProfile {
    CompatDefault,
    Zero256,
    Zero512,
    DnsQuery,
    StunBinding,
    WireGuardInitiation,
    DhtGetPeers,
}

const HTTP_IANA_GET: &[u8] = include_bytes!("fake_profiles/http_iana_org.bin");
const HTTP_CLOUDFLARE_GET: &[u8] =
    b"GET / HTTP/1.1\r\nHost: www.cloudflare.com\r\nConnection: keep-alive\r\nAccept: */*\r\n\r\n";

const TLS_IANA_FIREFOX: &[u8] = include_bytes!("fake_profiles/tls_clienthello_iana_org.bin");
const TLS_GOOGLE_CHROME: &[u8] = include_bytes!("fake_profiles/tls_clienthello_www_google_com.bin");
const TLS_VK_CHROME: &[u8] = include_bytes!("fake_profiles/tls_clienthello_vk_com.bin");
const TLS_SBERBANK_CHROME: &[u8] = include_bytes!("fake_profiles/tls_clienthello_sberbank_ru.bin");
const TLS_RUTRACKER_KYBER: &[u8] = include_bytes!("fake_profiles/tls_clienthello_rutracker_org_kyber.bin");
const TLS_BIGSIZE_IANA: &[u8] = include_bytes!("fake_profiles/tls_clienthello_iana_org_bigsize.bin");

const UDP_ZERO_256: &[u8] = include_bytes!("fake_profiles/zero_256.bin");
const UDP_ZERO_512: &[u8] = include_bytes!("fake_profiles/zero_512.bin");
const UDP_DNS_QUERY: &[u8] = include_bytes!("fake_profiles/dns.bin");
const UDP_STUN_BINDING: &[u8] = include_bytes!("fake_profiles/stun.bin");
const UDP_WIREGUARD_INITIATION: &[u8] = include_bytes!("fake_profiles/wireguard_initiation.bin");
const UDP_DHT_GET_PEERS: &[u8] = include_bytes!("fake_profiles/dht_get_peers.bin");

pub fn http_fake_profile_bytes(profile: HttpFakeProfile) -> &'static [u8] {
    match profile {
        HttpFakeProfile::CompatDefault => crate::DEFAULT_FAKE_HTTP,
        HttpFakeProfile::IanaGet => HTTP_IANA_GET,
        HttpFakeProfile::CloudflareGet => HTTP_CLOUDFLARE_GET,
    }
}

pub fn tls_fake_profile_bytes(profile: TlsFakeProfile) -> &'static [u8] {
    match profile {
        TlsFakeProfile::CompatDefault => crate::DEFAULT_FAKE_TLS,
        TlsFakeProfile::IanaFirefox => TLS_IANA_FIREFOX,
        TlsFakeProfile::GoogleChrome => TLS_GOOGLE_CHROME,
        TlsFakeProfile::VkChrome => TLS_VK_CHROME,
        TlsFakeProfile::SberbankChrome => TLS_SBERBANK_CHROME,
        TlsFakeProfile::RutrackerKyber => TLS_RUTRACKER_KYBER,
        TlsFakeProfile::BigsizeIana => TLS_BIGSIZE_IANA,
    }
}

pub fn udp_fake_profile_bytes(profile: UdpFakeProfile) -> &'static [u8] {
    match profile {
        UdpFakeProfile::CompatDefault => crate::DEFAULT_FAKE_UDP,
        UdpFakeProfile::Zero256 => UDP_ZERO_256,
        UdpFakeProfile::Zero512 => UDP_ZERO_512,
        UdpFakeProfile::DnsQuery => UDP_DNS_QUERY,
        UdpFakeProfile::StunBinding => UDP_STUN_BINDING,
        UdpFakeProfile::WireGuardInitiation => UDP_WIREGUARD_INITIATION,
        UdpFakeProfile::DhtGetPeers => UDP_DHT_GET_PEERS,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn http_profiles_parse_and_expose_hosts() {
        let cases = [
            (HttpFakeProfile::CompatDefault, "www.wikipedia.org"),
            (HttpFakeProfile::IanaGet, "www.iana.org"),
            (HttpFakeProfile::CloudflareGet, "www.cloudflare.com"),
        ];

        for (profile, host) in cases {
            let parsed = crate::parse_http(http_fake_profile_bytes(profile)).expect("http profile");
            assert_eq!(std::str::from_utf8(parsed.host).unwrap(), host);
        }
    }

    #[test]
    fn tls_profiles_parse_and_expose_sni() {
        let cases = [
            (TlsFakeProfile::CompatDefault, "www.wikipedia.org"),
            (TlsFakeProfile::IanaFirefox, "iana.org"),
            (TlsFakeProfile::GoogleChrome, "www.google.com"),
            (TlsFakeProfile::VkChrome, "vk.com"),
            (TlsFakeProfile::SberbankChrome, "online.sberbank.ru"),
            (TlsFakeProfile::RutrackerKyber, "rutracker.org"),
            (TlsFakeProfile::BigsizeIana, "iana.org"),
        ];

        for (profile, host) in cases {
            let parsed = crate::parse_tls(tls_fake_profile_bytes(profile)).expect("tls profile");
            assert_eq!(std::str::from_utf8(parsed).unwrap(), host);
        }
    }

    #[test]
    fn udp_profiles_match_expected_lengths_and_signatures() {
        assert_eq!(udp_fake_profile_bytes(UdpFakeProfile::CompatDefault).len(), 64);
        assert_eq!(udp_fake_profile_bytes(UdpFakeProfile::Zero256).len(), 256);
        assert_eq!(udp_fake_profile_bytes(UdpFakeProfile::Zero512).len(), 512);

        let dns = udp_fake_profile_bytes(UdpFakeProfile::DnsQuery);
        assert_eq!(&dns[12..19], b"\x06update");

        let stun = udp_fake_profile_bytes(UdpFakeProfile::StunBinding);
        assert_eq!(&stun[4..8], b"\x21\x12\xa4\x42");

        let wireguard = udp_fake_profile_bytes(UdpFakeProfile::WireGuardInitiation);
        assert_eq!(wireguard[0], 1);

        let dht = udp_fake_profile_bytes(UdpFakeProfile::DhtGetPeers);
        assert!(dht.windows(b"get_peers".len()).any(|window| window == b"get_peers"));
    }
}
