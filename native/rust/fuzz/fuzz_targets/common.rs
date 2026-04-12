#![allow(dead_code)]

use std::sync::OnceLock;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

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

pub fn tunnel_config_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let minimal = "socks5:\n  port: 1080\n  address: 127.0.0.1\n";
        let _ = minimal.parse::<ripdpi_tunnel_config::Config>();

        let with_credentials =
            "socks5:\n  port: 1080\n  address: 127.0.0.1\n  username: user\n  password: pass\n";
        let _ = with_credentials.parse::<ripdpi_tunnel_config::Config>();

        let invalid = "socks5:\n  port: 1080\n  username: user\n";
        let _ = invalid.parse::<ripdpi_tunnel_config::Config>();
    });
}

pub fn tunnel_config_yaml_from_bytes(data: &[u8]) -> String {
    let address = format!("127.0.0.{}", 1 + data.first().copied().unwrap_or(0) % 200);
    let port = 1 + u16::from(data.get(1).copied().unwrap_or(0));
    let username = ascii_label(data.get(2..).unwrap_or_default(), "user", 8);
    let password = ascii_label(data.get(3..).unwrap_or_default(), "pass", 8);

    match data.first().copied().unwrap_or(0) % 6 {
        0 => format!("socks5:\n  port: {port}\n  address: {address}\n"),
        1 => format!(
            "socks5:\n  port: {port}\n  address: {address}\n  username: {username}\n  password: {password}\n"
        ),
        2 => format!("socks5:\n  address: {address}\n"),
        3 => format!("socks5:\n  port: {port}\n"),
        4 => format!("socks5:\n  port: {port}\n  address: {address}\n  username: {username}\n"),
        _ => String::from_utf8_lossy(data).into_owned(),
    }
}

pub fn session_resolver(host: &str, socket_type: ripdpi_session::SocketType) -> Option<SocketAddr> {
    match (host, socket_type) {
        ("example.com", ripdpi_session::SocketType::Stream) => Some(SocketAddr::from(([198, 51, 100, 10], 0))),
        ("example.net", ripdpi_session::SocketType::Datagram) => Some(SocketAddr::from(([198, 51, 100, 20], 0))),
        ("ipv6.example", ripdpi_session::SocketType::Stream) => {
            Some(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 0))
        }
        _ => None,
    }
}

pub fn session_request_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let resolver = session_resolver;
        let config = ripdpi_session::SessionConfig::default();

        let socks4 = b"\x04\x01\x01\xbb\x00\x00\x00\x01user\x00example.com\x00";
        let _ = ripdpi_session::parse_socks4_request(socks4, config, &resolver);

        let socks5 = [
            ripdpi_session::S_VER5,
            ripdpi_session::S_CMD_CONN,
            0,
            ripdpi_session::S_ATP_ID,
            11,
            b'e',
            b'x',
            b'a',
            b'm',
            b'p',
            b'l',
            b'e',
            b'.',
            b'c',
            b'o',
            b'm',
            0x01,
            0xbb,
        ];
        let _ = ripdpi_session::parse_socks5_request(
            &socks5,
            ripdpi_session::SocketType::Stream,
            config,
            &resolver,
        );

        let http_connect = b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
        let _ = ripdpi_session::parse_http_connect_request(http_connect, &resolver);
    });
}

pub fn socks4_request_from_bytes(data: &[u8]) -> Vec<u8> {
    let port = 1 + u16::from_be_bytes([data.first().copied().unwrap_or(0), data.get(1).copied().unwrap_or(0)]);
    let user = ascii_label(data.get(2..).unwrap_or_default(), "user", 12);

    if data.first().copied().unwrap_or(0) & 0x1 == 0 {
        let host = if data.get(2).copied().unwrap_or(0) & 0x1 == 0 { "example.com" } else { "example.net" };
        let mut request = vec![ripdpi_session::S_VER4, ripdpi_session::S_CMD_CONN];
        request.extend_from_slice(&port.to_be_bytes());
        request.extend_from_slice(&[0, 0, 0, 1]);
        request.extend_from_slice(user.as_bytes());
        request.push(0);
        request.extend_from_slice(host.as_bytes());
        request.push(0);
        request
    } else {
        let ipv4 = Ipv4Addr::new(1, data.get(2).copied().unwrap_or(0), data.get(3).copied().unwrap_or(0), 1);
        let mut request = vec![ripdpi_session::S_VER4, ripdpi_session::S_CMD_CONN];
        request.extend_from_slice(&port.to_be_bytes());
        request.extend_from_slice(&ipv4.octets());
        request.extend_from_slice(user.as_bytes());
        request.push(0);
        request
    }
}

pub fn socks5_request_from_bytes(data: &[u8]) -> Vec<u8> {
    let port = 1 + u16::from_be_bytes([data.first().copied().unwrap_or(0), data.get(1).copied().unwrap_or(0)]);
    let cmd =
        if data.get(2).copied().unwrap_or(0) & 0x1 == 0 {
            ripdpi_session::S_CMD_CONN
        } else {
            ripdpi_session::S_CMD_AUDP
        };

    let mut request = vec![ripdpi_session::S_VER5, cmd, 0];
    match data.get(3).copied().unwrap_or(0) % 3 {
        0 => {
            request.push(ripdpi_session::S_ATP_I4);
            request.extend_from_slice(&[
                198,
                51,
                100,
                data.get(4).copied().unwrap_or(10),
            ]);
        }
        1 => {
            let host = if data.get(4).copied().unwrap_or(0) & 0x1 == 0 { "example.com" } else { "example.net" };
            request.push(ripdpi_session::S_ATP_ID);
            request.push(host.len() as u8);
            request.extend_from_slice(host.as_bytes());
        }
        _ => {
            request.push(ripdpi_session::S_ATP_I6);
            request.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
        }
    }
    request.extend_from_slice(&port.to_be_bytes());
    request
}

