use base64::{Engine as _, engine::general_purpose::STANDARD};

use super::*;
use crate::config::{AwgV3Config, SecretString};

/// Byte-exact vectors printed by a Go program that applies the
/// `golang.org/x/crypto/chacha20` construction of amneziawg-go v3.1 `send.go`
/// (key = 32 x 0x03, S prefix = 0xa0..0xab). Init: H1 = 150, bytes 4.. = index,
/// trailer `deadbeef01`. Transport: H4 = 450, receiver 7, counter 9, then 0x50..0x67.
const GO_INIT: &str = "a0a1a2a3a4a5a6a7a8a9aaabed1d39eca70332aa35f63fd6a6ad19777814b700f5c5b09e4a8a67496c0600da0d87e379af4c12d6b6472efe730cd926f274a36ecba26cfef2a8337288673af46c6f7d6253ea1b324e46a86cc50d77658396a2c0677e38e73c13fa9341ea63c73c6b4280e37fab35137a31afdb348711fe25fd99f2c907f9dffc2a0fa77e4f1b2f38028f1bad63d84336db8d3da805e8526916a3deadbeef01";
const GO_TRANSPORT: &str =
    "a0a1a2a3a4a5a6a7a8a9aaabb91c39eca40634ad34ff35ddaaa01778505152535455565758595a5b5c5d5e5f6061626364656667";

fn hex(text: &str) -> Vec<u8> {
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
}

fn config(v3: AwgV3Config, padding: i32) -> WarpAmneziaConfig {
    WarpAmneziaConfig {
        enabled: true,
        h1: "100-199".into(),
        h2: "200-299".into(),
        h3: "300-399".into(),
        h4: "400-499".into(),
        s1: padding,
        s2: padding,
        s3: padding,
        s4: padding,
        v3,
        ..Default::default()
    }
}

fn protected() -> AwgV3Config {
    AwgV3Config {
        header_protection_key: SecretString(STANDARD.encode([3; 32])),
        random_trailers: true,
        ..Default::default()
    }
}

fn headers() -> [HeaderRange; 4] {
    ["100-199", "200-299", "300-399", "400-499"].map(|text| HeaderRange::parse(text).unwrap())
}

fn params(v3: AwgV3Config, padding: u32) -> AwgV3Params {
    AwgV3Params::from_config(&config(v3, padding as i32), &[padding; 4]).unwrap()
}

#[test]
fn open_matches_go_header_protection_vectors() {
    let v3 = params(protected(), 12);
    let (index, message) = v3.open(&hex(GO_INIT), &[12; 4], &headers()).unwrap();
    assert_eq!(index, 0, "a trailer must not hide the initiation");
    assert_eq!(u32::from_le_bytes(message[..4].try_into().unwrap()), 150);
    assert_eq!(message.len(), 148);
    assert!(message[4..].iter().enumerate().all(|(i, &byte)| byte == (i + 4) as u8));

    let (index, message) = v3.open(&hex(GO_TRANSPORT), &[12; 4], &headers()).unwrap();
    assert_eq!(index, 3);
    assert_eq!(u32::from_le_bytes(message[..4].try_into().unwrap()), 450);
    assert_eq!(u32::from_le_bytes(message[4..8].try_into().unwrap()), 7);
    assert_eq!(u64::from_le_bytes(message[8..16].try_into().unwrap()), 9);
    assert_eq!(message[16..], hex(GO_TRANSPORT)[28..], "transport ciphertext is not masked");
}

#[test]
fn seal_masks_headers_appends_handshake_trailers_and_opens_back() {
    let v3 = params(protected(), 12);
    let window = UdpWindow::default();
    for (index, size) in [(0, 148), (1, 92), (2, 64), (3, 80)] {
        let mut message = vec![0x5a; size];
        message[..4].copy_from_slice(&(150 + 100 * index as u32).to_le_bytes());
        let mut trailers = 0;
        for _ in 0..32 {
            let wire = v3.seal(message.clone(), index, 12, &window).unwrap();
            assert!(!headers()[index].contains(u32::from_le_bytes(wire[12..16].try_into().unwrap())));
            if index == 3 {
                assert_eq!(wire.len(), 12 + size, "transport padding stays inside the ciphertext");
                assert_eq!(wire[12 + 16..], message[16..]);
            } else {
                assert!(wire.len() < DEFAULT_UDP_WINDOW as usize);
                trailers += usize::from(wire.len() > 12 + size);
            }
            assert_eq!(v3.open(&wire, &[12; 4], &headers()), Some((index, message.clone())));
        }
        if index < 3 {
            assert!(trailers > 0, "random trailers must vary the handshake length");
        }
    }
}

#[test]
fn trailers_are_refused_when_random_trailers_is_off() {
    let v3 = params(AwgV3Config { random_trailers: false, ..protected() }, 12);
    let mut init = hex(GO_INIT);
    assert!(v3.open(&init, &[12; 4], &headers()).is_none(), "exact sizes only");
    init.truncate(12 + 148);
    assert_eq!(v3.open(&init, &[12; 4], &headers()).unwrap().0, 0);
}

#[test]
fn defaults_keep_the_awg_2_wire_bytes() {
    let v3 = params(AwgV3Config::default(), 0);
    let message = vec![7u8; 148];
    assert_eq!(v3.seal(message.clone(), 0, 0, &UdpWindow::default()), Some(message));
    assert_eq!(v3.transport_padding(100, 0, &UdpWindow::default()), 0);
    assert_eq!(v3.timer_config(), boringtun::noise::awg::AwgTimerConfig::default());
}

