use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use bytes::BytesMut;
use quinn::Endpoint;
use rcgen::generate_simple_self_signed;
use rustls::ServerConfig as RustlsServerConfig;
use rustls::pki_types::PrivateKeyDer;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use uuid::Uuid;

use crate::client::TuicClient;
use crate::config::Config;
use crate::endpoint::{build_tls_config, ensure_crypto_provider};
use crate::protocol::{COMMAND_AUTHENTICATE, COMMAND_CONNECT, PacketHeader, TUIC_VERSION, TuicAddress};

#[test]
fn unsupported_congestion_control_fails_before_connect() {
    let config = Config {
        server: "relay.example".to_owned(),
        server_port: 443,
        server_name: "relay.example".to_owned(),
        uuid: "00000000-0000-0000-0000-000000000000".to_owned(),
        password: "secret".to_owned(),
        zero_rtt: false,
        congestion_control: "silent-fallback".to_owned(),
        udp_enabled: true,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
        keepalive_interval_ms: 0,
        root_certificate_pem: None,
    };
    let error = crate::endpoint::validate_config(&config).expect_err("unknown mode must be rejected");
    assert_eq!(std::io::ErrorKind::InvalidInput, error.kind());
    assert!(error.to_string().contains("unsupported TUIC congestion control"));
}

#[test]
fn packet_header_round_trips() {
    let header = PacketHeader {
        assoc_id: 7,
        packet_id: 9,
        fragment_total: 2,
        fragment_id: 1,
        payload_len: 5,
        address: TuicAddress::Domain("relay.example".to_owned(), 443),
    };
    let mut buffer = BytesMut::new();
    header.encode(&mut buffer);
    buffer.extend_from_slice(b"hello");

    let (decoded, payload) = PacketHeader::decode(&buffer).expect("decode");
    assert_eq!(decoded.assoc_id, header.assoc_id);
    assert_eq!(decoded.packet_id, header.packet_id);
    assert_eq!(decoded.fragment_total, header.fragment_total);
    assert_eq!(decoded.fragment_id, header.fragment_id);
    assert_eq!(decoded.address, header.address);
    assert_eq!(payload, b"hello");
}

/// TUIC v5 packet header: VER, TYPE, ASSOC_ID(2), PKT_ID(2), FRAG_TOTAL,
/// FRAG_ID, SIZE(2), then the address (1 type byte for `None`).
#[test]
fn packet_header_encoded_len_matches_encoding() {
    let headers = [
        (TuicAddress::None, 11),
        (TuicAddress::Socket(SocketAddr::from((Ipv4Addr::LOCALHOST, 53))), 17),
        (TuicAddress::Domain("dns.example".to_owned(), 53), 10 + 1 + 1 + 11 + 2),
    ];
    for (address, expected) in headers {
        let header =
            PacketHeader { assoc_id: 1, packet_id: 2, fragment_total: 1, fragment_id: 0, payload_len: 0, address };
        let mut buffer = BytesMut::new();
        header.encode(&mut buffer);
        assert_eq!(buffer.len(), expected);
        assert_eq!(header.encoded_len(), expected);
    }
}

/// Reads the next UDP packet datagram, skipping Heartbeats as a server does.
async fn read_packet_datagram(connection: &quinn::Connection) -> bytes::Bytes {
    loop {
        let datagram = connection.read_datagram().await.expect("udp datagram");
        if datagram.as_ref() != [TUIC_VERSION, 0x04] {
            return datagram;
        }
    }
}

fn large_payload() -> Vec<u8> {
    (0..3000u32).map(|index| (index % 251) as u8).collect()
}

