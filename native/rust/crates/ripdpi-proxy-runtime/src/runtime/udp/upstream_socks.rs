use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::platform::connect as connect_platform;

use crate::runtime::state::RuntimeState;

/// A live SOCKS5 UDP ASSOCIATE session for one UDP flow.
///
/// Per RFC 1928 the UDP relay binding stays valid only while the control TCP
/// connection that requested the ASSOCIATE is open; the server tears the relay
/// down when this stream is dropped. The flow therefore owns `_control` for its
/// entire lifetime and never reads/writes it again after the handshake.
pub(super) struct UpstreamUdpSocks {
    _control: TcpStream,
    pub(super) relay_endpoint: SocketAddr,
}

/// Open a protected control TCP connection to the upstream SOCKS5 server and run
/// a synchronous UDP ASSOCIATE handshake, mirroring the blocking CONNECT path in
/// `runtime::routing::connect::socks`.
///
/// The control socket is protected before `connect` via the shared
/// [`connect_platform::connect_tcp_stream`] connector (the same protected
/// connector the TCP CONNECT path uses), satisfying the
/// `vpnservice-protect-invariant` rule for the control channel. The returned
/// `relay_endpoint` is the `BND.ADDR:BND.PORT` the caller must connect the
/// (separately protected) relay UDP socket to.
pub(super) fn open_upstream_udp_associate(
    upstream: SocketAddr,
    protect_path: Option<&str>,
    connect_timeout: Option<Duration>,
) -> io::Result<UpstreamUdpSocks> {
    let bind_ip = match upstream {
        SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
    };
    let mut control =
        connect_platform::connect_tcp_stream(upstream, bind_ip, protect_path, false, connect_timeout, None)
            .map_err(|err| err.source)?;
    control.set_read_timeout(connect_timeout)?;
    control.set_write_timeout(connect_timeout)?;

    let relay_endpoint = run_udp_associate_handshake(&mut control)?;

    control.set_read_timeout(None)?;
    control.set_write_timeout(None)?;
    Ok(UpstreamUdpSocks { _control: control, relay_endpoint })
}

fn run_udp_associate_handshake(control: &mut TcpStream) -> io::Result<SocketAddr> {
    control.write_all(&RuntimeState::upstream_socks_auth_request())?;
    let mut auth = [0u8; 2];
    control.read_exact(&mut auth)?;
    if !RuntimeState::upstream_socks_auth_accepted(auth) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "upstream socks auth failed"));
    }

    control.write_all(&encode_udp_associate_request())?;
    let reply = RuntimeState::read_upstream_socks_reply(control)?;
    if !RuntimeState::upstream_socks_connect_succeeded(&reply) {
        return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream socks udp associate failed"));
    }
    parse_associate_relay_endpoint(&reply)
}

/// RFC 1928 UDP ASSOCIATE request: `VER=5, CMD=3, RSV=0, ATYP=IPv4, 0.0.0.0:0`.
fn encode_udp_associate_request() -> [u8; 10] {
    [0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]
}

/// Parse `BND.ADDR:BND.PORT` from a validated SOCKS5 reply (`VER REP RSV ATYP …`).
fn parse_associate_relay_endpoint(reply: &[u8]) -> io::Result<SocketAddr> {
    let invalid = || io::Error::new(io::ErrorKind::InvalidData, "malformed udp associate reply");
    match reply.get(3).copied().ok_or_else(invalid)? {
        0x01 => {
            let octets: [u8; 4] = reply.get(4..8).ok_or_else(invalid)?.try_into().map_err(|_| invalid())?;
            let port = read_port(reply, 8).ok_or_else(invalid)?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), port))
        }
        0x04 => {
            let octets: [u8; 16] = reply.get(4..20).ok_or_else(invalid)?.try_into().map_err(|_| invalid())?;
            let port = read_port(reply, 20).ok_or_else(invalid)?;
            Ok(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), port))
        }
        _ => Err(invalid()),
    }
}

fn read_port(reply: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*reply.get(offset)?, *reply.get(offset + 1)?]))
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;

    fn spawn_associate_server(relay: [u8; 4], port: u16) -> (SocketAddr, thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind associate server");
        let addr = listener.local_addr().expect("listener addr");
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept associate client");
            let mut greeting = [0u8; 3];
            stream.read_exact(&mut greeting).expect("read auth request");
            stream.write_all(&[0x05, 0x00]).expect("write auth response");
            let mut request = [0u8; 10];
            stream.read_exact(&mut request).expect("read associate request");
            assert_eq!(request[1], 0x03, "CMD must be UDP ASSOCIATE");
            let mut reply = vec![0x05, 0x00, 0x00, 0x01];
            reply.extend_from_slice(&relay);
            reply.extend_from_slice(&port.to_be_bytes());
            stream.write_all(&reply).expect("write associate reply");
            // Hold the control connection open until the client drops it.
            let mut sink = [0u8; 1];
            let _ = stream.read(&mut sink);
        });
        (addr, handle)
    }

    #[test]
    fn udp_associate_returns_ipv4_relay_endpoint() {
        let (upstream, server) = spawn_associate_server([127, 0, 0, 1], 5300);
        let session = open_upstream_udp_associate(upstream, None, Some(Duration::from_secs(2))).expect("associate ok");
        assert_eq!(session.relay_endpoint, SocketAddr::from(([127, 0, 0, 1], 5300)));
        drop(session);
        server.join().expect("join associate server");
    }

    #[test]
    fn parse_relay_endpoint_rejects_truncated_reply() {
        assert!(parse_associate_relay_endpoint(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0]).is_err());
    }
}