#[test]
fn header_protection_needs_twelve_byte_prefixes_and_a_valid_key() {
    for padding in [0, 11] {
        let error = AwgV3Params::from_config(&config(protected(), 12), &[12, 12, padding, 12]).unwrap_err();
        assert_eq!(error, AwgParamsError::HeaderProtectionPaddingTooSmall { index: 2, value: padding });
    }
    let bogus_key = "not-a-key+secret-material";
    let bad = AwgV3Config { header_protection_key: SecretString(bogus_key.into()), ..Default::default() };
    let error = AwgV3Params::from_config(&config(bad, 12), &[12; 4]).unwrap_err();
    assert_eq!(error, AwgParamsError::InvalidHeaderProtectionKey);
    assert!(!error.to_string().contains(bogus_key));
    let zero = AwgV3Config { header_protection_key: SecretString(STANDARD.encode([0; 32])), ..Default::default() };
    assert!(AwgV3Params::from_config(&config(zero, 0), &[0; 4]).is_ok(), "an all-zero key means off");
    assert!(!format!("{:?}", params(protected(), 12)).contains(&STANDARD.encode([3; 32])));
}

#[test]
fn range_keys_follow_upstream_uint_range_rules() {
    let with = |text: &str| AwgV3Config { rekey_timeout: text.into(), ..Default::default() };
    for valid in ["", "0", "0-3", "5", "3-7", "7-7", "65535"] {
        assert!(AwgV3Params::from_config(&config(with(valid), 0), &[0; 4]).is_ok(), "{valid}");
    }
    for invalid in ["7-3", "65536", "1-70000", "x", " 5", "-1"] {
        assert_eq!(
            AwgV3Params::from_config(&config(with(invalid), 0), &[0; 4]),
            Err(AwgParamsError::InvalidRange { name: "RekeyTimeout" }),
            "{invalid}"
        );
    }
    let rekey = AwgV3Config { rekey_after_time: "100-120".into(), ..Default::default() };
    assert_eq!(params(rekey, 0).timer_config().rekey_after, TimerRange::new(100, 120).unwrap());
}

#[test]
fn every_timer_range_is_preserved_for_the_backend_policy() {
    let config = AwgV3Config {
        rekey_after_time: "200-240".into(),
        rekey_timeout: "1-3".into(),
        reject_after_time: "300-350".into(),
        keepalive_timeout: "4-7".into(),
        max_handshake_attempts: "1-2".into(),
        ..Default::default()
    };
    assert_eq!(
        params(config, 0).timer_config(),
        AwgTimerConfig {
            rekey_after: TimerRange::new(200, 240).unwrap(),
            rekey_timeout: TimerRange::new(1, 3).unwrap(),
            reject_after: TimerRange::new(300, 350).unwrap(),
            keepalive_timeout: TimerRange::new(4, 7).unwrap(),
            max_handshake_attempts: TimerRange::new(1, 2).unwrap(),
            persistent_keepalive: None,
        }
    );
    let zero = AwgV3Config { rekey_timeout: "0".into(), reject_after_time: "0-0".into(), ..Default::default() };
    assert_eq!(params(zero, 0).timer_config(), AwgTimerConfig::default());
}

#[test]
fn transport_padding_respects_the_learned_udp_window() {
    let padded = AwgV3Config { content_padding_addition: "10-100".into(), ..Default::default() };
    let v3 = params(padded, 12);
    let window = UdpWindow::default();
    for _ in 0..64 {
        // 400 + 32 + 12 = 444 bytes leaves 56 bytes of the default 500-byte window.
        assert!((10..=56).contains(&v3.transport_padding(400, 12, &window)));
    }
    assert_eq!(v3.transport_padding(470, 12, &window), 0, "a larger packet raises the window, no space left");
    window.observe_inbound_transport(1_000);
    assert!(v3.transport_padding(400, 12, &window) >= 10);

    let trailers = params(AwgV3Config { random_trailers: true, ..Default::default() }, 12);
    let fresh = UdpWindow::default();
    assert!((0..56).contains(&trailers.transport_padding(400, 12, &fresh)));
}

#[test]
fn unauthenticated_transport_does_not_grow_the_udp_window_and_growth_is_capped() {
    use crate::amneziawg::{AwgParams, AwgWireCodec};
    let padded = AwgV3Config { content_padding_addition: "1000-1000".into(), ..Default::default() };
    let codec = AwgWireCodec::new(AwgParams::from_config(&config(padded, 12), &[""; 5]).unwrap());
    // A spoofed 65 KB datagram with a valid H4 decodes, but boringtun has not authenticated it.
    let mut spoofed = vec![0u8; 65_000];
    spoofed[12..16].copy_from_slice(&450u32.to_le_bytes());
    assert!(codec.decode(&spoofed).is_some());
    let mut out = Vec::new();
    assert!(codec.pad_transport_plaintext_into(&[0x45; 400], &mut out));
    // 400 + 32 + 12 = 444 of the default 500-byte window: at most 56 padding bytes.
    assert_eq!(out.len(), 456, "decode alone must not move the window");

    codec.observe_authenticated_transport(65_000);
    assert!(codec.pad_transport_plaintext_into(&[0x45; 400], &mut out));
    // Capped at 1500 + 32 + S4 = 1544, which leaves 1100 bytes of space: 1000 fit.
    assert_eq!(out.len(), 1400);
    assert!(codec.pad_transport_plaintext_into(&[0x45; 1200], &mut out));
    assert_eq!(out.len(), 1200 + 300, "the capped window bounds padding to 1544 - 1244 bytes");
}
