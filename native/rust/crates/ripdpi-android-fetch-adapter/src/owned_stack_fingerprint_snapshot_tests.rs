use std::collections::BTreeSet;
use std::fs;
use std::io::Read;
use std::net::{Shutdown, TcpListener};
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

use golden_test_support::repo_root;
use ripdpi_packets::{TlsClientHelloLayout, parse_tls_client_hello_layout};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tokio::net::TcpStream;

use crate::tls_profile::connect_tls;

const OWNED_STACK_TLS_FINGERPRINT_FIXTURE: &str = "contract-fixtures/owned_stack_tls_fingerprint_snapshot.json";
const OWNED_STACK_PROFILE: &str = "chrome_stable";
const FIXTURE_SERVER_NAME: &str = "owned-stack-fixture.test";
const EXT_SUPPORTED_GROUPS: u16 = 0x000a;
const EXT_ALPN: u16 = 0x0010;
const EXT_SUPPORTED_VERSIONS: u16 = 0x002b;
const EXT_KEY_SHARE: u16 = 0x0033;
const TLS_VERSION_13: u16 = 0x0304;
const X25519_MLKEM768: u16 = 0x11ec;

#[derive(Debug)]
struct CapturedClientHello {
    bytes: Vec<u8>,
    layout: TlsClientHelloLayout,
}

#[test]
fn owned_stack_tls_fingerprint_snapshot_matches_fixture() {
    let actual = owned_stack_tls_fingerprint_snapshot();
    let path = fixture_path();

    if std::env::var_os("RIPDPI_REGENERATE_OWNED_STACK_TLS_FINGERPRINT_FIXTURE").is_some() {
        let serialized = serde_json::to_string_pretty(&actual).expect("serialize fixture") + "\n";
        fs::write(&path, serialized).expect("write regenerated owned-stack TLS fingerprint fixture");
        return;
    }

    let fixture = fs::read_to_string(&path).expect("read owned-stack TLS fingerprint fixture");
    let expected: Value = serde_json::from_str(&fixture).expect("parse owned-stack TLS fingerprint fixture");
    assert_eq!(
        expected,
        actual,
        "owned-stack TLS JA3/JA4 snapshot drifted; review the ClientHello diff before regenerating with \
         RIPDPI_REGENERATE_OWNED_STACK_TLS_FINGERPRINT_FIXTURE=1\n{}",
        serde_json::to_string_pretty(&actual).expect("format actual fixture")
    );
}

#[test]
fn owned_stack_tls_fingerprint_requires_x25519mlkem768_key_share() {
    let capture = capture_owned_stack_client_hello();
    let key_share_groups = parse_key_share_groups(extension_bytes(&capture, EXT_KEY_SHARE));
    assert!(
        strip_grease(&key_share_groups).contains(&X25519_MLKEM768),
        "owned-stack ClientHello must advertise X25519MLKEM768 in key_share"
    );
}

fn owned_stack_tls_fingerprint_snapshot() -> Value {
    let capture = capture_owned_stack_client_hello();
    let cipher_suites = parse_cipher_suites(&capture.bytes);
    let extension_order = extension_order(&capture);
    let extension_types_no_grease_sorted = extension_order
        .iter()
        .copied()
        .filter(|ext_type| !is_grease_value(*ext_type))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .map(hex_u16)
        .collect::<Vec<_>>();
    let supported_groups = parse_named_group_list(extension_bytes(&capture, EXT_SUPPORTED_GROUPS));
    let key_share_groups = parse_key_share_groups(extension_bytes(&capture, EXT_KEY_SHARE));
    let alpn_protocols = parse_alpn_protocols(extension_bytes(&capture, EXT_ALPN));
    let supported_versions = parse_supported_versions(extension_bytes(&capture, EXT_SUPPORTED_VERSIONS));
    let normalized = NormalizedFingerprintInput {
        legacy_version: client_hello_legacy_version(&capture.bytes),
        cipher_suites_no_grease: strip_grease(&cipher_suites),
        extension_types_no_grease_sorted: strip_grease(&extension_order)
            .into_iter()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect(),
        supported_groups_no_grease: strip_grease(&supported_groups),
        alpn_protocols: alpn_protocols.clone(),
        supported_versions: strip_grease(&supported_versions),
    };
    let key_share_groups_no_grease = strip_grease(&key_share_groups);

    json!({
        "schemaVersion": 1,
        "corpusId": "owned_stack_tls_fingerprint_snapshot",
        "catalogVersion": ripdpi_tls_profiles::profile_catalog_version(),
        "ownedStackPath": "native_owned_tls_fetcher",
        "tlsProfileId": OWNED_STACK_PROFILE,
        "fixtureEndpoint": {
            "transport": "loopback",
            "serverName": FIXTURE_SERVER_NAME,
            "externalNetwork": false
        },
        "normalization": {
            "randomSessionAndKeyShareBytesExcluded": true,
            "greaseValuesExcluded": true,
            "extensionOrderSortedForJa4": true,
            "fixtureContainsNoSecrets": true
        },
        "snapshot": {
            "ja3NormalizedHash": ja3_normalized_hash(&normalized),
            "ja4RipdpiV1": ja4_ripdpi_v1(&normalized),
            "ja4RipdpiV1Hash": sha256_hex(ja4_ripdpi_v1(&normalized).as_bytes()),
            "recordPayloadLen": capture.layout.record_payload_len,
            "handshakePayloadLen": capture.layout.handshake_payload_len,
            "clientHelloLegacyVersion": hex_u16(normalized.legacy_version),
            "highestSupportedVersion": hex_u16(highest_supported_version(&normalized.supported_versions)),
            "alpnProtocols": alpn_protocols.iter().map(|protocol| hex_bytes(protocol)).collect::<Vec<_>>(),
            "cipherSuitesNoGrease": normalized.cipher_suites_no_grease.iter().copied().map(hex_u16).collect::<Vec<_>>(),
            "extensionTypesNoGreaseSorted": extension_types_no_grease_sorted,
            "supportedGroupsNoGrease": normalized.supported_groups_no_grease.iter().copied().map(hex_u16).collect::<Vec<_>>(),
            "keyShareGroupsNoGrease": key_share_groups_no_grease.iter().copied().map(hex_u16).collect::<Vec<_>>(),
            "requiresX25519Mlkem768KeyShare": true,
            "containsX25519Mlkem768KeyShare": key_share_groups_no_grease.contains(&X25519_MLKEM768)
        }
    })
}