#[tokio::test]
async fn tuic_client_relays_tcp_and_udp() {
    ensure_crypto_provider();
    let certificate = generate_simple_self_signed(vec!["localhost".to_owned()]).expect("certificate");
    let cert_der = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
    let mut server_tls = RustlsServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der.clone()], key_der)
        .expect("server cert");
    server_tls.alpn_protocols = vec![b"h3".to_vec()];
    let server_config = quinn::ServerConfig::with_crypto(Arc::new(
        quinn::crypto::rustls::QuicServerConfig::try_from(server_tls).expect("quic server config"),
    ));
    let server = Endpoint::server(server_config, SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("endpoint");
    let server_addr = server.local_addr().expect("server addr");

    let expected_uuid = Uuid::parse_str("00000000-0000-0000-0000-000000000000").expect("uuid");
    let expected_token_seed = "tuic-fixture".to_owned();
    let server_token_seed = expected_token_seed.clone();
    let server_task = tokio::spawn(async move {
        let incoming = server.accept().await.expect("incoming");
        let connection = incoming.await.expect("connection");

        let mut auth_stream = connection.accept_uni().await.expect("auth stream");
        let mut auth_payload = Vec::new();
        auth_stream.read_to_end(1024).await.map(|bytes| auth_payload = bytes).expect("auth read");
        assert_eq!(auth_payload[0], TUIC_VERSION);
        assert_eq!(auth_payload[1], COMMAND_AUTHENTICATE);
        assert_eq!(Uuid::from_slice(&auth_payload[2..18]).expect("uuid"), expected_uuid);
        let mut expected_token = [0u8; 32];
        connection
            .export_keying_material(&mut expected_token, expected_uuid.as_bytes(), server_token_seed.as_bytes())
            .expect("export token");
        assert_eq!(&auth_payload[18..50], expected_token.as_slice());

        let tcp_conn = connection.clone();
        let tcp_task = tokio::spawn(async move {
            let (mut send, mut recv) = tcp_conn.accept_bi().await.expect("tcp stream");
            let mut header = [0u8; 2];
            recv.read_exact(&mut header).await.expect("connect header");
            assert_eq!(header, [TUIC_VERSION, COMMAND_CONNECT]);
            let mut address_payload = [0u8; 16];
            recv.read_exact(&mut address_payload).await.expect("connect target");
            let mut address_input = address_payload.as_slice();
            assert_eq!(
                TuicAddress::decode(&mut address_input).expect("decode target"),
                TuicAddress::Domain("echo.example".to_owned(), 443)
            );
            let mut buffer = [0u8; 5];
            recv.read_exact(&mut buffer).await.expect("tcp payload");
            assert_eq!(&buffer, b"hello");
            send.write_all(&buffer).await.expect("tcp echo");
            send.finish().expect("finish");
        });

        let udp_conn = connection.clone();
        let udp_task = tokio::spawn(async move {
            let datagram = read_packet_datagram(&udp_conn).await;
            let (header, payload) = PacketHeader::decode(&datagram).expect("decode packet");
            assert_eq!(payload, b"world");
            let response = PacketHeader {
                assoc_id: header.assoc_id,
                packet_id: header.packet_id,
                fragment_total: 1,
                fragment_id: 0,
                payload_len: payload.len() as u16,
                address: header.address,
            };
            let mut frame = BytesMut::with_capacity(response.encoded_len() + payload.len());
            response.encode(&mut frame);
            frame.extend_from_slice(payload);
            udp_conn.send_datagram(frame.freeze()).expect("send datagram");

            // A payload larger than one QUIC datagram arrives as fragments.
            let mut fragments = Vec::new();
            loop {
                let datagram = read_packet_datagram(&udp_conn).await;
                let (header, payload) = PacketHeader::decode(&datagram).expect("decode fragment");
                fragments.push((header.fragment_id, payload.to_vec()));
                if fragments.len() == usize::from(header.fragment_total) {
                    break;
                }
            }
            assert!(fragments.len() > 1, "a 3000-byte payload must be fragmented");
            fragments.sort_by_key(|(fragment_id, _)| *fragment_id);
            let reassembled: Vec<u8> = fragments.into_iter().flat_map(|(_, chunk)| chunk).collect();
            assert_eq!(reassembled, large_payload());
        });

        let _ = tokio::join!(tcp_task, udp_task);
    });

    let tls_config = build_tls_config(false, Some(vec![cert_der])).expect("tls config");
    let client = TuicClient::connect_with_tls(
        Config {
            server: server_addr.ip().to_string(),
            server_port: i32::from(server_addr.port()),
            server_name: "localhost".to_owned(),
            uuid: expected_uuid.to_string(),
            password: expected_token_seed,
            zero_rtt: false,
            congestion_control: "bbr".to_owned(),
            udp_enabled: true,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: true,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
            keepalive_interval_ms: 0,
            root_certificate_pem: None,
        },
        tls_config,
    )
    .await
    .expect("client");

    let mut tcp = client.tcp_connect("echo.example:443").await.expect("tcp connect");
    tcp.write_all(b"hello").await.expect("tcp write");
    let mut echoed = [0u8; 5];
    tcp.read_exact(&mut echoed).await.expect("tcp read");
    assert_eq!(&echoed, b"hello");

    assert_eq!(
        client.quic_migration_snapshot(),
        (Some("validated".to_string()), Some("path_validated_after_stream_open".to_string()),)
    );

    let mut udp = client.udp_session().await.expect("udp session");
    // The open session, not the client handle, keeps inbound datagrams flowing.
    drop(client);
    udp.send_to("dns.example:53", b"world").await.expect("udp send");
    let (address, payload) = tokio::time::timeout(Duration::from_secs(5), udp.recv_from())
        .await
        .expect("a UDP session must keep receiving after the client handle drops")
        .expect("udp recv");
    assert_eq!(address, "dns.example:53");
    assert_eq!(payload, b"world");
    udp.send_to("dns.example:53", &large_payload()).await.expect("fragmented udp send");

    server_task.await.expect("server task");
    // The server is gone, so the connection closes and `recv_from` must end.
    let closed = tokio::time::timeout(Duration::from_secs(5), udp.recv_from())
        .await
        .expect("recv_from must not hang once the connection is closed");
    assert!(closed.is_err(), "a closed connection must end the UDP session");
}

