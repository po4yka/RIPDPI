use std::collections::BTreeSet;
use std::io::Read;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use ripdpi_packets::{parse_tls_client_hello_layout, TlsClientHelloLayout};

use crate::{
    apply_record_choreography, build_connector, planned_record_payload_lengths, selected_profile_config,
    selected_profile_metadata, AVAILABLE_PROFILES,
};

const EXT_SUPPORTED_GROUPS: u16 = 0x000a;
const EXT_ALPN: u16 = 0x0010;
const EXT_KEY_SHARE: u16 = 0x0033;
const X25519: u16 = 0x001d;
const SECP256R1: u16 = 0x0017;
const SECP384R1: u16 = 0x0018;
const SECP521R1: u16 = 0x0019;

#[derive(Debug)]
struct CapturedClientHello {
    bytes: Vec<u8>,
    layout: TlsClientHelloLayout,
}

fn capture_client_hello(profile: &str) -> CapturedClientHello {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
    listener.set_nonblocking(false).expect("listener blocking mode");
    let addr = listener.local_addr().expect("listener addr");

    let server = thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept client");
        socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set server read timeout");

        let mut header = [0_u8; 5];
        socket.read_exact(&mut header).expect("read TLS record header");
        let payload_len = u16::from_be_bytes([header[3], header[4]]) as usize;
        let mut payload = vec![0_u8; payload_len];
        socket.read_exact(&mut payload).expect("read TLS record payload");
        let _ = socket.shutdown(Shutdown::Both);

        [header.to_vec(), payload].concat()
    });

    let connector = build_connector(profile, false).expect("build TLS connector");
    let stream = TcpStream::connect(addr).expect("connect loopback socket");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set client read timeout");
    stream.set_write_timeout(Some(Duration::from_secs(5))).expect("set client write timeout");

    let _ = connector.connect("template-validation.test", stream);

    let bytes = server.join().expect("server join");
    let layout = parse_tls_client_hello_layout(&bytes).expect("parse captured ClientHello");
    CapturedClientHello { bytes, layout }
}

fn extension_order(capture: &CapturedClientHello) -> Vec<u16> {
    capture.layout.extensions.iter().map(|extension| extension.ext_type).collect()
}

fn normalized_extension_order(capture: &CapturedClientHello) -> Vec<u16> {
    extension_order(capture)
        .into_iter()
        .map(|ext_type| if is_grease_value(ext_type) { 0x0a0a } else { ext_type })
        .collect()
}

fn extension_bytes(capture: &CapturedClientHello, ext_type: u16) -> &[u8] {
    let extension = capture
        .layout
        .extensions
        .iter()
        .find(|extension| extension.ext_type == ext_type)
        .unwrap_or_else(|| panic!("extension 0x{ext_type:04x} not present"));
    &capture.bytes[extension.data_offset..extension.data_offset + extension.data_len]
}

fn parse_alpn_protocols(payload: &[u8]) -> Vec<Vec<u8>> {
    if payload.len() < 2 {
        return Vec::new();
    }
    let list_len = u16::from_be_bytes([payload[0], payload[1]]) as usize;
    let mut index = 2_usize;
    let list_end = 2 + list_len.min(payload.len().saturating_sub(2));
    let mut protocols = Vec::new();
    while index < list_end {
        let proto_len = payload[index] as usize;
        index += 1;
        if index + proto_len > list_end {
            break;
        }
        protocols.push(payload[index..index + proto_len].to_vec());
        index += proto_len;
    }
    protocols
}

fn parse_named_group_list(payload: &[u8]) -> Vec<u16> {
    if payload.len() < 2 {
        return Vec::new();
    }
    let list_len = u16::from_be_bytes([payload[0], payload[1]]) as usize;
    let mut groups = Vec::new();
    let mut index = 2_usize;
    let list_end = 2 + list_len.min(payload.len().saturating_sub(2));
    while index + 1 < list_end {
        groups.push(u16::from_be_bytes([payload[index], payload[index + 1]]));
        index += 2;
    }
    groups
}

fn parse_key_share_groups(payload: &[u8]) -> Vec<u16> {
    if payload.len() < 2 {
        return Vec::new();
    }
    let list_len = u16::from_be_bytes([payload[0], payload[1]]) as usize;
    let mut groups = Vec::new();
    let mut index = 2_usize;
    let list_end = 2 + list_len.min(payload.len().saturating_sub(2));
    while index + 3 < list_end {
        let group = u16::from_be_bytes([payload[index], payload[index + 1]]);
        let key_len = u16::from_be_bytes([payload[index + 2], payload[index + 3]]) as usize;
        index += 4;
        if index + key_len > list_end {
            break;
        }
        groups.push(group);
        index += key_len;
    }
    groups
}

