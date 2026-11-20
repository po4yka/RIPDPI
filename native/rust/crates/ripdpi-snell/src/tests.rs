// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from sagernet/sing-snell, Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>.

use std::io;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::record::test_support::fill_padding_with_bit_count;
use crate::record::{Record, RecordReader, RecordWriter};
use crate::request::encode_connect;
use crate::{SnellClientConfig, connect};

// Synthetic credentials and content of the Go-recorded vectors (see testdata/README.md).
const VECTOR_PSK: &str = "ripdpi-snell-vector-psk";
const VECTOR_USER_KEY: &str = "ripdpi-snell-vector-user";
const VECTOR_TARGET: &str = "example.com:443";
const VECTOR_PAYLOAD: &[u8] = b"hello snell from the go v4 client";
const GO_CLIENT_STREAM: &[u8] = include_bytes!("../testdata/v4_client_to_v5_server.bin");
const GO_SERVER_STREAM: &[u8] = include_bytes!("../testdata/v5_server_to_v4_client.bin");

#[test]
fn go_client_stream_decodes_to_request_payload_and_end() {
    let mut reader = RecordReader::new(VECTOR_PSK.as_bytes());
    let mut input = GO_CLIENT_STREAM.to_vec();
    let Some(Record::Data(request)) = reader.decode(&mut input).unwrap() else { panic!("missing request record") };
    assert_eq!(
        parse_request(&request).unwrap(),
        ParsedRequest { command: 0x05, user_key: VECTOR_USER_KEY.into(), target: VECTOR_TARGET.into(), rest: vec![] }
    );
    assert_eq!(request, encode_connect(VECTOR_USER_KEY.as_bytes(), VECTOR_TARGET).unwrap());
    assert_eq!(reader.decode(&mut input).unwrap(), Some(Record::Data(VECTOR_PAYLOAD.to_vec())));
    assert_eq!(reader.decode(&mut input).unwrap(), Some(Record::End));
    assert!(input.is_empty());
}

#[tokio::test]
async fn go_server_stream_reads_as_reply_and_echo() {
    let config = SnellClientConfig::new(VECTOR_PSK, VECTOR_USER_KEY).unwrap();
    let transport = tokio::io::join(GO_SERVER_STREAM, tokio::io::sink());
    let mut stream = connect(transport, &config, VECTOR_TARGET).await.unwrap();
    let mut received = Vec::new();
    stream.read_to_end(&mut received).await.unwrap();
    assert_eq!(received, [b"echo:".as_slice(), VECTOR_PAYLOAD].concat());
}

#[tokio::test]
async fn go_server_stream_rejects_wrong_psk() {
    let config = SnellClientConfig::new("another-psk", VECTOR_USER_KEY).unwrap();
    let transport = tokio::io::join(GO_SERVER_STREAM, tokio::io::sink());
    let mut stream = connect(transport, &config, VECTOR_TARGET).await.unwrap();
    let error = stream.read_to_end(&mut Vec::new()).await.unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
}

