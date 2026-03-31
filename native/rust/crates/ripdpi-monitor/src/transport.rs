use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::thread;
use std::time::Duration;

use rustls::{ClientConnection, StreamOwned};

use crate::types::{DomainTarget, QuicTarget, ScanPathMode, ScanRequest};
use crate::util::{CONNECT_TIMEOUT, IO_TIMEOUT};

// --- Types ---

#[derive(Clone, Debug)]
pub(crate) enum TransportConfig {
    Direct,
    Socks5 { host: String, port: u16 },
}

#[derive(Clone, Debug)]
pub(crate) enum TargetAddress {
    Host(String),
    Ip(IpAddr),
}

#[derive(Debug)]
pub(crate) enum ConnectionStream {
    Plain(TcpStream),
    Tls(Box<StreamOwned<ClientConnection, TcpStream>>),
}

impl Read for ConnectionStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.read(buf),
            Self::Tls(stream) => stream.read(buf),
        }
    }
}

impl Write for ConnectionStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.write(buf),
            Self::Tls(stream) => stream.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::Plain(stream) => stream.flush(),
            Self::Tls(stream) => stream.flush(),
        }
    }
}

impl ConnectionStream {
    pub(crate) fn shutdown(&mut self) {
        match self {
            Self::Plain(stream) => {
                let _ = stream.shutdown(Shutdown::Both);
            }
            Self::Tls(stream) => {
                stream.conn.send_close_notify();
                let _ = stream.flush();
                let _ = stream.sock.shutdown(Shutdown::Both);
            }
        }
    }
}

// --- Transport selection ---

pub(crate) fn transport_for_request(request: &ScanRequest) -> TransportConfig {
    match (&request.path_mode, request.proxy_host.as_ref(), request.proxy_port) {
        (ScanPathMode::InPath, Some(host), Some(port)) => TransportConfig::Socks5 { host: host.clone(), port },
        _ => TransportConfig::Direct,
    }
}

pub(crate) fn describe_transport(transport: &TransportConfig) -> String {
    match transport {
        TransportConfig::Direct => "DIRECT".to_string(),
        TransportConfig::Socks5 { host, port } => format!("SOCKS5({host}:{port})"),
    }
}

// --- Address resolution ---

pub(crate) fn domain_connect_target(target: &DomainTarget) -> TargetAddress {
    target
        .connect_ip
        .as_ref()
        .and_then(|ip| ip.parse::<IpAddr>().ok())
        .map_or_else(|| TargetAddress::Host(target.host.clone()), TargetAddress::Ip)
}

pub(crate) fn quic_connect_target(target: &QuicTarget) -> TargetAddress {
    target
        .connect_ip
        .as_ref()
        .and_then(|ip| ip.parse::<IpAddr>().ok())
        .map_or_else(|| TargetAddress::Host(target.host.clone()), TargetAddress::Ip)
}

pub(crate) fn resolve_addresses(target: &TargetAddress, port: u16) -> Result<Vec<SocketAddr>, String> {
    match target {
        TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
        TargetAddress::Host(host) => {
            (host.as_str(), port).to_socket_addrs().map(std::iter::Iterator::collect).map_err(|err| err.to_string())
        }
    }
}

pub(crate) fn resolve_first_socket_addr(value: &str) -> Result<SocketAddr, String> {
    value.to_socket_addrs().map_err(|err| err.to_string())?.next().ok_or_else(|| "no_socket_addrs".to_string())
}

// --- TCP connection ---

pub(crate) fn connect_transport(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
) -> Result<TcpStream, String> {
    match transport {
        TransportConfig::Direct => connect_direct(target, port),
        TransportConfig::Socks5 { host, port: proxy_port } => {
            let proxy = connect_direct(&TargetAddress::Host(host.clone()), *proxy_port)?;
            negotiate_socks5(proxy, target, port)
        }
    }
}

