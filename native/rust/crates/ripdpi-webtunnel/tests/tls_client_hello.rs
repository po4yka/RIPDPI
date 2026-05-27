use std::fs;
use std::io::Read;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use golden_test_support::repo_root;
use ripdpi_packets::parse_tls_client_hello_layout;
use ripdpi_webtunnel::bridge_line::parse_bridge_line;
use ripdpi_webtunnel::tls::build_tls_connector;
use serde_json::{json, Value};

const CLIENT_HELLO_FIXTURE: &str = "contract-fixtures/webtunnel_client_hello_chrome_parity.json";
const EXT_SUPPORTED_GROUPS: u16 = 0x000a;
const EXT_ALPN: u16 = 0x0010;
const EXT_KEY_SHARE: u16 = 0x0033;

#[test]
fn webtunnel_client_hello_matches_chrome_utls_target_fixture() {
    let config =
        parse_bridge_line("Bridge webtunnel 192.0.2.3:1 url=https://front.example/secret utls=hellochrome_auto")
            .expect("parse bridge line");
    let bytes = capture_client_hello(&config.servername);
    let actual = normalized_client_hello(&bytes, &config.servername, &config.utls, &config.tls_profile);
    let fixture_path = repo_root().join(CLIENT_HELLO_FIXTURE);

    if std::env::var_os("RIPDPI_REGENERATE_WEBTUNNEL_CLIENT_HELLO_FIXTURE").is_some() {
        fs::write(&fixture_path, serde_json::to_string_pretty(&actual).expect("serialize fixture") + "\n")
            .expect("write WebTunnel ClientHello fixture");
        return;
    }

    let expected = fs::read_to_string(&fixture_path).expect("read WebTunnel ClientHello fixture");
    let expected: Value = serde_json::from_str(&expected).expect("parse WebTunnel ClientHello fixture");
    assert_eq!(
        expected, actual,
        "WebTunnel ClientHello drifted from the reviewed hellochrome_auto structural parity target"
    );
}

fn capture_client_hello(server_name: &str) -> Vec<u8> {
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

    let config = parse_bridge_line(&format!(
        "Bridge webtunnel 192.0.2.3:1 url=https://{server_name}/secret utls=hellochrome_auto"
    ))
    .expect("parse capture bridge line");
    let connector = build_tls_connector(&config, false).expect("build WebTunnel TLS connector");
    let stream = TcpStream::connect(addr).expect("connect loopback socket");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set client read timeout");
    stream.set_write_timeout(Some(Duration::from_secs(5))).expect("set client write timeout");
    let _ = connector.connect(server_name, stream);

    server.join().expect("server join")
}

fn normalized_client_hello(bytes: &[u8], server_name: &str, utls: &str, tls_profile: &str) -> Value {
    let layout = parse_tls_client_hello_layout(bytes).expect("parse captured ClientHello");
    let extension_order = layout.extensions.iter().map(|extension| extension.ext_type).collect::<Vec<_>>();
    let supported_groups = parse_u16_list(extension_bytes(bytes, &layout, EXT_SUPPORTED_GROUPS));
    let key_share_groups = parse_key_share_groups(extension_bytes(bytes, &layout, EXT_KEY_SHARE));
    let alpn_protocols = parse_alpn_protocols(extension_bytes(bytes, &layout, EXT_ALPN));
    let captured_sni =
        std::str::from_utf8(&bytes[layout.markers.host_start..layout.markers.host_end]).expect("SNI host utf8");

    json!({
        "schemaVersion": 1,
        "source": {
            "goTarget": "lyrebird hellochrome_auto via uTLS",
            "rustBackend": "boring",
            "parityMode": "structural"
        },
        "bridge": {
            "servername": server_name,
            "utls": utls,
            "tlsProfile": tls_profile
        },
        "clientHello": {
            "contentType": hex_bytes(&bytes[0..1]),
            "recordLegacyVersion": hex_bytes(&bytes[1..3]),
            "handshakeType": hex_bytes(&bytes[5..6]),
            "clientHelloLegacyVersion": hex_bytes(&bytes[9..11]),
            "sniHost": captured_sni,
            "alpnProtocols": alpn_protocols.iter().map(|protocol| hex_bytes(protocol)).collect::<Vec<_>>(),
            "extensionTypesNoGreaseSorted": sorted_no_grease(&extension_order).into_iter().map(hex_u16).collect::<Vec<_>>(),
            "greaseExtensionCount": extension_order.iter().filter(|ext_type| is_grease_value(**ext_type)).count(),
            "supportedGroupsNoGrease": strip_grease(&supported_groups).into_iter().map(hex_u16).collect::<Vec<_>>(),
            "greaseSupportedGroupCount": supported_groups.iter().filter(|group| is_grease_value(**group)).count(),
            "keyShareGroupsNoGrease": strip_grease(&key_share_groups).into_iter().map(hex_u16).collect::<Vec<_>>(),
            "greaseKeyShareGroupCount": key_share_groups.iter().filter(|group| is_grease_value(**group)).count()
        }
    })
}

fn extension_bytes<'a>(bytes: &'a [u8], layout: &ripdpi_packets::TlsClientHelloLayout, ext_type: u16) -> &'a [u8] {
    let extension = layout
        .extensions
        .iter()
        .find(|extension| extension.ext_type == ext_type)
        .unwrap_or_else(|| panic!("extension 0x{ext_type:04x} not present"));
    &bytes[extension.data_offset..extension.data_offset + extension.data_len]
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

fn parse_u16_list(payload: &[u8]) -> Vec<u16> {
    if payload.len() < 2 {
        return Vec::new();
    }
    let list_len = u16::from_be_bytes([payload[0], payload[1]]) as usize;
    let mut values = Vec::new();
    let mut index = 2_usize;
    let list_end = 2 + list_len.min(payload.len().saturating_sub(2));
    while index + 1 < list_end {
        values.push(u16::from_be_bytes([payload[index], payload[index + 1]]));
        index += 2;
    }
    values
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

fn is_grease_value(value: u16) -> bool {
    let [hi, lo] = value.to_be_bytes();
    hi == lo && (hi & 0x0f) == 0x0a
}

fn strip_grease(values: &[u16]) -> Vec<u16> {
    values.iter().copied().filter(|value| !is_grease_value(*value)).collect()
}

fn sorted_no_grease(values: &[u16]) -> Vec<u16> {
    let mut values = strip_grease(values);
    values.sort_unstable();
    values
}

fn hex_u16(value: u16) -> String {
    format!("{value:04x}")
}

fn hex_bytes(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect::<String>()
}
