//! Self-consistency test: drive the real [`MieruClient`] against a spec-faithful
//! in-crate Mieru *server* (built from the same cipher/segment code). This
//! proves the client's open handshake, in-tunnel SOCKS5 connect, and data
//! round-trip are internally consistent end to end. It does **not** prove
//! interoperability with a real upstream mieru server (see `PROTOCOL.md`).

use std::sync::Arc;

use ripdpi_network_time::NetworkTimeProvider;
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};

use crate::cipher::{self, KEY_LEN};
use crate::client::MieruClient;
use crate::config::{MieruConfig, MieruMux, MieruProtocol};
use crate::error::Result;
use crate::metadata::{DataAckMeta, ProtocolType};
use crate::segment::{Decryptor, Encryptor, MAX_PDU};

const NOW: i64 = 1_700_000_000;
const USERNAME: &str = "alice";
const PASSWORD: &str = "correct-horse-battery";

/// Minimal spec-faithful mieru TCP server: skips the open request, answers the
/// in-tunnel SOCKS5 negotiation, then echoes all subsequent data.
async fn run_server(transport: DuplexStream, key: [u8; KEY_LEN], username: Vec<u8>) -> Result<()> {
    let (mut reader, mut writer) = tokio::io::split(transport);
    let mut dec = Decryptor::new(key);
    let mut enc = Encryptor::new(key, username);

    // Mieru authenticates the carrier and sends CONNECT without a greeting.
    let request = recv_data(&mut dec, &mut reader).await?.expect("connect request");
    assert_eq!(request[0], 0x05);
    assert_eq!(request[1], 0x01, "CONNECT command");
    // Success reply with a dummy bound v4 address.
    send_data(&mut enc, &mut writer, &[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;

    // Echo loop.
    while let Some(chunk) = recv_data(&mut dec, &mut reader).await? {
        send_data(&mut enc, &mut writer, &chunk).await?;
    }
    Ok(())
}

async fn send_data<W>(enc: &mut Encryptor, writer: &mut W, bytes: &[u8]) -> Result<()>
where
    W: tokio::io::AsyncWrite + Unpin,
{
    for chunk in bytes.chunks(MAX_PDU) {
        let (prefix, suffix) = enc.next_padding()?;
        let payload_len = u16::try_from(chunk.len()).expect("fragment fits u16");
        enc.write_segment(
            writer,
            prefix,
            suffix,
            |prefix_len, suffix_len| {
                DataAckMeta {
                    protocol: ProtocolType::DataServerToClient,
                    session_id: 1,
                    seq: 0,
                    unack_seq: 0,
                    window_size: 0,
                    fragment: 0,
                    prefix_len,
                    payload_len,
                    suffix_len,
                }
                .marshal(NOW)
            },
            Some(chunk),
        )
        .await?;
    }
    Ok(())
}

async fn recv_data<R>(dec: &mut Decryptor, reader: &mut R) -> Result<Option<Vec<u8>>>
where
    R: tokio::io::AsyncRead + Unpin,
{
    loop {
        let (meta, payload) = match dec.read_segment(reader).await {
            Ok(segment) => segment,
            Err(crate::error::MieruError::Io(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(e),
        };
        if ProtocolType::from_u8(meta[0])? == ProtocolType::DataClientToServer
            && let Some(blob) = payload
        {
            return Ok(Some(blob));
        }
        // open/close/ack control segments: skip.
    }
}

fn test_config() -> MieruConfig {
    MieruConfig {
        server: "loopback".to_owned(),
        port: 443,
        username: USERNAME.to_owned(),
        password: PASSWORD.to_owned(),
        protocol: MieruProtocol::Tcp,
        multiplexing: MieruMux::Off,
        mtu: 1400,
    }
}

#[tokio::test]
async fn client_round_trips_one_mib_through_spec_faithful_loopback() {
    let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
    let (client_side, server_side) = tokio::io::duplex(1 << 20);
    let server = tokio::spawn(run_server(server_side, key, USERNAME.as_bytes().to_vec()));

    // A device-clock-independent provider pinned to NOW for the handshake; the
    // reader calibrates this same instance from the server's segments.
    let time = Arc::new(NetworkTimeProvider::fixed(NOW));
    let probe = Arc::clone(&time);

    let client = MieruClient::connect_over(client_side, &test_config(), time).await.expect("session open");
    let stream = client.tcp_connect("example.com:443").await.expect("in-tunnel SOCKS5 connect");
    let (mut read_half, mut write_half) = tokio::io::split(stream);

    let payload = vec![0xABu8; 1 << 20];
    let expected = payload.clone();
    let write = async move {
        write_half.write_all(&payload).await.expect("write payload");
        write_half.shutdown().await.expect("shutdown");
    };
    let read = async move {
        let mut got = vec![0u8; expected.len()];
        read_half.read_exact(&mut got).await.expect("read echo");
        assert_eq!(got, expected, "1 MiB round-trip must match");
    };
    tokio::join!(write, read);

    // The session must have calibrated the shared replay clock from the server's
    // (authenticated) segment timestamps — within the ±60 s minute-granularity.
    assert!(probe.is_calibrated(), "reader must calibrate the network-time provider from server segments");
    assert!((probe.now_unix() - NOW).abs() <= 60, "calibrated network time must track the server's wire timestamp");

    drop(server); // server task ends on client EOF
}

#[tokio::test]
async fn udp_protocol_is_rejected() {
    let mut config = test_config();
    config.protocol = MieruProtocol::Udp;
    let (client_side, _server_side) = tokio::io::duplex(1024);
    let time = Arc::new(NetworkTimeProvider::fixed(NOW));
    match MieruClient::connect_over(client_side, &config, time).await {
        Err(crate::error::MieruError::UdpUnsupported) => {}
        Ok(_) => panic!("udp config must be rejected"),
        Err(other) => panic!("expected UdpUnsupported, got {other:?}"),
    }
}

/// # Cancel safety
/// Not cancel-safe: the test explicitly joins its owned peer on either outcome.
#[tokio::test]
async fn dropping_idle_stream_closes_both_carrier_halves() {
    let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
    let (client_side, server_side) = tokio::io::duplex(1 << 16);
    let mut server = tokio::spawn(run_server(server_side, key, USERNAME.as_bytes().to_vec()));
    let client = MieruClient::connect_over(client_side, &test_config(), Arc::new(NetworkTimeProvider::fixed(NOW)))
        .await
        .expect("session");
    let stream = client.tcp_connect("example.com:443").await.expect("connect");
    drop(stream);
    let stopped = tokio::time::timeout(std::time::Duration::from_millis(200), &mut server).await;
    if stopped.is_err() {
        server.abort();
        let _ = server.await;
    }
    stopped.expect("idle stream drop must close its carrier").expect("peer task").expect("peer EOF");
}
