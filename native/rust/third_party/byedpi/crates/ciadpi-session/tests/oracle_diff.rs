use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::OnceLock;

use ciadpi_session::{
    detect_response_trigger, encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply,
    parse_http_connect_request, parse_socks4_request, parse_socks5_request, ClientRequest, SessionConfig, SocketType,
    TriggerEvent, S_ER_OK,
};
use serde_json::Value;

#[allow(dead_code)]
#[path = "../../../tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

fn fixtures() -> &'static Value {
    static FIXTURES: OnceLock<Value> = OnceLock::new();
    FIXTURES.get_or_init(|| {
        serde_json::from_str(include_str!("../../../tests/corpus/rust-fixtures/session_oracle.json"))
            .expect("session fixtures")
    })
}

fn fixture(case: &str) -> &'static Value {
    &fixtures()[case]
}

fn resolver(host: &str, _: SocketType) -> Option<SocketAddr> {
    match host {
        "localhost" => Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)),
        _ => None,
    }
}

fn target_addr(request: &ClientRequest) -> SocketAddr {
    match request {
        ClientRequest::Socks4Connect(target)
        | ClientRequest::Socks5Connect(target)
        | ClientRequest::Socks5UdpAssociate(target)
        | ClientRequest::HttpConnect(target) => target.addr,
    }
}

fn assert_target(request: &ClientRequest, expected: &Value) {
    let target = target_addr(request);
    assert_eq!(target.port() as u64, expected["port"].as_u64().unwrap());
    assert_eq!(
        match target {
            SocketAddr::V4(_) => "ipv4",
            SocketAddr::V6(_) => "ipv6",
        },
        expected["family"].as_str().unwrap()
    );
}

#[test]
fn socks4_request_matches_fixture() {
    let payload = [&[0x04, 0x01][..], &(8080u16.to_be_bytes()), &Ipv4Addr::LOCALHOST.octets(), b"user\0"].concat();
    let rust = parse_socks4_request(&payload, SessionConfig::default(), &resolver).expect("socks4 parse");
    assert_target(&rust, fixture("socks4"));
    assert_eq!(target_addr(&rust).ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
}

#[test]
fn socks4a_request_matches_fixture() {
    let payload = [&[0x04, 0x01][..], &(8081u16.to_be_bytes()), &[0, 0, 0, 1], b"user\0localhost\0"].concat();
    let rust = parse_socks4_request(&payload, SessionConfig::default(), &resolver).expect("socks4a parse");
    assert_target(&rust, fixture("socks4a"));
    assert_eq!(target_addr(&rust).ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
}

#[test]
fn socks5_connect_and_udp_match_fixture() {
    let connect = [&[0x05, 0x01, 0x00, 0x01][..], &Ipv4Addr::LOCALHOST.octets(), &(443u16.to_be_bytes())].concat();
    let dgram = [&[0x05, 0x03, 0x00, 0x01][..], &Ipv4Addr::LOCALHOST.octets(), &(5353u16.to_be_bytes())].concat();
    let connect_rust = parse_socks5_request(&connect, SocketType::Stream, SessionConfig::default(), &resolver)
        .expect("socks5 connect");
    let dgram_rust =
        parse_socks5_request(&dgram, SocketType::Datagram, SessionConfig::default(), &resolver).expect("socks5 udp");

    assert_target(&connect_rust, fixture("socks5_connect"));
    assert_target(&dgram_rust, fixture("socks5_udp"));
    assert_eq!(target_addr(&connect_rust).ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
    assert_eq!(target_addr(&dgram_rust).ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
}

#[test]
fn socks5_domain_and_ipv6_match_fixture() {
    let domain = b"localhost";
    let domain_payload = [&[0x05, 0x01, 0x00, 0x03, domain.len() as u8][..], domain, &(443u16.to_be_bytes())].concat();
    let mut ipv6_payload = vec![0x05, 0x01, 0x00, 0x04];
    ipv6_payload.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
    ipv6_payload.extend_from_slice(&9443u16.to_be_bytes());

    let domain_rust = parse_socks5_request(&domain_payload, SocketType::Stream, SessionConfig::default(), &resolver)
        .expect("domain request");
    let ipv6_rust = parse_socks5_request(&ipv6_payload, SocketType::Stream, SessionConfig::default(), &resolver)
        .expect("ipv6 request");

    assert_target(&domain_rust, fixture("socks5_domain"));
    assert_target(&ipv6_rust, fixture("socks5_ipv6"));
    assert_eq!(target_addr(&domain_rust).ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
    assert_eq!(target_addr(&ipv6_rust).ip(), IpAddr::V6(Ipv6Addr::LOCALHOST));
}

#[test]
fn http_connect_matches_fixture() {
    let payload = b"CONNECT 127.0.0.1:8443 HTTP/1.1\r\nHost: 127.0.0.1:8443\r\n\r\n".to_vec();
    let rust = parse_http_connect_request(&payload, &|host: &str, _| {
        Some(SocketAddr::new(host.parse::<IpAddr>().unwrap(), 0))
    })
    .expect("http connect");

    assert_target(&rust, fixture("http_connect"));
    assert_eq!(target_addr(&rust).ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
}

#[test]
fn invalid_socks5_request_matches_fixture_failure() {
    let payload = vec![0x05, 0x01, 0x00];
    let rust = parse_socks5_request(&payload, SocketType::Stream, SessionConfig::default(), &resolver);

    assert!(!fixture("invalid_socks5")["ok"].as_bool().unwrap());
    assert!(rust.is_err());
}

#[test]
fn reply_encoding_matches_c_contract() {
    let socks4 = encode_socks4_reply(true);
    assert_eq!(socks4.as_bytes(), &[0, 0x5a, 0, 0, 0, 0, 0, 0]);

    let socks5 = encode_socks5_reply(S_ER_OK, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 1080));
    assert_eq!(socks5.as_bytes(), &[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x04, 0x38]);

    let http = encode_http_connect_reply(true);
    assert_eq!(http.as_bytes(), b"HTTP/1.1 200 OK\r\n\r\n");
}

#[test]
fn trigger_detection_matches_packet_contract() {
    let request = rust_packet_seeds::http_request();
    let redirect = rust_packet_seeds::http_redirect_response();
    assert_eq!(detect_response_trigger(&request, &redirect), Some(TriggerEvent::Redirect));

    let tls_request = rust_packet_seeds::tls_client_hello();
    let tls_response = rust_packet_seeds::tls_server_hello_like();
    assert_eq!(detect_response_trigger(&tls_request, &tls_response), Some(TriggerEvent::SslErr));
}