pub(crate) fn connect_direct(target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    let addresses = resolve_addresses(target, port)?;
    let mut last_error = None;
    for address in addresses {
        match TcpStream::connect_timeout(&address, CONNECT_TIMEOUT) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_error = Some(err.to_string()),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_addresses".to_string()))
}

pub(crate) fn wait_for_listener(addr: SocketAddr) -> Result<(), String> {
    for _ in 0..40 {
        if TcpStream::connect_timeout(&addr, Duration::from_millis(50)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err(format!("probe runtime listener did not become ready on {addr}"))
}

// --- SOCKS5 TCP CONNECT ---

pub(crate) fn negotiate_socks5(mut proxy: TcpStream, target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    proxy.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    proxy.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    proxy.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut auth_reply = [0u8; 2];
    proxy.read_exact(&mut auth_reply).map_err(|err| err.to_string())?;
    if auth_reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {auth_reply:?}"));
    }

    let mut request = vec![0x05, 0x01, 0x00];
    match target {
        TargetAddress::Ip(IpAddr::V4(ip)) => {
            request.push(0x01);
            request.extend(ip.octets());
        }
        TargetAddress::Ip(IpAddr::V6(ip)) => {
            request.push(0x04);
            request.extend(ip.octets());
        }
        TargetAddress::Host(host) => {
            let host_bytes = host.as_bytes();
            if host_bytes.len() > u8::MAX as usize {
                return Err("SOCKS5 host too long".to_string());
            }
            request.push(0x03);
            request.push(host_bytes.len() as u8);
            request.extend(host_bytes);
        }
    }
    request.extend(port.to_be_bytes());
    proxy.write_all(&request).map_err(|err| err.to_string())?;

    let mut reply = [0u8; 4];
    proxy.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply[1] != 0x00 {
        return Err(format!("SOCKS5 connect failed: {:x}", reply[1]));
    }
    match reply[3] {
        0x01 => {
            let mut tail = [0u8; 6];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        0x04 => {
            let mut tail = [0u8; 18];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            proxy.read_exact(&mut len).map_err(|err| err.to_string())?;
            let mut tail = vec![0u8; len[0] as usize + 2];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        atyp => return Err(format!("SOCKS5 atyp unsupported: {atyp}")),
    }
    Ok(proxy)
}

// --- SOCKS5 UDP ---

pub(crate) fn socks5_noauth_handshake(stream: &mut TcpStream) -> Result<(), String> {
    stream.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut reply = [0u8; 2];
    stream.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {reply:?}"));
    }
    Ok(())
}

pub(crate) fn socks5_udp_associate(stream: &mut TcpStream) -> Result<SocketAddr, String> {
    let request = [0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    stream.write_all(&request).map_err(|err| err.to_string())?;
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).map_err(|err| err.to_string())?;
    if header[1] != 0x00 {
        return Err(format!("SOCKS5 UDP ASSOCIATE failed: {:x}", header[1]));
    }
    match header[3] {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(addr)), u16::from_be_bytes(port)))
        }
        atyp => Err(format!("SOCKS5 UDP ASSOCIATE atyp unsupported: {atyp}")),
    }
}

pub(crate) fn normalize_udp_relay_addr(relay_addr: SocketAddr, control: &TcpStream) -> Result<SocketAddr, String> {
    if relay_addr.ip().is_unspecified() {
        let peer = control.peer_addr().map_err(|err| err.to_string())?;
        Ok(SocketAddr::new(peer.ip(), relay_addr.port()))
    } else {
        Ok(relay_addr)
    }
}

pub(crate) fn encode_socks5_udp_frame(destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]);
    match destination {
        SocketAddr::V4(addr) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

pub(crate) fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), String> {
    if frame.len() < 10 {
        return Err("SOCKS5 UDP frame too short".to_string());
    }
    match frame[3] {
        0x01 => {
            let address = SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7])),
                u16::from_be_bytes([frame[8], frame[9]]),
            );
            Ok((address, frame[10..].to_vec()))
        }
        0x04 => {
            if frame.len() < 22 {
                return Err("SOCKS5 UDP IPv6 frame too short".to_string());
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let address =
                SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(raw)), u16::from_be_bytes([frame[20], frame[21]]));
            Ok((address, frame[22..].to_vec()))
        }
        atyp => Err(format!("SOCKS5 UDP atyp unsupported: {atyp}")),
    }
}

// --- UDP relay ---

pub(crate) fn relay_udp_payload(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<Vec<u8>, String> {
    let destination =
        resolve_addresses(target, port)?.into_iter().next().ok_or_else(|| "no_socket_addrs".to_string())?;
    match transport {
        TransportConfig::Direct => relay_udp_direct(destination, payload),
        TransportConfig::Socks5 { host, port } => relay_udp_via_socks5(host, *port, destination, payload),
    }
}

pub(crate) fn relay_udp_direct(server: SocketAddr, payload: &[u8]) -> Result<Vec<u8>, String> {
    let bind_addr: SocketAddr =
        if server.is_ipv4() { (Ipv4Addr::UNSPECIFIED, 0).into() } else { (std::net::Ipv6Addr::UNSPECIFIED, 0).into() };
    let socket = UdpSocket::bind(bind_addr).map_err(|err| err.to_string())?;
    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.send_to(payload, server).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 2048];
    let (size, _) = socket.recv_from(&mut buf).map_err(|err| err.to_string())?;
    Ok(buf[..size].to_vec())
}