/// TUIC v5 SPEC "Heartbeat": the client sends `VER 0x05, TYPE 0x04`
/// datagrams periodically while relay tasks are active, so a server with a
/// short idle timeout (tuic-server default 10 s) keeps idle flows open.
#[tokio::test]
async fn tuic_client_sends_heartbeats_only_while_tasks_are_active() {
    ensure_crypto_provider();
    let certificate = generate_simple_self_signed(vec!["localhost".to_owned()]).expect("certificate");
    let cert_der = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
    let mut server_tls = RustlsServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der.clone()], key_der)
        .expect("server cert");
    server_tls.alpn_protocols = vec![b"h3".to_vec()];
    let server_config = quinn::ServerConfig::with_crypto(Arc::new(
        quinn::crypto::rustls::QuicServerConfig::try_from(server_tls).expect("quic server config"),
    ));
    let server = Endpoint::server(server_config, SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("endpoint");
    let server_addr = server.local_addr().expect("server addr");
    let (stream_opened_tx, stream_opened_rx) = tokio::sync::oneshot::channel();
    let server_task = tokio::spawn(async move {
        let connection = server.accept().await.expect("incoming").await.expect("connection");
        let quiet = tokio::time::timeout(Duration::from_millis(600), connection.read_datagram()).await;
        assert!(quiet.is_err(), "an idle client with no relay task must not send heartbeats");
        stream_opened_tx.send(()).expect("signal client");

        let (_send, mut recv) = connection.accept_bi().await.expect("tcp stream");
        let mut header = [0u8; 2];
        recv.read_exact(&mut header).await.expect("connect header");
        let heartbeat = tokio::time::timeout(Duration::from_secs(2), connection.read_datagram())
            .await
            .expect("an open TCP relay must trigger a heartbeat")
            .expect("heartbeat datagram");
        assert_eq!(heartbeat.as_ref(), [TUIC_VERSION, 0x04]);
    });

    let tls_config = build_tls_config(false, Some(vec![cert_der])).expect("tls config");
    let client = TuicClient::connect_with_tls(
        Config {
            server: server_addr.ip().to_string(),
            server_port: i32::from(server_addr.port()),
            server_name: "localhost".to_owned(),
            uuid: "00000000-0000-0000-0000-000000000000".to_owned(),
            password: "tuic-fixture".to_owned(),
            zero_rtt: false,
            congestion_control: "bbr".to_owned(),
            udp_enabled: true,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
            keepalive_interval_ms: 0,
            root_certificate_pem: None,
        },
        tls_config,
    )
    .await
    .expect("client");

    stream_opened_rx.await.expect("server saw an idle client");
    let _tcp = client.tcp_connect("echo.example:443").await.expect("tcp connect");
    // The open stream, not the client handle, keeps the heartbeat alive.
    drop(client);
    server_task.await.expect("server task");
}
