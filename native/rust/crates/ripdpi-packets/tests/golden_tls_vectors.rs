//! Golden test vectors for TLS ClientHello parsing.
//!
//! Each fixture is a real-world captured TLS ClientHello record. Tests assert that:
//! 1. The manual parser extracts the correct SNI hostname.
//! 2. `tls-parser` extracts the same SNI hostname (cross-validation).
//! 3. `TlsMarkerInfo` structural invariants hold (offsets are ordered, host slice is valid).

use ripdpi_packets::{is_tls_client_hello, parse_tls, tls_marker_info};

/// Golden fixture entry loaded from the manifest.
struct GoldenFixture {
    file: &'static str,
    data: &'static [u8],
    expected_sni: &'static str,
}

const FIXTURES: &[GoldenFixture] = &[
    GoldenFixture {
        file: "curl_tls12_example_com.bin",
        data: include_bytes!("fixtures/curl_tls12_example_com.bin"),
        expected_sni: "example.com",
    },
    GoldenFixture {
        file: "curl_tls13_example_com.bin",
        data: include_bytes!("fixtures/curl_tls13_example_com.bin"),
        expected_sni: "example.com",
    },
    GoldenFixture {
        file: "curl_auto_cloudflare_com.bin",
        data: include_bytes!("fixtures/curl_auto_cloudflare_com.bin"),
        expected_sni: "cloudflare.com",
    },
    GoldenFixture {
        file: "curl_tls13_github_com.bin",
        data: include_bytes!("fixtures/curl_tls13_github_com.bin"),
        expected_sni: "github.com",
    },
];

fn extract_sni_via_tls_parser(data: &[u8]) -> Option<Vec<u8>> {
    use tls_parser::{parse_tls_extensions, parse_tls_plaintext, TlsExtension, TlsMessage, TlsMessageHandshake};

    let (_, record) = parse_tls_plaintext(data).ok()?;
    for msg in &record.msg {
        if let TlsMessage::Handshake(TlsMessageHandshake::ClientHello(ch)) = msg {
            let exts = ch.ext?;
            if let Ok((_, extensions)) = parse_tls_extensions(exts) {
                for ext in &extensions {
                    if let TlsExtension::SNI(sni_list) = ext {
                        for (_, name) in sni_list {
                            return Some(name.to_vec());
                        }
                    }
                }
            }
        }
    }
    None
}

#[test]
fn golden_fixtures_are_valid_client_hellos() {
    for fixture in FIXTURES {
        assert!(
            is_tls_client_hello(fixture.data),
            "{}: not recognized as TLS ClientHello",
            fixture.file
        );
    }
}

#[test]
fn golden_manual_parser_extracts_correct_sni() {
    for fixture in FIXTURES {
        let sni = parse_tls(fixture.data).unwrap_or_else(|| panic!("{}: manual parser returned None", fixture.file));
        assert_eq!(
            std::str::from_utf8(sni).unwrap(),
            fixture.expected_sni,
            "{}: manual parser SNI mismatch",
            fixture.file
        );
    }
}

#[test]
fn golden_tls_parser_extracts_correct_sni() {
    for fixture in FIXTURES {
        let sni =
            extract_sni_via_tls_parser(fixture.data).unwrap_or_else(|| panic!("{}: tls-parser returned None", fixture.file));
        assert_eq!(
            std::str::from_utf8(&sni).unwrap(),
            fixture.expected_sni,
            "{}: tls-parser SNI mismatch",
            fixture.file
        );
    }
}

#[test]
fn golden_both_parsers_agree_on_sni() {
    for fixture in FIXTURES {
        let manual = parse_tls(fixture.data).unwrap_or_else(|| panic!("{}: manual parser failed", fixture.file));
        let oracle =
            extract_sni_via_tls_parser(fixture.data).unwrap_or_else(|| panic!("{}: tls-parser failed", fixture.file));
        assert_eq!(manual, oracle.as_slice(), "{}: parsers disagree on SNI", fixture.file);
    }
}

#[test]
fn golden_marker_info_structural_invariants() {
    for fixture in FIXTURES {
        let info = tls_marker_info(fixture.data).unwrap_or_else(|| panic!("{}: tls_marker_info returned None", fixture.file));

        // Offsets must be ordered: ext_len < sni_ext < host_start < host_end
        assert!(
            info.ext_len_start < info.sni_ext_start,
            "{}: ext_len_start ({}) >= sni_ext_start ({})",
            fixture.file,
            info.ext_len_start,
            info.sni_ext_start
        );
        assert!(
            info.sni_ext_start < info.host_start,
            "{}: sni_ext_start ({}) >= host_start ({})",
            fixture.file,
            info.sni_ext_start,
            info.host_start
        );
        assert!(
            info.host_start < info.host_end,
            "{}: host_start ({}) >= host_end ({})",
            fixture.file,
            info.host_start,
            info.host_end
        );

        // host_end must not exceed buffer length
        assert!(
            info.host_end <= fixture.data.len(),
            "{}: host_end ({}) exceeds buffer length ({})",
            fixture.file,
            info.host_end,
            fixture.data.len()
        );

        // Extracted host must match expected SNI
        let host = &fixture.data[info.host_start..info.host_end];
        assert_eq!(
            std::str::from_utf8(host).unwrap(),
            fixture.expected_sni,
            "{}: marker-based host extraction mismatch",
            fixture.file
        );

        // SNI extension starts 5 bytes before hostname (ext body len + list len + type + name len)
        assert_eq!(
            info.host_start,
            info.sni_ext_start + 5,
            "{}: unexpected gap between sni_ext_start and host_start",
            fixture.file
        );
    }
}
