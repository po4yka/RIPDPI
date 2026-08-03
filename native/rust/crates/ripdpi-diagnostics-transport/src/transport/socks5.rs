use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};

use ripdpi_socks5_core::client::{Config as Socks5Config, Socks5Stream};
use ripdpi_socks5_core::util::target_addr::TargetAddr as Socks5TargetAddr;
use ripdpi_socks5_core::{Socks5Command, SocksError, validate_udp_rsv_frag};

use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

use super::tcp::connect_direct;
use super::types::{TargetAddress, TransportConnectResult};

pub(super) fn connect_via_socks5_observed(
    targets: &[TargetAddress],
    port: u16,
    proxy_host: &str,
    proxy_port: u16,
) -> Result<TransportConnectResult, String> {
    let mut last_error = None;
    for target in targets {
        let proxy = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
        match negotiate_socks5(proxy, target, port) {
            Ok(stream) => {
                let connected_addr = match target {
                    TargetAddress::Ip(ip) => Some(SocketAddr::new(*ip, port)),
                    TargetAddress::Host(_) => None,
                };
                let local_addr = stream.local_addr().ok();
                return Ok(TransportConnectResult { stream, connected_addr, local_addr, route_report: None });
            }
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_target_candidates".to_string()))
}

pub fn negotiate_socks5(proxy: TcpStream, target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    proxy.set_nonblocking(true).map_err(|err| err.to_string())?;
    let target = socks5_target(target, port);
    debug_assert!(
        tokio::runtime::Handle::try_current().is_err(),
        "negotiate_socks5 builds its own runtime and must not be called from within a tokio runtime context",
    );
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_io()
        .enable_time()
        .build()
        .map_err(|err| err.to_string())?;
    let proxy = runtime.block_on(async move {
        let proxy = tokio::net::TcpStream::from_std(proxy).map_err(|err| err.to_string())?;
        let operation = async move {
            let mut socks = Socks5Stream::use_stream(proxy, None, Socks5Config::default()).await?;
            socks.request(Socks5Command::TCPConnect, target).await?;
            Ok::<_, SocksError>(socks.get_socket())
        };
        match tokio::time::timeout(timeout, operation).await {
            Ok(Ok(proxy)) => Ok(proxy),
            Ok(Err(error)) => Err(error.to_string()),
            Err(_) => Err("SOCKS5 negotiation timed out".to_string()),
        }
    })?;
    let proxy = proxy.into_std().map_err(|err| err.to_string())?;
    proxy.set_nonblocking(false).map_err(|err| err.to_string())?;
    proxy.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    proxy.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    Ok(proxy)
}

fn socks5_target(target: &TargetAddress, port: u16) -> Socks5TargetAddr {
    match target {
        TargetAddress::Ip(ip) => Socks5TargetAddr::Ip(SocketAddr::new(*ip, port)),
        TargetAddress::Host(host) => Socks5TargetAddr::Domain(host.clone(), port),
    }
}

pub fn socks5_noauth_handshake(stream: &mut TcpStream) -> Result<(), String> {
    stream.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut reply = [0u8; 2];
    stream.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {reply:?}"));
    }
    Ok(())
}

pub fn socks5_udp_associate(stream: &mut TcpStream) -> Result<SocketAddr, String> {
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

pub fn normalize_udp_relay_addr(relay_addr: SocketAddr, control: &TcpStream) -> Result<SocketAddr, String> {
    if relay_addr.ip().is_unspecified() {
        let peer = control.peer_addr().map_err(|err| err.to_string())?;
        Ok(SocketAddr::new(peer.ip(), relay_addr.port()))
    } else {
        Ok(relay_addr)
    }
}

pub fn encode_socks5_udp_frame(destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
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

pub fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), String> {
    if frame.len() < 10 {
        return Err("SOCKS5 UDP frame too short".to_string());
    }
    validate_udp_rsv_frag(frame).map_err(|err| format!("SOCKS5 UDP: {err}"))?;
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

pub fn relay_udp_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
) -> Result<(Vec<u8>, SocketAddr), String> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    let mut control = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
    control.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    control.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    socks5_noauth_handshake(&mut control)?;
    let relay_addr = normalize_udp_relay_addr(socks5_udp_associate(&mut control)?, &control)?;

    let bind_addr: SocketAddr = if relay_addr.is_ipv4() {
        "0.0.0.0:0".parse().expect("valid IPv4 UDP bind")
    } else {
        "[::]:0".parse().expect("valid IPv6 UDP bind")
    };
    let udp = UdpSocket::bind(bind_addr).map_err(|err| err.to_string())?;
    udp.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    udp.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    udp.connect(relay_addr).map_err(|err| err.to_string())?;
    let frame = encode_socks5_udp_frame(destination, payload);
    udp.send(&frame).map_err(|err| err.to_string())?;

    let mut buf = [0u8; 65535];
    let size = udp.recv(&mut buf).map_err(|err| err.to_string())?;
    let (_, payload) = decode_socks5_udp_frame(&buf[..size])?;
    let local_addr = udp.local_addr().map_err(|err| err.to_string())?;
    Ok((payload, local_addr))
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv6Addr, SocketAddr};

    use super::*;

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
        let mut frame = vec![0x00, 0x00, 0x00, 0x02];
        frame.extend_from_slice(&[0; 10]);
        assert!(decode_socks5_udp_frame(&frame).is_err());
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_nonzero_reserved_bytes() {
        let addr: SocketAddr = "1.2.3.4:5678".parse().unwrap();
        let mut frame = encode_socks5_udp_frame(addr, b"payload");
        frame[0] = 0x01;

        let err = decode_socks5_udp_frame(&frame).unwrap_err();
        assert!(err.contains("reserved"));
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_nonzero_frag() {
        let addr: SocketAddr = "1.2.3.4:5678".parse().unwrap();
        let mut frame = encode_socks5_udp_frame(addr, b"payload");
        frame[2] = 0x01;

        let err = decode_socks5_udp_frame(&frame).unwrap_err();
        assert!(err.contains("fragmentation"));
    }
}
