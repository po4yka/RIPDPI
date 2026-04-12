#![allow(dead_code)]

use std::sync::OnceLock;

use ripdpi_failure_classifier::{bundled_blockpage_fingerprints, classify_http_response_block};
use ripdpi_packets::fields::{FieldCache, FieldObserver, ProtocolField};

pub fn ascii_label(bytes: &[u8], prefix: &str, max_len: usize) -> String {
    let mut label = String::with_capacity(prefix.len() + bytes.len().min(max_len));
    label.push_str(prefix);
    for &byte in bytes.iter().take(max_len) {
        let digit = byte % 36;
        let ch = if digit < 10 {
            (b'0' + digit) as char
        } else {
            (b'a' + (digit - 10)) as char
        };
        label.push(ch);
    }
    label
}

pub fn packet_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let _ = ripdpi_packets::parse_http(ripdpi_packets::DEFAULT_FAKE_HTTP);
        let _ = ripdpi_packets::parse_tls(ripdpi_packets::DEFAULT_FAKE_TLS);

        for version in [ripdpi_packets::QUIC_V1_VERSION, ripdpi_packets::QUIC_V2_VERSION] {
            if let Some(packet) = ripdpi_packets::build_realistic_quic_initial(version, Some("fuzz.example")) {
                let _ = ripdpi_packets::parse_quic_initial(&packet);
            }
        }
    });
}

pub fn failure_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let response = b"HTTP/1.1 403 Forbidden\r\nServer: fuzz\r\n\r\nAccess denied";
        let _ = classify_http_response_block(response);

        let mut cache = FieldCache::new();
        let status = ProtocolField::HttpStatusCode(403);
        cache.on_field(&status);
        let body = ProtocolField::HttpBodyChunk(b"Access denied".to_vec());
        cache.on_field(&body);
        let _ = ripdpi_failure_classifier::field_classifier::classify_from_fields(&cache, bundled_blockpage_fingerprints());
    });
}

pub fn vless_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let uuid = [0x11_u8; 16];
        let domain_request = ripdpi_vless::wire::encode_request(&uuid, &[0xAA], "example.com:443");
        let _ = ripdpi_vless::wire::parse_request_header(&domain_request);

        let ipv6_request = ripdpi_vless::wire::encode_request(&uuid, &[], "[2001:db8::1]:8443");
        let _ = ripdpi_vless::wire::parse_request_header(&ipv6_request);
    });
}

pub fn proxy_config_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let valid_ui = r#"{"kind":"ui","strategyPreset":null,"config":{"protocols":{"desyncUdp":true},"chains":{"tcpSteps":[{"kind":"disorder","marker":"host+1","midhostMarker":"","fakeHostTemplate":"","overlapSize":0,"fakeMode":"","fragmentCount":0,"minFragmentSize":0,"maxFragmentSize":0,"interSegmentDelayMs":0,"activationFilter":null,"ipv6ExtensionProfile":"none"}]},"fakePackets":{"fakeSni":"www.wikipedia.org"}},"runtimeContext":null,"logContext":null}"#;
        let _ = ripdpi_proxy_config::parse_proxy_config_json(valid_ui);

        let valid_cli = r#"{"kind":"command_line","args":["--ip","127.0.0.1","-p","1080"],"runtimeContext":null,"logContext":null}"#;
        let _ = ripdpi_proxy_config::parse_proxy_config_json(valid_cli);

        let legacy_ui = r#"{"kind":"ui","ip":"127.0.0.1","port":1080,"desyncMethod":"disorder"}"#;
        let _ = ripdpi_proxy_config::parse_proxy_config_json(legacy_ui);
    });
}

