use super::protocol_io::*;
use ripdpi_config::RuntimeConfig;
use ripdpi_session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, S_ATP_I4, S_ATP_I6, S_CMD_CONN, S_VER5,
};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::time::Duration;

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
    let addr = listener.local_addr().expect("listener addr");
    let client = TcpStream::connect(addr).expect("connect client");
    let (server, _) = listener.accept().expect("accept client");
    (client, server)
}

#[test]
fn send_success_reply_emits_protocol_specific_payloads() {
    let cases = [
        (HandshakeKind::Socks4, encode_socks4_reply(true).as_bytes().to_vec()),
        (
            HandshakeKind::Socks5,
            encode_socks5_reply(0, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).as_bytes().to_vec(),
        ),
        (HandshakeKind::HttpConnect, encode_http_connect_reply(true).as_bytes().to_vec()),
    ];

    for (handshake, expected) in cases {
        let (mut writer, mut reader) = connected_pair();
        reader.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

        send_success_reply(&mut writer, handshake).expect("send success reply");

        let mut actual = vec![0u8; expected.len()];
        reader.read_exact(&mut actual).expect("read success reply");
        assert_eq!(actual, expected);
    }
}

#[test]
fn read_socks5_request_reads_domain_target() {
    let (mut reader, mut writer) = connected_pair();
    let request =
        [S_VER5, S_CMD_CONN, 0, 0x03, 11, b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm', 0x01, 0xbb];
    writer.write_all(&request).expect("write socks5 request");

    assert_eq!(read_socks5_request(&mut reader).expect("read socks5 request"), request);
}

#[test]
fn parse_shadowsocks_target_handles_ipv4_and_resolved_domain_targets() {
    let config = RuntimeConfig::default();
    let ipv4_packet = [S_ATP_I4, 127, 0, 0, 1, 0x01, 0xbb];
    let (ipv4_target, ipv4_header_len) = parse_shadowsocks_target(&ipv4_packet, &config).expect("parse ipv4 target");
    assert_eq!(ipv4_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
    assert_eq!(ipv4_header_len, ipv4_packet.len());

    let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0x00, 0x50];
    let (domain_target, domain_header_len) =
        parse_shadowsocks_target(&domain_packet, &config).expect("parse domain target");
    assert_eq!(domain_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 80));
    assert_eq!(domain_header_len, domain_packet.len());
}

#[test]
fn parse_shadowsocks_target_respects_ipv6_and_resolve_flags() {
    let mut config = RuntimeConfig::default();
    config.network.ipv6 = false;
    config.network.resolve = false;
    let ipv6_packet = [S_ATP_I6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53];
    let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0, 80];

    assert!(parse_shadowsocks_target(&ipv6_packet, &config).is_none());
    assert!(parse_shadowsocks_target(&domain_packet, &config).is_none());
}
