use std::collections::HashSet;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;

use bytes::{Bytes, BytesMut};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket, lookup_host};

use crate::ports::{PortForwardConfig, PortProtocol, UdpAssociationPool, VirtualPortPool};
use crate::support::{MAX_PACKET, to_io_error};
use crate::virtual_iface::{Bus, Event};

/// # Cancel safety
/// Not cancel-safe for reuse: negotiation and forwarding consume stream bytes and pool entries.
/// Terminal runtime shutdown may abort this handler while dropping its sockets and pools.
// NOT cancel-safe: only the runtime's terminal shutdown aborts this owned session.
pub(crate) async fn handle_socks_client(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    udp_pool: Arc<UdpAssociationPool>,
) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }
    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    client.read_exact(&mut methods).await?;
    client.write_all(&[0x05, 0x00]).await?;

    let mut request_header = [0u8; 4];
    client.read_exact(&mut request_header).await?;
    if request_header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS request"));
    }
    let target = read_target(&mut client, request_header[3]).await?;

    match request_header[1] {
        0x01 => handle_tcp_connect(client, bus, tcp_pool, target).await,
        0x03 => handle_udp_associate(client, bus, udp_pool).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS command unsupported"))
        }
    }
}

/// # Cancel safety
/// Not cancel-safe for reuse: terminal runtime shutdown may abort this owned
/// session, but ordinary success and errors both release its virtual port.
// NOT cancel-safe: cancellation is permitted only while the whole runtime is discarded.
async fn handle_tcp_connect(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    target: SocketAddr,
) -> io::Result<()> {
    let virtual_port = tcp_pool.acquire().await?;
    let port_forward = PortForwardConfig { destination: target, protocol: PortProtocol::Tcp };
    let mut endpoint = bus.new_endpoint();
    endpoint.send(Event::ClientConnectionInitiated(port_forward, virtual_port));
    let result = async {
        write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;

        let mut buffer = BytesMut::with_capacity(MAX_PACKET);
        // Pending remote->client write, held outside the select! so the write is not
        // entangled with the readable arm and cancel reasoning stays simple.
        let mut pending_write: Option<Bytes> = None;
        loop {
            if let Some(data) = pending_write.take() {
                client.write_all(&data).await?;
            }
            tokio::select! {
                readable_result = client.readable() => {
                    match readable_result {
                        Ok(_) => match client.try_read_buf(&mut buffer) {
                            Ok(size) if size > 0 => {
                                endpoint.send(Event::LocalData(port_forward, virtual_port, Bytes::copy_from_slice(&buffer[..size])));
                                buffer.clear();
                            }
                            Ok(_) => break,
                            Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                            Err(error) => return Err(error),
                        },
                        Err(error) => return Err(error),
                    }
                }
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::ClientConnectionDropped(event_port) if event_port == virtual_port => break,
                    Event::RemoteData(event_port, data) if event_port == virtual_port => {
                        pending_write = Some(data);
                    }
                    _ => {}
                }
            }
        }

        Ok(())
    }.await;
    endpoint.send(Event::ClientConnectionDropped(virtual_port));
    tcp_pool.release(virtual_port).await;
    result
}