#[tokio::test]
async fn bytes_after_the_end_record_fail_the_stream_for_good() {
    let config = SnellClientConfig::new(VECTOR_PSK, VECTOR_USER_KEY).unwrap();
    let with_trailer = [GO_SERVER_STREAM, b"x"].concat();
    let transport = tokio::io::join(with_trailer.as_slice(), tokio::io::sink());
    let mut stream = connect(transport, &config, VECTOR_TARGET).await.unwrap();
    let error = stream.read_to_end(&mut Vec::new()).await.unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    assert_eq!(stream.read(&mut [0u8; 8]).await.unwrap_err().kind(), io::ErrorKind::InvalidData);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn loopback_round_trip_carries_multi_record_payload() {
    let (address, server) = spawn_fixture(Behaviour::Echo).await;
    let config = SnellClientConfig::new(FIXTURE_PSK, FIXTURE_USER_KEY).unwrap();
    let mut stream = connect(TcpStream::connect(address).await.unwrap(), &config, "[2001:db8::1]:8443").await.unwrap();
    let payload: Vec<u8> = (0..40_000u32).map(|index| (index * 7 % 251) as u8).collect();
    stream.write_all(&payload[..1_000]).await.unwrap();
    stream.write_all(&payload[1_000..]).await.unwrap();
    stream.shutdown().await.unwrap();
    let mut received = Vec::new();
    stream.read_to_end(&mut received).await.unwrap();
    assert_eq!(received, payload);
    assert_eq!(server.await.unwrap().unwrap(), "2001:db8::1:8443");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn loopback_rejects_unknown_user_key() {
    let (address, server) = spawn_fixture(Behaviour::Echo).await;
    let config = SnellClientConfig::new(FIXTURE_PSK, "someone-else").unwrap();
    let mut stream = connect(TcpStream::connect(address).await.unwrap(), &config, "example.com:80").await.unwrap();
    let error = stream.read_to_end(&mut Vec::new()).await.unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::ConnectionAborted);
    assert_eq!(server.await.unwrap().unwrap_err().kind(), io::ErrorKind::PermissionDenied);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn loopback_reports_server_error_reply() {
    let (address, server) = spawn_fixture(Behaviour::ErrorReply).await;
    let config = SnellClientConfig::new(FIXTURE_PSK, FIXTURE_USER_KEY).unwrap();
    let mut stream = connect(TcpStream::connect(address).await.unwrap(), &config, "example.com:80").await.unwrap();
    let error = stream.read_to_end(&mut Vec::new()).await.unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    assert!(error.to_string().contains("Snell server error 1: target unreachable"), "{error}");
    server.await.unwrap().unwrap();
}

#[test]
fn shaped_padding_sets_exactly_the_requested_bits() {
    for (len, ones) in [(256, 0), (256, 2048), (311, 1_000), (511, 1)] {
        let mut padding = vec![0xa5u8; len];
        fill_padding_with_bit_count(&mut padding, ones);
        assert_eq!(padding.iter().map(|byte| byte.count_ones() as usize).sum::<usize>(), ones);
    }
}

#[test]
fn config_rejects_missing_psk_and_long_user_key() {
    assert_eq!(SnellClientConfig::new("", "user").unwrap_err().kind(), io::ErrorKind::InvalidInput);
    assert_eq!(SnellClientConfig::new("psk", vec![b'u'; 256]).unwrap_err().kind(), io::ErrorKind::InvalidInput);
}

const FIXTURE_PSK: &str = "fixture-psk";
const FIXTURE_USER_KEY: &str = "fixture-user";

#[derive(Clone, Copy)]
enum Behaviour {
    Echo,
    ErrorReply,
}

#[derive(Debug, PartialEq, Eq)]
struct ParsedRequest {
    command: u8,
    user_key: Vec<u8>,
    target: String,
    rest: Vec<u8>,
}

/// Port of sing-snell `snellv5.Service.readRequest` for CONNECT and CONNECT_V2.
fn parse_request(record: &[u8]) -> io::Result<ParsedRequest> {
    let invalid = |reason: &str| io::Error::new(io::ErrorKind::InvalidData, reason.to_owned());
    let [version, command, user_key_len, rest @ ..] = record else { return Err(invalid("short request")) };
    if *version != 0x01 || !matches!(command, 0x01 | 0x05) {
        return Err(invalid("bad request version or command"));
    }
    let (user_key, rest) =
        rest.split_at_checked(usize::from(*user_key_len)).ok_or_else(|| invalid("short user key"))?;
    let (host_len, rest) = rest.split_first().ok_or_else(|| invalid("missing host"))?;
    let (host, rest) = rest.split_at_checked(usize::from(*host_len)).ok_or_else(|| invalid("short host"))?;
    let (port, rest) = rest.split_at_checked(2).ok_or_else(|| invalid("missing port"))?;
    Ok(ParsedRequest {
        command: *command,
        user_key: user_key.to_vec(),
        target: format!("{}:{}", String::from_utf8_lossy(host), u16::from_be_bytes([port[0], port[1]])),
        rest: rest.to_vec(),
    })
}

async fn read_record(stream: &mut TcpStream, reader: &mut RecordReader, input: &mut Vec<u8>) -> io::Result<Record> {
    loop {
        if let Some(record) = reader.decode(input)? {
            return Ok(record);
        }
        let mut chunk = [0u8; 4096];
        let read = stream.read(&mut chunk).await?;
        if read == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        input.extend_from_slice(&chunk[..read]);
    }
}

/// Serves one connection like the v5 multi-user server: authenticate the user key, then either echo
/// the upload after the client's end record or answer with an error reply. Returns the target.
async fn spawn_fixture(behaviour: Behaviour) -> (std::net::SocketAddr, tokio::task::JoinHandle<io::Result<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await?;
        let mut reader = RecordReader::new(FIXTURE_PSK.as_bytes());
        let mut input = Vec::new();
        let Record::Data(first) = read_record(&mut socket, &mut reader, &mut input).await? else {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "missing request"));
        };
        let request = parse_request(&first)?;
        if request.user_key != FIXTURE_USER_KEY.as_bytes() {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "bad user key"));
        }
        let mut writer = RecordWriter::new(FIXTURE_PSK.as_bytes())?;
        let mut out = Vec::new();
        match behaviour {
            Behaviour::ErrorReply => {
                let message = b"target unreachable";
                writer.encode_write(&[&[0x02, 0x01, message.len() as u8][..], message].concat(), 1, &mut out)?;
            }
            Behaviour::Echo => {
                let mut upload = request.rest.clone();
                while let Record::Data(data) = read_record(&mut socket, &mut reader, &mut input).await? {
                    upload.extend_from_slice(&data);
                }
                writer.encode_write(&[&[0x00][..], &upload].concat(), 1, &mut out)?;
                writer.encode_end(&mut out)?;
            }
        }
        socket.write_all(&out).await?;
        Ok(request.target)
    });
    (address, server)
}