pub fn proxy_config_json_from_bytes(data: &[u8]) -> String {
    let runtime_id = ascii_label(data, "rt-", 12);
    let fake_sni = format!("{}.example", ascii_label(data.get(1..).unwrap_or_default(), "host-", 12));
    let direct_ip = format!("127.0.0.{}", 1 + data.first().copied().unwrap_or(0) % 200);

    match data.first().copied().unwrap_or(0) % 4 {
        0 => format!(
            r#"{{"kind":"ui","strategyPreset":null,"config":{{"protocols":{{"desyncUdp":true}},"chains":{{"tcpSteps":[{{"kind":"disorder","marker":"host+1","midhostMarker":"","fakeHostTemplate":"","overlapSize":0,"fakeMode":"","fragmentCount":0,"minFragmentSize":0,"maxFragmentSize":0,"interSegmentDelayMs":0,"activationFilter":null,"ipv6ExtensionProfile":"none"}}]}},"fakePackets":{{"fakeSni":"{fake_sni}"}}}},"runtimeContext":null,"logContext":{{"runtimeId":"{runtime_id}"}}}}"#
        ),
        1 => format!(
            r#"{{"kind":"command_line","args":["--ip","{direct_ip}","-p","1080"],"runtimeContext":{{"protectPath":"/tmp/{runtime_id}"}},"logContext":{{"runtimeId":"{runtime_id}"}}}}"#
        ),
        2 => format!(
            r#"{{"kind":"ui","ip":"{direct_ip}","port":1080,"desyncMethod":"disorder","logContext":{{"runtimeId":"{runtime_id}"}}}}"#
        ),
        _ => String::from_utf8_lossy(data).into_owned(),
    }
}

pub fn http_response_from_bytes(data: &[u8]) -> Vec<u8> {
    let status = match data.first().copied().unwrap_or(0) % 5 {
        0 => 200,
        1 => 302,
        2 => 403,
        3 => 451,
        _ => 500,
    };
    let reason = match status {
        200 => "OK",
        302 => "Found",
        403 => "Forbidden",
        451 => "Unavailable For Legal Reasons",
        500 => "Internal Server Error",
        _ => "FUZZ",
    };

    let mut response = format!("HTTP/1.1 {status} {reason}\r\n").into_bytes();
    response.extend_from_slice(b"Server: fuzz\r\n");
    if status == 302 {
        let location = ascii_label(data.get(1..).unwrap_or_default(), "block", 20);
        response.extend_from_slice(b"Location: http://");
        response.extend_from_slice(location.as_bytes());
        response.extend_from_slice(b".example/\r\n");
    }
    if status == 403 {
        response.extend_from_slice(b"X-Reason: Access denied\r\n");
    }
    response.extend_from_slice(b"\r\n");
    response.extend_from_slice(data);
    response
}

pub fn field_cache_from_bytes(data: &[u8]) -> FieldCache {
    let mut cache = FieldCache::new();
    if data.is_empty() {
        return cache;
    }

    let status = match data[0] % 5 {
        0 => 200,
        1 => 302,
        2 => 403,
        3 => 451,
        _ => 500,
    };
    let status_field = ProtocolField::HttpStatusCode(status);
    cache.on_field(&status_field);

    let mut cursor = 1usize;
    let header_count = usize::from(data.get(cursor).copied().unwrap_or(0) % 3);
    cursor += usize::from(cursor < data.len());

    for index in 0..header_count {
        if cursor >= data.len() {
            break;
        }
        let name_len = 3 + usize::from(data[cursor] % 8);
        cursor += 1;
        let name_end = cursor.saturating_add(name_len).min(data.len());
        let name = ascii_label(&data[cursor..name_end], "x-fuzz-", 24);
        cursor = name_end;

        let value_len = if cursor < data.len() { 1 + usize::from(data[cursor] % 24) } else { 0 };
        if cursor < data.len() {
            cursor += 1;
        }
        let value_end = cursor.saturating_add(value_len).min(data.len());
        let value = ascii_label(&data[cursor..value_end], &format!("v{index}-"), 32);
        cursor = value_end;

        let header_field = ProtocolField::HttpHeader { name, value };
        cache.on_field(&header_field);
    }

    if cursor < data.len() {
        let body = ProtocolField::HttpBodyChunk(data[cursor..].to_vec());
        cache.on_field(&body);
    }

    if data.len() > 3 && data[0] & 0b10 != 0 {
        let alert = ProtocolField::TlsAlertCode(data[1] % 128);
        cache.on_field(&alert);
    }
    if data.len() > 4 && data[0] & 0b100 != 0 {
        let server_hello = ProtocolField::TlsServerHelloSeen;
        cache.on_field(&server_hello);
    }
    if status == 302 {
        let redirect = ProtocolField::HttpRedirectLocation(format!(
            "http://{}.example/",
            ascii_label(data.get(1..).unwrap_or_default(), "redir", 20)
        ));
        cache.on_field(&redirect);
    }

    cache
}