/// # Cancel safety
/// Not cancel-safe for reuse: cancellation can precede association release. The runtime
/// aborts this future only when all associated pools and interfaces are being discarded.
// NOT cancel-safe: association teardown is owned by terminal runtime shutdown.
async fn handle_udp_associate(mut control: TcpStream, bus: Bus, udp_pool: Arc<UdpAssociationPool>) -> io::Result<()> {
    let udp_socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await?;
    let bind_addr = udp_socket.local_addr()?;
    write_reply(&mut control, 0x00, bind_addr).await?;
    let association_bind_port = bind_addr.port();
    let mut endpoint = bus.new_endpoint();
    let mut buffer = [0u8; MAX_PACKET];
    let mut known_ports = HashSet::new();

    let result = async {
        loop {
            tokio::select! {
                readable_result = control.readable() => {
                    match readable_result {
                        Ok(_) => {
                            let mut one = [0u8; 1];
                            match control.try_read(&mut one) {
                                Ok(0) => break,
                                Ok(_) => continue,
                                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                                Err(error) => return Err(error),
                            }
                        }
                        Err(error) => return Err(error),
                    }
                }
                recv_result = udp_socket.recv_from(&mut buffer) => {
                    let (size, peer_addr) = recv_result?;
                    let (target, payload) = parse_socks_udp_request(&buffer[..size]).map_err(to_io_error)?;
                    let virtual_port = udp_pool.acquire(association_bind_port, peer_addr).await?;
                    known_ports.insert(virtual_port);
                    let port_forward = PortForwardConfig { destination: target, protocol: PortProtocol::Udp };
                    endpoint.send(Event::LocalData(port_forward, virtual_port, payload));
                }
                event = endpoint.recv() => match event {
                    Event::Shutdown => break,
                    Event::RemoteUdpDatagram(virtual_port, source, data) if known_ports.contains(&virtual_port) => {
                        if let Some(peer_addr) = udp_pool.peer_addr(virtual_port).await {
                            let packet = encode_socks_udp_response(source, &data);
                            udp_socket.send_to(&packet, peer_addr).await?;
                        }
                    }
                    _ => {}
                }
            }
        }

        Ok(())
    }
    .await;
    for port in known_ports {
        endpoint.send(Event::ClientConnectionDropped(port));
    }
    udp_pool.release_association(association_bind_port).await;
    result
}
/// # Cancel safety
/// Not cancel-safe: partial address bytes can be consumed. The owning session closes
/// its stream on cancellation and never resumes negotiation on that stream.
// NOT cancel-safe: callers must discard the partially consumed stream.
async fn read_target(client: &mut TcpStream, address_type: u8) -> io::Result<SocketAddr> {
    let host = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets).await?;
            IpAddr::V4(Ipv4Addr::from(octets)).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await?;
            let mut host = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut host).await?;
            String::from_utf8(host).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid socks host"))?
        }
        0x04 => {
            let mut octets = [0u8; 16];
            client.read_exact(&mut octets).await?;
            IpAddr::V6(Ipv6Addr::from(octets)).to_string()
        }
        _ => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socks address type"));
        }
    };
    let mut port = [0u8; 2];
    client.read_exact(&mut port).await?;
    let port = u16::from_be_bytes(port);
    if let Ok(ip) = host.parse::<IpAddr>() {
        Ok(SocketAddr::new(ip, port))
    } else {
        lookup_host((host.as_str(), port))
            .await?
            .find(SocketAddr::is_ipv4)
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "unable to resolve IPv4 target"))
    }
}

fn parse_socks_udp_request(buffer: &[u8]) -> anyhow::Result<(SocketAddr, Bytes)> {
    if buffer.len() < 10 {
        anyhow::bail!("socks udp datagram too short");
    }
    if buffer[2] != 0 {
        anyhow::bail!("fragmented socks udp datagrams are unsupported");
    }
    let address_type = buffer[3];
    let (target, payload_offset) = match address_type {
        0x01 => {
            let ip = Ipv4Addr::new(buffer[4], buffer[5], buffer[6], buffer[7]);
            let port = u16::from_be_bytes([buffer[8], buffer[9]]);
            (SocketAddr::V4(SocketAddrV4::new(ip, port)), 10)
        }
        0x04 => {
            anyhow::ensure!(buffer.len() >= 22, "socks IPv6 udp datagram too short");
            let octets: [u8; 16] = buffer[4..20].try_into()?;
            let port = u16::from_be_bytes([buffer[20], buffer[21]]);
            (SocketAddr::new(Ipv6Addr::from(octets).into(), port), 22)
        }
        _ => anyhow::bail!("SOCKS UDP targets must be IP literals"),
    };
    Ok((target, Bytes::copy_from_slice(&buffer[payload_offset..])))
}

