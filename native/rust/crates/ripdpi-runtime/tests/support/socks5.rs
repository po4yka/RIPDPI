use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::thread;
use std::time::Duration;

use super::SOCKET_TIMEOUT;

pub fn socks_auth(proxy_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set socks auth read timeout");
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).expect("set socks auth write timeout");
    stream.write_all(b"\x05\x01\x00").expect("write socks auth");
    assert_eq!(recv_exact(&mut stream, 2), b"\x05\x00");
    stream
}

pub fn socks_connect(proxy_port: u16, dst_port: u16) -> TcpStream {
    let (stream, reply) = socks_connect_ip_reply(proxy_port, dst_port);
    assert_eq!(reply[1], 0, "SOCKS5 connect failed: {reply:?}");
    stream
}

pub fn socks_connect_ip_reply(proxy_port: u16, dst_port: u16) -> (TcpStream, Vec<u8>) {
    let mut stream = socks_auth(proxy_port);
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks connect");
    let reply = recv_socks5_reply(&mut stream);
    (stream, reply)
}

pub fn socks_connect_domain(proxy_port: u16, host: &str, dst_port: u16) -> (TcpStream, Vec<u8>) {
    let mut stream = socks_auth(proxy_port);
    let host_bytes = host.as_bytes();
    let mut request = Vec::with_capacity(7 + host_bytes.len());
    request.extend([0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
    request.extend(host_bytes);
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks domain connect");
    let reply = recv_socks5_reply(&mut stream);
    (stream, reply)
}

pub fn socks_connect_domain_round_trip_with_retry(
    proxy_port: u16,
    host: &str,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_socks_connect_domain_round_trip(proxy_port, host, dst_port, payload) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "domain round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown domain round trip error".to_string())
    );
}

pub fn attempt_socks_connect_domain_round_trip(
    proxy_port: u16,
    host: &str,
    dst_port: u16,
    payload: &[u8],
) -> Result<Vec<u8>, String> {
    let (mut stream, reply) = socks_connect_domain(proxy_port, host, dst_port);
    if reply.get(1).copied() != Some(0x00) {
        return Err(format!("SOCKS5 domain connect failed: {reply:?}"));
    }
    stream.write_all(payload).map_err(|error| format!("write domain payload failed: {error}"))?;
    let mut body = vec![0u8; payload.len()];
    stream.read_exact(&mut body).map_err(|error| format!("read domain payload failed: {error}"))?;
    Ok(body)
}

pub fn socks_connect_ip_round_trip_with_retry(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        match attempt_socks_connect_ip_round_trip(proxy_port, dst_port, payload) {
            Ok(body) => return body,
            Err(error) => {
                last_error = Some(error);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    panic!(
        "ip round trip failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown ip round trip error".to_string())
    );
}

pub fn attempt_socks_connect_ip_round_trip(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut stream = socks_connect(proxy_port, dst_port);
    stream.write_all(payload).map_err(|error| format!("write ip payload failed: {error}"))?;
    let mut body = vec![0u8; payload.len()];
    stream.read_exact(&mut body).map_err(|error| format!("read ip payload failed: {error}"))?;
    Ok(body)
}

pub fn socks_udp_associate(proxy_port: u16) -> (TcpStream, SocketAddr) {
    let mut stream = socks_auth(proxy_port);
    let mut request = vec![0x05, 0x03, 0x00, 0x01];
    request.extend([0, 0, 0, 0]);
    request.extend([0, 0]);
    stream.write_all(&request).expect("write udp associate");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 UDP associate failed: {reply:?}");
    (stream, parse_socks5_reply_addr(&reply))
}

pub fn recv_socks5_reply(stream: &mut TcpStream) -> Vec<u8> {
    let mut reply = recv_exact(stream, 4);
    let tail = match reply[3] {
        0x01 => recv_exact(stream, 6),
        0x04 => recv_exact(stream, 18),
        0x03 => {
            let mut tail = recv_exact(stream, 1);
            let size = tail[0] as usize;
            tail.extend(recv_exact(stream, size + 2));
            tail
        }
        atyp => panic!("unsupported SOCKS5 reply ATYP: {atyp}"),
    };
    reply.extend(tail);
    reply
}

pub fn parse_socks5_reply_addr(reply: &[u8]) -> SocketAddr {
    match reply[3] {
        0x01 => SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7])),
            u16::from_be_bytes([reply[8], reply[9]]),
        ),
        0x04 => SocketAddr::new(
            IpAddr::from([
                reply[4], reply[5], reply[6], reply[7], reply[8], reply[9], reply[10], reply[11], reply[12], reply[13],
                reply[14], reply[15], reply[16], reply[17], reply[18], reply[19],
            ]),
            u16::from_be_bytes([reply[20], reply[21]]),
        ),
        atyp => panic!("unsupported SOCKS5 reply address type: {atyp}"),
    }
}

pub fn udp_proxy_roundtrip(relay: SocketAddr, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let socket = udp_proxy_client();
    udp_proxy_roundtrip_with_socket(&socket, relay, dst_port, payload)
}

pub fn udp_proxy_client() -> UdpSocket {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp client");
    socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set udp timeout");
    socket
}

pub fn udp_proxy_roundtrip_with_socket(
    socket: &UdpSocket,
    relay: SocketAddr,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut packet = vec![0x00, 0x00, 0x00, 0x01];
    packet.extend(Ipv4Addr::LOCALHOST.octets());
    packet.extend(dst_port.to_be_bytes());
    packet.extend(payload);
    socket.send_to(&packet, relay).expect("send udp packet");
    let mut buf = [0u8; 4096];
    loop {
        let (read, _) = socket.recv_from(&mut buf).expect("receive udp packet");
        assert!(read >= 10, "udp response too short");
        assert_eq!(&buf[..4], b"\x00\x00\x00\x01");
        let body = &buf[10..read];
        if body == payload {
            return body.to_vec();
        }
    }
}

pub fn recv_exact(stream: &mut TcpStream, size: usize) -> Vec<u8> {
    let mut buf = vec![0u8; size];
    stream.read_exact(&mut buf).expect("read exact");
    buf
}

pub fn wait_for_accepted_connections(telemetry: &dyn AcceptedCounter, minimum: usize, timeout: Duration) -> bool {
    let started = std::time::Instant::now();
    while started.elapsed() < timeout {
        if telemetry.accepted_count() >= minimum {
            return true;
        }
        thread::sleep(Duration::from_millis(20));
    }
    false
}

pub fn socks_connect_domain_round_trip_via_upstream_with_retry(
    proxy_port: u16,
    upstream_telemetry: &dyn AcceptedCounter,
    host: &str,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut last_error = None;
    for _ in 0..3 {
        let body = socks_connect_domain_round_trip_with_retry(proxy_port, host, dst_port, payload);
        if wait_for_accepted_connections(upstream_telemetry, 1, super::START_TIMEOUT) {
            return body;
        }
        last_error = Some("matching host did not traverse the upstream relay".to_string());
        thread::sleep(Duration::from_millis(50));
    }
    panic!(
        "domain round trip via upstream failed after retries: {}",
        last_error.unwrap_or_else(|| "unknown upstream routing error".to_string())
    );
}

/// Trait to abstract over different `RecordingTelemetry` types for `wait_for_accepted_connections`.
pub trait AcceptedCounter {
    fn accepted_count(&self) -> usize;
}
