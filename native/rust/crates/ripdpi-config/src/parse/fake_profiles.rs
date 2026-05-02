mod bytes;
mod hosts;
mod profiles;
mod tls_modifiers;

pub use self::bytes::{data_from_str, file_or_inline_bytes};
pub(crate) use self::hosts::lower_host_char;
pub use self::hosts::{normalize_fake_host_template, normalize_quic_fake_host};
pub use self::profiles::{
    parse_http_fake_profile, parse_quic_fake_profile, parse_tls_fake_profile, parse_udp_fake_profile,
};
pub(crate) use self::tls_modifiers::apply_fake_tls_mod_token;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::QuicFakeProfile;
    use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

    #[test]
    fn data_from_str_all_cform_branches() {
        assert_eq!(data_from_str("\\r").unwrap(), vec![b'\r']);
        assert_eq!(data_from_str("\\n").unwrap(), vec![b'\n']);
        assert_eq!(data_from_str("\\t").unwrap(), vec![b'\t']);
        assert_eq!(data_from_str("\\\\").unwrap(), vec![b'\\']);
        assert_eq!(data_from_str("\\f").unwrap(), vec![0x0c]);
        assert_eq!(data_from_str("\\b").unwrap(), vec![0x08]);
        assert_eq!(data_from_str("\\v").unwrap(), vec![0x0b]);
        assert_eq!(data_from_str("\\a").unwrap(), vec![0x07]);
        assert_eq!(data_from_str("\\x41").unwrap(), vec![0x41]);
        assert_eq!(data_from_str("\\101").unwrap(), vec![0x41]);
    }

    #[test]
    fn data_from_str_trailing_backslash() {
        assert_eq!(data_from_str("abc\\").unwrap(), vec![97, 98, 99, b'\\']);
    }

    #[test]
    fn data_from_str_empty_string_rejected() {
        assert!(data_from_str("").is_err());
    }

    #[test]
    fn data_from_str_plain_ascii() {
        assert_eq!(data_from_str("hello").unwrap(), b"hello".to_vec());
    }

    #[test]
    fn data_from_str_mixed_escapes_and_text() {
        let result = data_from_str("A\\x42C").unwrap();
        assert_eq!(result, vec![b'A', 0x42, b'C']);
    }

    #[test]
    fn file_or_inline_bytes_inline_data() {
        let result = file_or_inline_bytes(":hello").unwrap();
        assert_eq!(result, b"hello".to_vec());
    }

    #[test]
    fn file_or_inline_bytes_missing_file() {
        assert!(file_or_inline_bytes("/nonexistent/path/to/file").is_err());
    }

    #[test]
    fn lower_host_char_range_boundaries() {
        assert_eq!(lower_host_char('-'), Some('-'));
        assert_eq!(lower_host_char('9'), Some('9'));
        assert_eq!(lower_host_char(','), None);
        assert_eq!(lower_host_char('A'), Some('a'));
    }

    #[test]
    fn normalize_quic_fake_host_rejects_invalid_values() {
        assert_eq!(normalize_quic_fake_host(" Example.COM. ").unwrap(), "example.com");
        assert!(normalize_quic_fake_host("127.0.0.1").is_err());
        assert!(normalize_quic_fake_host("::1").is_err());
        assert!(normalize_quic_fake_host("bad..host").is_err());
    }

    #[test]
    fn parse_quic_fake_profile_accepts_known_values() {
        assert_eq!(parse_quic_fake_profile("disabled").unwrap(), QuicFakeProfile::Disabled);
        assert_eq!(parse_quic_fake_profile("compat_default").unwrap(), QuicFakeProfile::CompatDefault);
        assert_eq!(parse_quic_fake_profile("realistic_initial").unwrap(), QuicFakeProfile::RealisticInitial);
        assert!(parse_quic_fake_profile("bogus").is_err());
    }

    #[test]
    fn parse_fake_payload_profiles_accepts_known_values() {
        assert_eq!(parse_http_fake_profile("compat_default").unwrap(), HttpFakeProfile::CompatDefault);
        assert_eq!(parse_http_fake_profile("iana_get").unwrap(), HttpFakeProfile::IanaGet);
        assert_eq!(parse_http_fake_profile("cloudflare_get").unwrap(), HttpFakeProfile::CloudflareGet);
        assert!(parse_http_fake_profile("bogus").is_err());

        assert_eq!(parse_tls_fake_profile("compat_default").unwrap(), TlsFakeProfile::CompatDefault);
        assert_eq!(parse_tls_fake_profile("google_chrome").unwrap(), TlsFakeProfile::GoogleChrome);
        assert_eq!(parse_tls_fake_profile("google_chrome_hrr").unwrap(), TlsFakeProfile::GoogleChromeHrr);
        assert_eq!(parse_tls_fake_profile("rutracker_kyber").unwrap(), TlsFakeProfile::RutrackerKyber);
        assert!(parse_tls_fake_profile("bogus").is_err());

        assert_eq!(parse_udp_fake_profile("compat_default").unwrap(), UdpFakeProfile::CompatDefault);
        assert_eq!(parse_udp_fake_profile("dns_query").unwrap(), UdpFakeProfile::DnsQuery);
        assert_eq!(parse_udp_fake_profile("wireguard_initiation").unwrap(), UdpFakeProfile::WireGuardInitiation);
        assert!(parse_udp_fake_profile("bogus").is_err());
    }

    #[test]
    fn normalize_fake_host_template_valid() {
        assert_eq!(normalize_fake_host_template("Example.COM.").unwrap(), "example.com");
        assert_eq!(normalize_fake_host_template("sub.HOST.test").unwrap(), "sub.host.test");
    }

    #[test]
    fn normalize_fake_host_template_rejects_invalid() {
        assert!(normalize_fake_host_template("").is_err());
        assert!(normalize_fake_host_template("bad..host").is_err());
        assert!(normalize_fake_host_template("-start.com").is_err());
        assert!(normalize_fake_host_template("end-.com").is_err());
        assert!(normalize_fake_host_template("127.0.0.1").is_err());
    }

    #[test]
    fn data_from_str_hex_boundary_00_ff() {
        assert_eq!(data_from_str("\\x00").unwrap(), vec![0x00]);
        assert_eq!(data_from_str("\\xff").unwrap(), vec![0xff]);
    }

    #[test]
    fn data_from_str_octal_boundary_000_377() {
        assert_eq!(data_from_str("\\000").unwrap(), vec![0x00]);
        assert_eq!(data_from_str("\\377").unwrap(), vec![0xff]);
    }

    #[test]
    fn apply_fake_tls_mod_all_tokens() {
        use crate::{DesyncGroup, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI};

        let mut group = DesyncGroup::new(0);
        apply_fake_tls_mod_token(&mut group, "rand", "--fake-tls-mod", "rand").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND);

        apply_fake_tls_mod_token(&mut group, "orig", "--fake-tls-mod", "orig").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG);

        apply_fake_tls_mod_token(&mut group, "rndsni", "--fake-tls-mod", "rndsni").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG | FM_RNDSNI);

        apply_fake_tls_mod_token(&mut group, "dupsid", "--fake-tls-mod", "dupsid").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG | FM_RNDSNI | FM_DUPSID);

        apply_fake_tls_mod_token(&mut group, "padencap", "--fake-tls-mod", "padencap").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG | FM_RNDSNI | FM_DUPSID | FM_PADENCAP);
    }

    #[test]
    fn apply_fake_tls_mod_rejects_unknown_token() {
        use crate::DesyncGroup;
        let mut group = DesyncGroup::new(0);
        assert!(apply_fake_tls_mod_token(&mut group, "bogus", "--fake-tls-mod", "bogus").is_err());
    }

    #[test]
    fn apply_fake_tls_mod_msize_key() {
        use crate::DesyncGroup;
        let mut group = DesyncGroup::new(0);
        apply_fake_tls_mod_token(&mut group, "m=256", "--fake-tls-mod", "m=256").unwrap();
        assert_eq!(group.actions.fake_tls_size, 256);

        let mut group2 = DesyncGroup::new(0);
        apply_fake_tls_mod_token(&mut group2, "msize=512", "--fake-tls-mod", "msize=512").unwrap();
        assert_eq!(group2.actions.fake_tls_size, 512);
    }

    #[test]
    fn file_or_inline_bytes_with_escapes() {
        let result = file_or_inline_bytes(":A\\x42C").unwrap();
        assert_eq!(result, vec![b'A', 0x42, b'C']);
    }
}