pub fn http_connect_request_from_bytes(data: &[u8]) -> String {
    let host =
        match data.first().copied().unwrap_or(0) % 3 {
            0 => "example.com",
            1 => "example.net",
            _ => "ipv6.example",
        };
    let port = 1 + u16::from_be_bytes([data.get(1).copied().unwrap_or(0), data.get(2).copied().unwrap_or(0)]);
    let request_target =
        if data.get(3).copied().unwrap_or(0) & 0x1 == 0 {
            format!("{host}:{port}")
        } else {
            format!("[{host}]:{port}")
        };

    format!("CONNECT {request_target} HTTP/1.1\r\nHost: {request_target}\r\n\r\n")
}

fn push_dns_name(packet: &mut Vec<u8>, name: &str) {
    for label in name.split('.') {
        packet.push(label.len() as u8);
        packet.extend_from_slice(label.as_bytes());
    }
    packet.push(0);
}

pub fn dns_response_smoke() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let a_response = dns_response_packet_from_bytes(&[0, 0, 0, 0, 0, 0]);
        let _ = ripdpi_dns_resolver::extract_ip_answers(&a_response);

        let aaaa_response = dns_response_packet_from_bytes(&[1, 1, 1, 1, 1, 1, 1, 1]);
        let _ = ripdpi_dns_resolver::extract_ip_answers(&aaaa_response);

        let malformed = b"\x12\x34\x81\x80\x00\x01\x00\x01";
        let _ = ripdpi_dns_resolver::extract_ip_answers(malformed);
    });
}

pub fn dns_response_packet_from_bytes(data: &[u8]) -> Vec<u8> {
    let id = u16::from_be_bytes([data.first().copied().unwrap_or(0x12), data.get(1).copied().unwrap_or(0x34)]);
    let answer_count = 1 + usize::from(data.get(2).copied().unwrap_or(0) % 3);
    let question_name =
        match data.get(3).copied().unwrap_or(0) % 3 {
            0 => "example.com",
            1 => "ipv6.example",
            _ => "test.local",
        };

    let mut packet = Vec::with_capacity(128);
    packet.extend_from_slice(&id.to_be_bytes());
    packet.extend_from_slice(&0x8180u16.to_be_bytes());
    packet.extend_from_slice(&1u16.to_be_bytes());
    packet.extend_from_slice(&(answer_count as u16).to_be_bytes());
    packet.extend_from_slice(&0u16.to_be_bytes());
    packet.extend_from_slice(&0u16.to_be_bytes());

    push_dns_name(&mut packet, question_name);
    let question_type =
        if data.get(4).copied().unwrap_or(0) & 0x1 == 0 {
            1u16
        } else {
            28u16
        };
    packet.extend_from_slice(&question_type.to_be_bytes());
    packet.extend_from_slice(&1u16.to_be_bytes());

    for index in 0..answer_count {
        let selector = data.get(5 + index).copied().unwrap_or(0) % 4;
        let use_pointer = data.get(8 + index).copied().unwrap_or(0) & 0x1 == 0;
        if use_pointer {
            packet.extend_from_slice(&[0xC0, 0x0C]);
        } else {
            push_dns_name(&mut packet, question_name);
        }

        match selector {
            0 => {
                packet.extend_from_slice(&1u16.to_be_bytes());
                packet.extend_from_slice(&1u16.to_be_bytes());
                packet.extend_from_slice(&60u32.to_be_bytes());
                packet.extend_from_slice(&4u16.to_be_bytes());
                packet.extend_from_slice(&[
                    198,
                    18,
                    data.get(11 + index).copied().unwrap_or(0),
                    1 + index as u8,
                ]);
            }
            1 => {
                packet.extend_from_slice(&28u16.to_be_bytes());
                packet.extend_from_slice(&1u16.to_be_bytes());
                packet.extend_from_slice(&60u32.to_be_bytes());
                packet.extend_from_slice(&16u16.to_be_bytes());
                packet.extend_from_slice(&[
                    0x20,
                    0x01,
                    0x0d,
                    0xb8,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    data.get(12 + index).copied().unwrap_or(0),
                    0,
                    0,
                    index as u8,
                ]);
            }
            2 => {
                packet.extend_from_slice(&5u16.to_be_bytes());
                packet.extend_from_slice(&1u16.to_be_bytes());
                packet.extend_from_slice(&60u32.to_be_bytes());
                let mut cname = Vec::new();
                push_dns_name(&mut cname, "alias.example");
                packet.extend_from_slice(&(cname.len() as u16).to_be_bytes());
                packet.extend_from_slice(&cname);
            }
            _ => {
                packet.extend_from_slice(&1u16.to_be_bytes());
                packet.extend_from_slice(&1u16.to_be_bytes());
                packet.extend_from_slice(&60u32.to_be_bytes());
                packet.extend_from_slice(&16u16.to_be_bytes());
                packet.extend_from_slice(&[
                    198,
                    18,
                    data.get(13 + index).copied().unwrap_or(0),
                ]);
            }
        }
    }

    if data.get(20).copied().unwrap_or(0) & 0x1 != 0 {
        packet.extend_from_slice(&[0xC0]);
    }

    packet
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
