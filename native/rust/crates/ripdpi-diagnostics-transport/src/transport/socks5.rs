use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};

use crate::util::IO_TIMEOUT;

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

pub fn negotiate_socks5(mut proxy: TcpStream, target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
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
}
