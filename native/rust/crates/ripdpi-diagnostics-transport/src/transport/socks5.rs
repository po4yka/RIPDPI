use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket};

use ripdpi_socks5_core::client::{Config as Socks5Config, Socks5Stream};
use ripdpi_socks5_core::util::target_addr::TargetAddr as Socks5TargetAddr;
use ripdpi_socks5_core::{AuthenticationMethod, Socks5Command, SocksError, validate_udp_rsv_frag};

use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

use super::tcp::{connect_direct, connect_direct_observed};
use super::types::{
    Socks5Credentials, TargetAddress, TransportConnectError, TransportConnectResult, TransportError,
    TransportFailureStage,
};

pub(super) fn connect_via_socks5_observed(
    targets: &[TargetAddress],
    port: u16,
    proxy_host: &str,
    proxy_port: u16,
    credentials: Option<&Socks5Credentials>,
) -> Result<TransportConnectResult, TransportConnectError> {
    let mut last_error = None;
    for target in targets {
        let proxy = connect_direct_observed(&[TargetAddress::Host(proxy_host.to_string())], proxy_port, None)?.stream;
        match negotiate_socks5_with_credentials(proxy, target, port, credentials) {
            Ok(stream) => {
                let connected_addr = match target {
                    TargetAddress::Ip(ip) => Some(SocketAddr::new(*ip, port)),
                    TargetAddress::Host(_) => None,
                };
                let local_addr = stream.local_addr().ok();
                return Ok(TransportConnectResult { stream, connected_addr, local_addr, route_report: None });
            }
            Err(err) => {
                last_error =
                    Some(TransportConnectError::new(TransportFailureStage::Socks5Negotiation, err.to_string()));
            }
        }
    }
    Err(last_error.unwrap_or_else(|| {
        TransportConnectError::new(TransportFailureStage::Socks5Negotiation, "no_target_candidates")
    }))
}

pub fn negotiate_socks5(proxy: TcpStream, target: &TargetAddress, port: u16) -> Result<TcpStream, TransportError> {
    negotiate_socks5_with_credentials(proxy, target, port, None)
}

fn negotiate_socks5_with_credentials(
    proxy: TcpStream,
    target: &TargetAddress,
    port: u16,
    credentials: Option<&Socks5Credentials>,
) -> Result<TcpStream, TransportError> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
    proxy.set_nonblocking(true)?;
    let target = socks5_target(target, port);
    debug_assert!(
        tokio::runtime::Handle::try_current().is_err(),
        "negotiate_socks5 builds its own runtime and must not be called from within a tokio runtime context",
    );
    let runtime = tokio::runtime::Builder::new_current_thread().enable_io().enable_time().build()?;
    let auth = credentials.map(|credentials| AuthenticationMethod::Password {
        username: credentials.username().to_string(),
        password: credentials.password().to_string(),
    });
    let proxy = runtime.block_on(async move {
        let proxy = tokio::net::TcpStream::from_std(proxy)?;
        let operation = async move {
            let mut socks = Socks5Stream::use_stream(proxy, auth, Socks5Config::default()).await?;
            socks.request(Socks5Command::TCPConnect, target).await?;
            Ok::<_, SocksError>(socks.get_socket())
        };
        // Cancel safety: `operation` exclusively owns the just-created socket.
        // A timeout drops that socket after any partial RFC 1929/request bytes;
        // no caller can observe or reuse the half-negotiated connection.
        match tokio::time::timeout(timeout, operation).await {
            Ok(Ok(proxy)) => Ok(proxy),
            Ok(Err(error)) => Err(TransportError::Socks(error)),
            Err(_) => Err(TransportError::SocksNegotiationTimedOut),
        }
    })?;
    let proxy = proxy.into_std()?;
    proxy.set_nonblocking(false)?;
    proxy.set_read_timeout(Some(timeout))?;
    proxy.set_write_timeout(Some(timeout))?;
    Ok(proxy)
}

fn socks5_target(target: &TargetAddress, port: u16) -> Socks5TargetAddr {
    match target {
        TargetAddress::Ip(ip) => Socks5TargetAddr::Ip(SocketAddr::new(*ip, port)),
        TargetAddress::Host(host) => Socks5TargetAddr::Domain(host.clone(), port),
    }
}

pub fn socks5_noauth_handshake(stream: &mut TcpStream) -> Result<(), TransportError> {
    socks5_handshake(stream, None)
}

fn socks5_handshake(stream: &mut TcpStream, credentials: Option<&Socks5Credentials>) -> Result<(), TransportError> {
    let method = if credentials.is_some() { 0x02 } else { 0x00 };
    stream.write_all(&[0x05, 0x01, method])?;
    let mut reply = [0u8; 2];
    stream.read_exact(&mut reply)?;
    if reply != [0x05, method] {
        return Err(TransportError::SocksAuthFailed(reply));
    }
    if let Some(credentials) = credentials {
        let username = credentials.username().as_bytes();
        let password = credentials.password().as_bytes();
        let mut request = Vec::with_capacity(username.len() + password.len() + 3);
        request.extend_from_slice(&[0x01, username.len() as u8]);
        request.extend_from_slice(username);
        request.push(password.len() as u8);
        request.extend_from_slice(password);
        stream.write_all(&request)?;
        stream.read_exact(&mut reply)?;
        if reply != [0x01, 0x00] {
            return Err(TransportError::SocksUserpassFailed(reply[1]));
        }
    }
    Ok(())
}