fn tls_record_payload_lengths(buffer: &[u8]) -> Vec<usize> {
    let mut cursor = 0usize;
    let mut lengths = Vec::new();
    while cursor + 5 <= buffer.len() {
        let payload_len = u16::from_be_bytes([buffer[cursor + 3], buffer[cursor + 4]]) as usize;
        lengths.push(payload_len);
        cursor += 5 + payload_len;
    }
    assert_eq!(cursor, buffer.len(), "serialized TLS records must cover the full buffer");
    lengths
}

fn flatten_tls_record_payload(buffer: &[u8]) -> Vec<u8> {
    let mut cursor = 0usize;
    let mut payload = Vec::new();
    while cursor + 5 <= buffer.len() {
        let payload_len = u16::from_be_bytes([buffer[cursor + 3], buffer[cursor + 4]]) as usize;
        payload.extend_from_slice(&buffer[cursor + 5..cursor + 5 + payload_len]);
        cursor += 5 + payload_len;
    }
    assert_eq!(cursor, buffer.len(), "serialized TLS records must cover the full buffer");
    payload
}

fn is_grease_value(value: u16) -> bool {
    let [hi, lo] = value.to_be_bytes();
    hi == lo && (hi & 0x0f) == 0x0a
}

fn strip_grease(values: &[u16]) -> Vec<u16> {
    values.iter().copied().filter(|value| !is_grease_value(*value)).collect()
}

fn expected_supported_groups(profile: &str) -> &'static [u16] {
    match selected_profile_metadata(profile).template.supported_groups_profile {
        "x25519_p256_p384" => &[X25519, SECP256R1, SECP384R1],
        "x25519_p256_p384_p521" => &[X25519, SECP256R1, SECP384R1, SECP521R1],
        other => panic!("unexpected supported-groups profile {other}"),
    }
}

#[test]
fn captured_client_hello_matches_profile_packet_parity_metadata() {
    for profile in AVAILABLE_PROFILES {
        let capture = capture_client_hello(profile);
        let metadata = selected_profile_metadata(profile);

        assert_eq!(capture.bytes[0], 0x16, "{profile}: expected handshake record");
        assert_eq!(capture.bytes[5], 0x01, "{profile}: expected ClientHello");
        assert_eq!(
            capture.layout.record_payload_len + 5,
            capture.bytes.len(),
            "{profile}: expected a single-record ClientHello capture"
        );

        let alpn_protocols = parse_alpn_protocols(extension_bytes(&capture, EXT_ALPN));
        assert_eq!(
            alpn_protocols,
            vec![b"h2".to_vec(), b"http/1.1".to_vec()],
            "{profile}: ALPN payload drifted from template metadata"
        );

        let supported_groups = parse_named_group_list(extension_bytes(&capture, EXT_SUPPORTED_GROUPS));
        assert_eq!(
            strip_grease(&supported_groups),
            expected_supported_groups(profile),
            "{profile}: supported-groups payload drifted from template metadata"
        );

        let key_share_groups = parse_key_share_groups(extension_bytes(&capture, EXT_KEY_SHARE));
        match metadata.template.key_share_profile {
            "x25519_primary" => {
                assert_eq!(
                    strip_grease(&key_share_groups),
                    vec![X25519],
                    "{profile}: key-share payload drifted from x25519_primary profile"
                );
            }
            "x25519_hybrid_ready" => {
                let non_grease_key_shares = strip_grease(&key_share_groups);
                assert!(
                    !non_grease_key_shares.is_empty(),
                    "{profile}: hybrid-ready profile must still advertise a usable key share"
                );
                assert_eq!(
                    non_grease_key_shares[0], X25519,
                    "{profile}: hybrid-ready profile must keep x25519 as the primary share"
                );
                let supported_group_set = strip_grease(&supported_groups).into_iter().collect::<BTreeSet<_>>();
                assert!(
                    non_grease_key_shares.iter().all(|group| supported_group_set.contains(group)),
                    "{profile}: key shares must stay within the supported-groups surface"
                );
            }
            other => panic!("{profile}: unexpected key-share profile {other}"),
        }

        let extension_order = extension_order(&capture);
        let has_grease_extension = extension_order.iter().copied().any(is_grease_value);
        let has_grease_supported_group = supported_groups.iter().copied().any(is_grease_value);
        match metadata.template.grease_style {
            "none" => {
                assert!(!has_grease_extension, "{profile}: unexpected GREASE extension on non-GREASE profile");
                assert!(
                    !has_grease_supported_group,
                    "{profile}: unexpected GREASE supported-group on non-GREASE profile"
                );
                assert!(
                    key_share_groups.iter().copied().all(|group| !is_grease_value(group)),
                    "{profile}: unexpected GREASE key share on non-GREASE profile"
                );
            }
            "chromium_single_grease" | "firefox_ech_grease" => {
                assert!(has_grease_extension, "{profile}: expected GREASE extension on GREASE-enabled profile");
                assert!(
                    has_grease_supported_group,
                    "{profile}: expected GREASE supported-group entry on GREASE-enabled profile"
                );
            }
            other => panic!("{profile}: unexpected GREASE style {other}"),
        }
    }
}