pub(crate) fn relay_udp_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
) -> Result<Vec<u8>, String> {
    let mut control = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
    control.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    control.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socks5_noauth_handshake(&mut control)?;
    let relay_addr = normalize_udp_relay_addr(socks5_udp_associate(&mut control)?, &control)?;

    let bind_addr: SocketAddr = if relay_addr.is_ipv4() {
        "0.0.0.0:0".parse().expect("valid IPv4 UDP bind")
    } else {
        "[::]:0".parse().expect("valid IPv6 UDP bind")
    };
    let udp = UdpSocket::bind(bind_addr).map_err(|err| err.to_string())?;
    udp.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.connect(relay_addr).map_err(|err| err.to_string())?;
    let frame = encode_socks5_udp_frame(destination, payload);
    udp.send(&frame).map_err(|err| err.to_string())?;

    let mut buf = [0u8; 65535];
    let size = udp.recv(&mut buf).map_err(|err| err.to_string())?;
    let (_, payload) = decode_socks5_udp_frame(&buf[..size])?;
    Ok(payload)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv6Addr;

    #[test]
    fn encode_decode_socks5_udp_frame_ipv4_roundtrip() {
        let addr: SocketAddr = "1.2.3.4:5678".parse().unwrap();
        let payload = b"hello world";
        let frame = encode_socks5_udp_frame(addr, payload);
        let (decoded_addr, decoded_payload) = decode_socks5_udp_frame(&frame).unwrap();
        assert_eq!(decoded_addr, addr);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn encode_decode_socks5_udp_frame_ipv6_roundtrip() {
        let addr = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)), 443);
        let payload = b"ipv6 test";
        let frame = encode_socks5_udp_frame(addr, payload);
        let (decoded_addr, decoded_payload) = decode_socks5_udp_frame(&frame).unwrap();
        assert_eq!(decoded_addr, addr);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_short() {
        assert!(decode_socks5_udp_frame(&[0; 5]).is_err());
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_unknown_atyp() {
        let mut frame = vec![0x00, 0x00, 0x00, 0x02]; // atyp 0x02 is invalid
        frame.extend_from_slice(&[0; 10]);
        assert!(decode_socks5_udp_frame(&frame).is_err());
    }

    #[test]
    fn domain_connect_target_uses_ip_override() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: Some("1.2.3.4".to_string()),
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
        };
        match domain_connect_target(&target) {
            TargetAddress::Ip(ip) => assert_eq!(ip, "1.2.3.4".parse::<IpAddr>().unwrap()),
            TargetAddress::Host(_) => panic!("expected IP"),
        }
    }

    #[test]
    fn domain_connect_target_falls_back_to_host() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: None,
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
        };
        match domain_connect_target(&target) {
            TargetAddress::Host(host) => assert_eq!(host, "example.com"),
            TargetAddress::Ip(_) => panic!("expected Host"),
        }
    }

    #[test]
    fn resolve_addresses_with_ip_target() {
        let target = TargetAddress::Ip("127.0.0.1".parse().unwrap());
        let addrs = resolve_addresses(&target, 80).unwrap();
        assert_eq!(addrs, vec!["127.0.0.1:80".parse::<SocketAddr>().unwrap()]);
    }

    #[test]
    fn transport_for_request_direct_on_raw_path() {
        let request = ScanRequest {
            profile_id: "test".to_string(),
            display_name: "test".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: crate::types::ScanKind::Connectivity,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: vec![],
            proxy_host: Some("proxy".to_string()),
            proxy_port: Some(1080),
            probe_tasks: vec![],
            domain_targets: vec![],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            service_targets: vec![],
            circumvention_targets: vec![],
            throughput_targets: vec![],
            whitelist_sni: vec![],
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
        };
        match transport_for_request(&request) {
            TransportConfig::Direct => {}
            TransportConfig::Socks5 { .. } => panic!("expected Direct for RawPath"),
        }
    }

    #[test]
    fn transport_for_request_socks5_on_in_path() {
        let request = ScanRequest {
            profile_id: "test".to_string(),
            display_name: "test".to_string(),
            path_mode: ScanPathMode::InPath,
            kind: crate::types::ScanKind::Connectivity,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: vec![],
            proxy_host: Some("proxy".to_string()),
            proxy_port: Some(1080),
            probe_tasks: vec![],
            domain_targets: vec![],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            service_targets: vec![],
            circumvention_targets: vec![],
            throughput_targets: vec![],
            whitelist_sni: vec![],
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
        };
        match transport_for_request(&request) {
            TransportConfig::Socks5 { host, port } => {
                assert_eq!(host, "proxy");
                assert_eq!(port, 1080);
            }
            TransportConfig::Direct => panic!("expected Socks5 for InPath"),
        }
    }

    #[test]
    fn describe_transport_direct() {
        assert_eq!(describe_transport(&TransportConfig::Direct), "DIRECT");
    }

    #[test]
    fn describe_transport_socks5() {
        let t = TransportConfig::Socks5 { host: "1.2.3.4".to_string(), port: 1080 };
        assert_eq!(describe_transport(&t), "SOCKS5(1.2.3.4:1080)");
    }
}