pub fn socks5_udp_associate(stream: &mut TcpStream) -> Result<SocketAddr, TransportError> {
    let request = [0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    stream.write_all(&request)?;
    let mut header = [0u8; 4];
    stream.read_exact(&mut header)?;
    if header[1] != 0x00 {
        return Err(TransportError::SocksUdpAssociateFailed(header[1]));
    }
    match header[3] {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr)?;
            stream.read_exact(&mut port)?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr)?;
            stream.read_exact(&mut port)?;
            Ok(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(addr)), u16::from_be_bytes(port)))
        }
        atyp => Err(TransportError::SocksUdpAssociateAtypUnsupported(atyp)),
    }
}

pub fn normalize_udp_relay_addr(relay_addr: SocketAddr, control: &TcpStream) -> Result<SocketAddr, TransportError> {
    if relay_addr.ip().is_unspecified() {
        let peer = control.peer_addr()?;
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

pub fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), TransportError> {
    if frame.len() < 10 {
        return Err(TransportError::SocksUdpFrameTooShort);
    }
    validate_udp_rsv_frag(frame)?;
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
                return Err(TransportError::SocksUdpFrameTooShortIpv6);
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let address =
                SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(raw)), u16::from_be_bytes([frame[20], frame[21]]));
            Ok((address, frame[22..].to_vec()))
        }
        atyp => Err(TransportError::SocksUdpAtypUnsupported(atyp)),
    }
}

pub fn relay_udp_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
    credentials: Option<&Socks5Credentials>,
) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
    let mut control = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
    control.set_read_timeout(Some(timeout))?;
    control.set_write_timeout(Some(timeout))?;
    socks5_handshake(&mut control, credentials)?;
    let relay_addr = normalize_udp_relay_addr(socks5_udp_associate(&mut control)?, &control)?;

    let bind_addr: SocketAddr = if relay_addr.is_ipv4() {
        "0.0.0.0:0".parse().expect("valid IPv4 UDP bind")
    } else {
        "[::]:0".parse().expect("valid IPv6 UDP bind")
    };
    let udp = UdpSocket::bind(bind_addr)?;
    udp.set_read_timeout(Some(timeout))?;
    udp.set_write_timeout(Some(timeout))?;
    udp.connect(relay_addr)?;
    let frame = encode_socks5_udp_frame(destination, payload);
    udp.send(&frame)?;

    let mut buf = [0u8; 65535];
    let size = udp.recv(&mut buf)?;
    let (_, payload) = decode_socks5_udp_frame(&buf[..size])?;
    let local_addr = udp.local_addr()?;
    Ok((payload, local_addr))
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv6Addr, SocketAddr, TcpListener};
    use std::thread;

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
        assert!(err.to_string().contains("reserved"));
    }

    #[test]
    fn decode_socks5_udp_frame_rejects_nonzero_frag() {
        let addr: SocketAddr = "1.2.3.4:5678".parse().unwrap();
        let mut frame = encode_socks5_udp_frame(addr, b"payload");
        frame[2] = 0x01;

        let err = decode_socks5_udp_frame(&frame).unwrap_err();
        assert!(err.to_string().contains("fragmentation"));
    }

    #[test]
    fn authenticated_handshake_precedes_udp_associate() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind SOCKS fixture");
        let address = listener.local_addr().expect("SOCKS fixture address");
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept SOCKS control stream");
            let mut greeting = [0u8; 3];
            stream.read_exact(&mut greeting).expect("read USERPASS greeting");
            assert_eq!(greeting, [0x05, 0x01, 0x02]);
            stream.write_all(&[0x05, 0x02]).expect("select USERPASS");

            let mut auth = [0u8; 14];
            stream.read_exact(&mut auth).expect("read USERPASS credentials");
            assert_eq!(&auth, b"\x01\x07attempt\x04test");
            stream.write_all(&[0x01, 0x00]).expect("accept USERPASS credentials");

            let mut associate = [0u8; 10];
            stream.read_exact(&mut associate).expect("read UDP ASSOCIATE");
            assert_eq!(associate, [0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
            stream
                .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x12, 0x34])
                .expect("write UDP ASSOCIATE response");
        });

        let credentials = Socks5Credentials::new("attempt", "test").expect("valid credentials");
        let mut control = TcpStream::connect(address).expect("connect SOCKS fixture");
        socks5_handshake(&mut control, Some(&credentials)).expect("authenticate SOCKS control stream");
        assert_eq!(
            socks5_udp_associate(&mut control).expect("associate UDP relay"),
            "127.0.0.1:4660".parse().expect("relay address"),
        );
        server.join().expect("SOCKS fixture thread");
    }
}