fn capture_owned_stack_client_hello() -> CapturedClientHello {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
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

    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio runtime");
    runtime.block_on(async move {
        let tcp = TcpStream::connect(addr).await.expect("connect loopback socket");
        let _ = connect_tls(FIXTURE_SERVER_NAME, tcp, 5_000, OWNED_STACK_PROFILE).await;
    });

    let bytes = server.join().expect("server join");
    let layout = parse_tls_client_hello_layout(&bytes).expect("parse captured owned-stack ClientHello");
    CapturedClientHello { bytes, layout }
}

fn fixture_path() -> PathBuf {
    repo_root().join(OWNED_STACK_TLS_FINGERPRINT_FIXTURE)
}

fn extension_order(capture: &CapturedClientHello) -> Vec<u16> {
    capture.layout.extensions.iter().map(|extension| extension.ext_type).collect()
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

fn parse_cipher_suites(bytes: &[u8]) -> Vec<u16> {
    let mut index = 11 + 32;
    let session_id_len = bytes[index] as usize;
    index += 1 + session_id_len;
    let cipher_suites_len = u16::from_be_bytes([bytes[index], bytes[index + 1]]) as usize;
    index += 2;
    bytes[index..index + cipher_suites_len]
        .as_chunks::<2>()
        .0
        .iter()
        .map(|chunk| u16::from_be_bytes([chunk[0], chunk[1]]))
        .collect()
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

fn parse_supported_versions(payload: &[u8]) -> Vec<u16> {
    if payload.is_empty() {
        return Vec::new();
    }
    let list_len = payload[0] as usize;
    let mut versions = Vec::new();
    let mut index = 1_usize;
    let list_end = 1 + list_len.min(payload.len().saturating_sub(1));
    while index + 1 < list_end {
        versions.push(u16::from_be_bytes([payload[index], payload[index + 1]]));
        index += 2;
    }
    versions
}

fn client_hello_legacy_version(bytes: &[u8]) -> u16 {
    u16::from_be_bytes([bytes[9], bytes[10]])
}

fn highest_supported_version(versions: &[u16]) -> u16 {
    versions.iter().copied().max().unwrap_or(TLS_VERSION_13)
}

fn is_grease_value(value: u16) -> bool {
    let [hi, lo] = value.to_be_bytes();
    hi == lo && (hi & 0x0f) == 0x0a
}

fn strip_grease(values: &[u16]) -> Vec<u16> {
    values.iter().copied().filter(|value| !is_grease_value(*value)).collect()
}

fn hex_u16(value: u16) -> String {
    format!("{value:04x}")
}

fn hex_bytes(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect::<String>()
}

struct NormalizedFingerprintInput {
    legacy_version: u16,
    cipher_suites_no_grease: Vec<u16>,
    extension_types_no_grease_sorted: Vec<u16>,
    supported_groups_no_grease: Vec<u16>,
    alpn_protocols: Vec<Vec<u8>>,
    supported_versions: Vec<u16>,
}

fn ja3_normalized_hash(input: &NormalizedFingerprintInput) -> String {
    let ja3 = format!(
        "{},{},{},{},",
        input.legacy_version,
        join_decimal(&input.cipher_suites_no_grease),
        join_decimal(&input.extension_types_no_grease_sorted),
        join_decimal(&input.supported_groups_no_grease),
    );
    format!("{:x}", md5::compute(ja3.as_bytes()))
}

fn ja4_ripdpi_v1(input: &NormalizedFingerprintInput) -> String {
    let transport = "t";
    let tls_version = if highest_supported_version(&input.supported_versions) == TLS_VERSION_13 { "13" } else { "12" };
    let sni_flag = "d";
    let cipher_count = input.cipher_suites_no_grease.len();
    let extension_count = input.extension_types_no_grease_sorted.len();
    let alpn = input
        .alpn_protocols
        .iter()
        .map(|protocol| String::from_utf8_lossy(protocol).into_owned())
        .collect::<Vec<_>>()
        .join(",");
    let cipher_hash = sha256_hex(join_decimal(&input.cipher_suites_no_grease).as_bytes());
    let extension_hash = sha256_hex(
        format!(
            "{}_{}",
            join_decimal(&input.extension_types_no_grease_sorted),
            join_decimal(&input.supported_groups_no_grease),
        )
        .as_bytes(),
    );
    format!(
        "{}{}{}{:02}{:02}{}_{}_{}",
        transport,
        tls_version,
        sni_flag,
        cipher_count,
        extension_count,
        alpn,
        &cipher_hash[..12],
        &extension_hash[..12],
    )
}

fn join_decimal(values: &[u16]) -> String {
    values.iter().map(u16::to_string).collect::<Vec<_>>().join("-")
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect()
}