fn encode_socks_udp_response(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    match target {
        SocketAddr::V4(addr) => {
            let mut packet = Vec::with_capacity(10 + payload.len());
            packet.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
            packet.extend_from_slice(payload);
            packet
        }
        SocketAddr::V6(addr) => {
            let mut packet = Vec::with_capacity(22 + payload.len());
            packet.extend_from_slice(&[0, 0, 0, 4]);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
            packet.extend_from_slice(payload);
            packet
        }
    }
}
async fn write_reply(client: &mut TcpStream, code: u8, bind_addr: SocketAddr) -> io::Result<()> {
    match bind_addr {
        SocketAddr::V4(addr) => {
            let mut reply = vec![0x05, code, 0x00, 0x01];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            client.write_all(&reply).await
        }
        SocketAddr::V6(_) => Err(io::Error::new(io::ErrorKind::Unsupported, "ipv6 bind replies are unsupported")),
    }
}

/// Validate that the SOCKS5 listener host is a loopback address before binding.
///
/// The config resolver already pins `127.0.0.1`, but the runtime is the last
/// line of defence: a non-loopback bind would expose the in-tunnel SOCKS proxy
/// as a routable inbound surface, contrary to RIPDPI's no-inbound posture.
/// Fails closed -- any non-loopback or non-IP-literal host returns `Err`, so the
/// tunnel refuses to start rather than open a routable listener.
pub(crate) fn ensure_loopback_socks_host(host: &str) -> io::Result<()> {
    let ip: IpAddr = host.parse().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("SOCKS bind host {host:?} is not an IP literal"))
    })?;
    if ip.is_loopback() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::InvalidInput, format!("refusing non-loopback SOCKS bind host {host}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;
    use tokio::time::{Duration, timeout};

    /// # Cancel safety
    /// Cancel-safe for this test: dropping the joined futures closes all sockets and discards the fresh pool.
    // cancel-safe: the test owns and discards the entire association on timeout.
    async fn verify_udp_cleanup(malformed: bool) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let (client, server) =
            tokio::join!(TcpStream::connect(listener.local_addr().expect("address")), listener.accept());
        let mut client = client.expect("client");
        let (server, _) = server.expect("server");
        let bus = Bus::new();
        let mut observer = bus.new_endpoint();
        let pool = Arc::new(UdpAssociationPool::new());
        let drive = async {
            let mut reply = [0; 10];
            client.read_exact(&mut reply).await.expect("association reply");
            let relay = SocketAddr::from((Ipv4Addr::LOCALHOST, u16::from_be_bytes([reply[8], reply[9]])));
            let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("UDP client");
            let request = encode_socks_udp_response("192.0.2.1:53".parse().expect("target"), b"query");
            udp.send_to(&request, relay).await.expect("valid datagram");
            let Event::LocalData(_, port, _) = observer.recv().await else { panic!("expected virtual UDP allocation") };
            assert!(pool.peer_addr(port).await.is_some(), "association allocated");
            if malformed {
                udp.send_to(&[0, 0, 0], relay).await.expect("malformed datagram");
            } else {
                client.shutdown().await.expect("control shutdown");
            }
            port
        };
        let (result, port) = timeout(Duration::from_secs(2), async {
            tokio::join!(handle_udp_associate(server, bus, Arc::clone(&pool)), drive)
        })
        .await
        .expect("association exits");
        assert_eq!(result.is_err(), malformed);
        assert_eq!(pool.peer_addr(port).await, None, "closed association must release its virtual port");
        let dropped = timeout(Duration::from_millis(100), observer.recv()).await.expect("virtual socket cleanup event");
        assert!(matches!(dropped, Event::ClientConnectionDropped(actual) if actual == port));
    }

    /// # Cancel safety
    /// Cancel-safe: helper owns all fresh resources.
    // cancel-safe: no shared state outside this test.
    #[tokio::test]
    async fn malformed_udp_datagram_releases_association_and_virtual_socket() {
        verify_udp_cleanup(true).await;
    }

    /// # Cancel safety
    /// Cancel-safe: helper owns all fresh resources.
    // cancel-safe: no shared state outside this test.
    #[tokio::test]
    async fn closing_udp_control_releases_association_and_virtual_socket() {
        verify_udp_cleanup(false).await;
    }

    /// # Cancel safety
    /// Cancel-safe for this test: all sockets and pools are fresh and dropped on timeout.
    // cancel-safe: cancelled test discards the entire pool.
    #[tokio::test]
    async fn failed_tcp_reply_releases_port_and_virtual_socket() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let (client, server) =
            tokio::join!(TcpStream::connect(listener.local_addr().expect("address")), listener.accept());
        let _client = client.expect("client");
        let (mut server, _) = server.expect("server");
        server.shutdown().await.expect("close write half to force reply failure");
        let bus = Bus::new();
        let mut observer = bus.new_endpoint();
        let pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp));
        let result = timeout(
            Duration::from_secs(2),
            handle_tcp_connect(server, bus, Arc::clone(&pool), "192.0.2.1:443".parse().expect("target")),
        )
        .await
        .expect("handler exits");
        assert!(result.is_err(), "closed write half must reject the SOCKS reply");
        let Event::ClientConnectionInitiated(_, port) = observer.recv().await else { panic!("expected allocation") };
        let dropped = timeout(Duration::from_millis(100), observer.recv()).await.expect("virtual socket cleanup event");
        assert!(matches!(dropped, Event::ClientConnectionDropped(actual) if actual == port));
        for _ in crate::ports::MIN_VIRTUAL_PORT..crate::ports::MAX_VIRTUAL_PORT {
            pool.acquire().await.expect("entire port pool must be available after failed reply");
        }
        assert!(pool.acquire().await.is_err(), "no duplicate release");
    }

    #[test]
    fn ensure_loopback_socks_host_accepts_loopback_and_rejects_routable() {
        assert!(ensure_loopback_socks_host("127.0.0.1").is_ok());
        assert!(ensure_loopback_socks_host("::1").is_ok());
        assert!(ensure_loopback_socks_host("0.0.0.0").is_err());
        assert!(ensure_loopback_socks_host("192.0.2.10").is_err());
        assert!(ensure_loopback_socks_host("not-an-ip").is_err());
    }

    #[test]
    fn parse_socks_udp_request_extracts_ipv4_target_and_payload() {
        let packet = [0x00, 0x00, 0x00, 0x01, 192, 0, 2, 10, 0x1F, 0x90, b'p', b'i', b'n', b'g'];

        let (target, payload) = parse_socks_udp_request(&packet).expect("socks udp request");

        assert_eq!(target, SocketAddr::from(([192, 0, 2, 10], 8080)));
        assert_eq!(&payload[..], b"ping");
    }

    #[test]
    fn parse_socks_udp_request_rejects_fragmented_datagrams() {
        let packet = [0x00, 0x00, 0x01, 0x01, 192, 0, 2, 10, 0x1F, 0x90];

        let error = parse_socks_udp_request(&packet).expect_err("fragmented datagram should fail");

        assert!(error.to_string().contains("fragmented"));
    }

    #[test]
    fn encode_socks_udp_response_wraps_ipv4_payload() {
        let target = SocketAddr::from(([203, 0, 113, 7], 5353));

        let packet = encode_socks_udp_response(target, b"dns");

        assert_eq!(&packet, &[0, 0, 0, 1, 203, 0, 113, 7, 0x14, 0xE9, b'd', b'n', b's']);
    }

    #[test]
    fn ipv6_udp_roundtrip_rejects_every_truncated_header() {
        let target: SocketAddr = "[fd77::1]:41005".parse().expect("IPv6 target");
        let frame = encode_socks_udp_response(target, b"dns-v6");
        let (actual, payload) = parse_socks_udp_request(&frame).expect("IPv6 UDP frame");
        assert_eq!(actual, target);
        assert_eq!(&payload[..], b"dns-v6");
        for len in 0..22 {
            assert!(parse_socks_udp_request(&frame[..len]).is_err(), "accepted truncated header of {len} bytes");
        }
    }
}