#[test]
fn fixed_extension_order_families_remain_stable_across_captures() {
    for profile in AVAILABLE_PROFILES {
        let config = selected_profile_config(profile);
        if config.permute_extensions {
            continue;
        }

        let first_order = normalized_extension_order(&capture_client_hello(profile));
        let second_order = normalized_extension_order(&capture_client_hello(profile));
        let third_order = normalized_extension_order(&capture_client_hello(profile));

        assert_eq!(first_order, second_order, "{profile}: fixed extension-order family drifted between captures");
        assert_eq!(first_order, third_order, "{profile}: fixed extension-order family drifted between captures");
    }
}

#[test]
fn chromium_permuted_profiles_exercise_multiple_extension_orders() {
    for profile in AVAILABLE_PROFILES {
        let config = selected_profile_config(profile);
        if !config.permute_extensions {
            continue;
        }

        let captures = (0..8).map(|_| capture_client_hello(profile)).collect::<Vec<_>>();
        let extension_sets = captures
            .iter()
            .map(|capture| {
                extension_order(capture)
                    .into_iter()
                    .filter(|ext_type| !is_grease_value(*ext_type))
                    .collect::<BTreeSet<_>>()
            })
            .collect::<Vec<_>>();
        let distinct_orders = captures.iter().map(extension_order).collect::<BTreeSet<_>>();

        let first_set = extension_sets.first().expect("at least one capture");
        assert!(
            extension_sets.iter().all(|set| set == first_set),
            "{profile}: permuted family changed the extension set instead of only the order"
        );
        assert!(
            distinct_orders.len() > 1,
            "{profile}: expected more than one on-wire extension order for a permuted family"
        );
    }
}

#[test]
fn browser_template_record_choreography_rewrites_live_client_hellos() {
    let expectations = [("chrome_desktop_stable", 2usize), ("firefox_stable", 2usize), ("firefox_ech_stable", 2usize)];

    for (profile, expected_records) in expectations {
        let capture = capture_client_hello(profile);
        let rewritten = apply_record_choreography(profile, &capture.bytes).expect("rewrite captured ClientHello");
        let lengths = tls_record_payload_lengths(&rewritten);

        assert_eq!(lengths.len(), expected_records, "{profile}: unexpected record count after choreography");
        assert_eq!(
            flatten_tls_record_payload(&rewritten),
            capture.bytes[5..].to_vec(),
            "{profile}: choreography must preserve the ClientHello payload bytes",
        );
        assert_eq!(
            Some(lengths.clone()),
            planned_record_payload_lengths(profile, &capture.bytes),
            "{profile}: planned lengths should match the serialized record layout",
        );
    }
}

#[test]
fn browser_template_record_choreography_uses_semantic_markers() {
    let chrome = capture_client_hello("chrome_desktop_stable");
    let chrome_lengths =
        planned_record_payload_lengths("chrome_desktop_stable", &chrome.bytes).expect("chrome planned lengths");
    assert_eq!(
        chrome_lengths,
        vec![
            chrome.layout.markers.host_end - 5,
            chrome.layout.record_payload_len - (chrome.layout.markers.host_end - 5),
        ],
    );

    let firefox = capture_client_hello("firefox_stable");
    let firefox_lengths = planned_record_payload_lengths("firefox_stable", &firefox.bytes).expect("firefox lengths");
    assert_eq!(
        firefox_lengths,
        vec![
            firefox.layout.markers.sni_ext_start - 5,
            firefox.layout.record_payload_len - (firefox.layout.markers.sni_ext_start - 5),
        ],
    );
}
