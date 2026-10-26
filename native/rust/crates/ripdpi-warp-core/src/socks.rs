use std::collections::HashSet;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;

use bytes::Bytes;
use tokio::io::{AsyncReadExt, AsyncWriteExt, Interest};
use tokio::net::{TcpStream, UdpSocket, lookup_host};
use tokio::time::Duration;

use crate::ports::{PortForwardConfig, PortProtocol, UdpAssociationPool, VirtualPortPool};
use crate::support::MAX_PACKET;
use crate::virtual_iface::{Bus, Event};

/// Concurrent SOCKS sessions one runtime serves. A client accepted beyond this is
/// closed at once, so local clients cannot exhaust the process's descriptors.
pub(crate) const MAX_SOCKS_SESSIONS: usize = 4096;

/// Wait before the next accept after `failures` consecutive accept errors. A
/// persistent error such as EMFILE returns at once, so retrying without a wait spins.
pub(crate) fn accept_backoff(failures: u32) -> Duration {
    Duration::from_millis(10u64 << failures.clamp(1, 7)).min(Duration::from_secs(1))
}

/// A client must finish the greeting, the request and the target lookup within this
/// time, so a silent or trickling local client cannot pin a task and a descriptor.
const SOCKS_NEGOTIATION_TIMEOUT: Duration =
    if cfg!(test) { Duration::from_millis(300) } else { Duration::from_secs(10) };

/// # Cancel safety
/// NOT cancel-safe: negotiation and forwarding consume stream bytes and pool entries.
/// Terminal runtime shutdown may abort this handler while dropping its sockets and pools.
pub(crate) async fn handle_socks_client(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    udp_pool: Arc<UdpAssociationPool>,
) -> io::Result<()> {
    let (command, target) = tokio::time::timeout(SOCKS_NEGOTIATION_TIMEOUT, negotiate(&mut client))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "SOCKS negotiation timed out"))??;
    match command {
        0x01 => handle_tcp_connect(client, bus, tcp_pool, target).await,
        0x03 => handle_udp_associate(client, bus, udp_pool).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS command unsupported"))
        }
    }
}

/// Read the greeting and the request, and resolve the request's target.
///
/// # Cancel safety
/// NOT cancel-safe: partial negotiation bytes can be consumed. The caller closes the
/// stream on cancellation and never resumes negotiation on it.
async fn negotiate(client: &mut TcpStream) -> io::Result<(u8, SocketAddr)> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }
    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    client.read_exact(&mut methods).await?;
    if !methods.contains(&0x00) {
        // RFC 1928 section 3: no acceptable method.
        client.write_all(&[0x05, 0xFF]).await?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS client offers no supported method"));
    }
    client.write_all(&[0x05, 0x00]).await?;

    let mut request_header = [0u8; 4];
    client.read_exact(&mut request_header).await?;
    if request_header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS request"));
    }
    let target = read_target(client, request_header[3]).await?;
    Ok((request_header[1], target))
}

/// # Cancel safety
/// NOT cancel-safe: terminal runtime shutdown may abort this owned
/// session, but ordinary success and errors both release its virtual port.
async fn handle_tcp_connect(
    mut client: TcpStream,
    bus: Bus,
    tcp_pool: Arc<VirtualPortPool>,
    target: SocketAddr,
) -> io::Result<()> {
    let lease = tcp_pool.acquire()?;
    let port_forward = PortForwardConfig { destination: target, protocol: PortProtocol::Tcp };
    let mut session = bus.open_tcp_session(port_forward, lease.clone());
    let result = async {
        // RFC 1928 section 6: the reply reports the outcome of the connection attempt.
        if !session.connected().await {
            write_reply(&mut client, 0x04, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(io::Error::new(io::ErrorKind::HostUnreachable, "virtual TCP connect failed"));
        }
        write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;

        // One read never exceeds the upload credit it is taken under.
        let mut buffer = vec![0u8; MAX_PACKET];
        // Pending remote->client write, held outside the select! so the write is not
        // entangled with the readable arm and cancel reasoning stays simple.
        let mut pending_write: Option<Bytes> = None;
        // The client is read only under upload credit, so a stalled peer stops
        // this reader instead of growing the interface queue.
        let mut credit = None;
        let mut client_closed = false;
        // Each direction closes on its own (half-close): the client's EOF becomes our FIN
        // and the peer's FIN becomes a shutdown of the client's write half.
        let mut client_finished = false;
        let mut remote_finished = false;
        loop {
            if let Some(data) = pending_write.take() {
                client.write_all(&data).await?;
            }
            tokio::select! {
                granted = session.upload_credit(), if credit.is_none() && !client_finished => match granted {
                    Some(granted) => credit = Some(granted),
                    None => break,
                },
                () = client_read_closed(&client), if credit.is_none() && !client_closed && !client_finished => {
                    // The client left while the peer is stalled: start the close
                    // deadline so the session cannot outlive it, but keep
                    // uploading what credit still allows.
                    client_closed = true;
                    lease.request_close();
                }
                readable_result = client.readable(), if credit.is_some() && !client_finished => {
                    readable_result?;
                    match client.try_read(&mut buffer) {
                        Ok(0) => {
                            client_finished = true;
                            session.finish_upload();
                            if remote_finished {
                                break;
                            }
                        }
                        Ok(size) => {
                            let data = Bytes::copy_from_slice(&buffer[..size]);
                            let Some(granted) = credit.take() else { break };
                            if !session.send_local(granted, data) {
                                break;
                            }
                        }
                        Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                        Err(error) => return Err(error),
                    }
                }
                remote = session.recv_remote(), if !remote_finished => match remote {
                    Some(data) if data.is_empty() => {
                        remote_finished = true;
                        client.shutdown().await?;
                        if client_finished {
                            break;
                        }
                    }
                    Some(data) => pending_write = Some(data),
                    // The virtual socket closed, or the interface stopped.
                    None => break,
                }
            }
        }

        Ok(())
    }
    .await;
    lease.request_close();
    drop(session);
    result
}

/// Resolve once the client's read side has closed or failed. Readiness stays
/// set while unread data waits, so recheck on a slow tick instead of spinning.
///
/// # Cancel safety
/// cancel-safe: it only observes readiness and consumes no bytes.
async fn client_read_closed(client: &TcpStream) {
    loop {
        match client.ready(Interest::READABLE).await {
            Ok(ready) if !ready.is_read_closed() => tokio::time::sleep(Duration::from_millis(250)).await,
            _ => return,
        }
    }
}

/// # Cancel safety
/// NOT cancel-safe: cancellation can precede association release. The runtime
/// aborts this future only when all associated pools and interfaces are being discarded.
async fn handle_udp_associate(mut control: TcpStream, bus: Bus, udp_pool: Arc<UdpAssociationPool>) -> io::Result<()> {
    let udp_socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await?;
    let bind_addr = udp_socket.local_addr()?;
    write_reply(&mut control, 0x00, bind_addr).await?;
    let association_bind_port = bind_addr.port();
    let control_peer_ip = control.peer_addr()?.ip();
    // The first datagram from the control peer's IP owns the association (RFC 1928
    // section 7). Datagrams from other sources are dropped, so another local app can
    // neither allocate virtual ports through this relay nor end it.
    let mut udp_peer: Option<SocketAddr> = None;
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
                    if udp_peer.is_some_and(|peer| peer != peer_addr) || peer_addr.ip() != control_peer_ip {
                        continue;
                    }
                    let Ok((target, payload)) = parse_socks_udp_request(&buffer[..size]) else {
                        tracing::debug!("dropping a malformed SOCKS UDP datagram");
                        continue;
                    };
                    udp_peer = Some(peer_addr);
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
/// NOT cancel-safe: partial address bytes can be consumed. The owning session closes
/// its stream on cancellation and never resumes negotiation on that stream.
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
        // An IPv4-mapped IPv6 target (::ffff:a.b.c.d) is an IPv4 destination.
        Ok(SocketAddr::new(ip.to_canonical(), port))
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
            (SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)).to_canonical(), port), 22)
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
    use crate::virtual_iface::TcpCommand;
    use tokio::net::TcpListener;
    use tokio::time::{Duration, timeout};

    /// # Cancel safety
    /// cancel-safe: dropping the joined futures closes all sockets and discards the fresh pool.
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
                let foreign = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("foreign UDP source");
                foreign.send_to(&request, relay).await.expect("foreign datagram");
                udp.send_to(&request, relay).await.expect("second valid datagram");
                let Event::LocalData(_, second, payload) = observer.recv().await else { panic!("expected forwarding") };
                assert_eq!((second, &payload[..]), (port, &b"query"[..]), "only the pinned source is forwarded");
            }
            client.shutdown().await.expect("control shutdown");
            port
        };
        let (result, port) = timeout(Duration::from_secs(2), async {
            tokio::join!(handle_udp_associate(server, bus, Arc::clone(&pool)), drive)
        })
        .await
        .expect("association exits");
        result.expect("closing the control connection ends the association cleanly");
        assert_eq!(pool.peer_addr(port).await, None, "closed association must release its virtual port");
        let dropped = timeout(Duration::from_millis(100), observer.recv()).await.expect("virtual socket cleanup event");
        assert!(matches!(dropped, Event::ClientConnectionDropped(actual) if actual == port));
    }

    /// # Cancel safety
    /// cancel-safe: helper owns all fresh resources.
    #[tokio::test]
    async fn malformed_and_foreign_udp_datagrams_are_dropped_without_ending_the_association() {
        verify_udp_cleanup(true).await;
    }

    /// # Cancel safety
    /// cancel-safe: helper owns all fresh resources.
    #[tokio::test]
    async fn closing_udp_control_releases_association_and_virtual_socket() {
        verify_udp_cleanup(false).await;
    }

    /// # Cancel safety
    /// cancel-safe: all sockets and pools are fresh and dropped on timeout.
    #[tokio::test]
    async fn failed_tcp_reply_releases_port_and_virtual_socket() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let (client, server) =
            tokio::join!(TcpStream::connect(listener.local_addr().expect("address")), listener.accept());
        let _client = client.expect("client");
        let (mut server, _) = server.expect("server");
        server.shutdown().await.expect("close write half to force reply failure");
        let bus = Bus::new();
        let (mut commands, _drained) = bus.take_tcp_commands();
        let pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp).expect("TCP port entropy"));
        let handler = handle_tcp_connect(server, bus, Arc::clone(&pool), "192.0.2.1:443".parse().expect("target"));
        let (result, lease) = timeout(Duration::from_secs(2), async {
            tokio::join!(handler, async {
                let Some(TcpCommand::Open(_, lease, _, _, connected)) = commands.recv().await else {
                    panic!("expected allocation")
                };
                connected.send(()).expect("handler waits for the connect");
                lease
            })
        })
        .await
        .expect("handler exits");
        assert!(result.is_err(), "closed write half must reject the SOCKS reply");
        let port = lease.port();
        drop(lease);
        let closed =
            timeout(Duration::from_millis(100), commands.recv()).await.expect("virtual socket cleanup command");
        assert!(matches!(closed, Some(TcpCommand::Close(actual)) if actual == port));
        let leases: Vec<_> = (crate::ports::MIN_VIRTUAL_PORT..crate::ports::MAX_VIRTUAL_PORT)
            .map(|_| pool.acquire().expect("entire port pool must be available after failed reply"))
            .collect();
        assert!(pool.acquire().is_err(), "no duplicate release");
        drop(leases);
    }

    /// # Cancel safety
    /// cancel-safe: all sockets and pools are fresh and dropped on timeout.
    #[tokio::test]
    async fn failed_virtual_connect_is_reported_instead_of_success() {
        let (mut client, server) = socks_pair().await;
        let bus = Bus::new();
        let (mut commands, _drained) = bus.take_tcp_commands();
        let pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp).expect("TCP port entropy"));
        let handler = handle_tcp_connect(server, bus, pool, "[2001:db8::1]:443".parse().expect("target"));
        let (result, ()) = timeout(Duration::from_secs(2), async {
            tokio::join!(handler, async {
                // The interface drops the session, as it does for a target with no source address.
                let Some(TcpCommand::Open(..)) = commands.recv().await else { panic!("expected allocation") };
            })
        })
        .await
        .expect("handler exits");
        assert_eq!(result.expect_err("failed connect").kind(), io::ErrorKind::HostUnreachable);
        let mut reply = [0u8; 10];
        client.read_exact(&mut reply).await.expect("SOCKS reply");
        assert_eq!(reply[..2], [0x05, 0x04], "the client must see the failure, not success");
    }

    /// A connected client and the server side of the same loopback connection.
    ///
    /// # Cancel safety
    /// cancel-safe: dropping it drops only fresh local sockets.
    async fn socks_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let (client, server) =
            tokio::join!(TcpStream::connect(listener.local_addr().expect("address")), listener.accept());
        (client.expect("client"), server.expect("server").0)
    }

    /// # Cancel safety
    /// cancel-safe: all sockets and pools are fresh and dropped on timeout.
    #[tokio::test]
    async fn greeting_without_no_auth_is_refused() {
        let (mut client, server) = socks_pair().await;
        let pools = (
            Arc::new(VirtualPortPool::new(PortProtocol::Tcp).expect("TCP port entropy")),
            Arc::new(UdpAssociationPool::new()),
        );
        client.write_all(&[0x05, 0x01, 0x02]).await.expect("greeting offering only username/password");
        let result = timeout(Duration::from_secs(2), handle_socks_client(server, Bus::new(), pools.0, pools.1))
            .await
            .expect("handler exits");
        assert_eq!(result.expect_err("no acceptable method").kind(), io::ErrorKind::PermissionDenied);
        let mut reply = [0u8; 2];
        client.read_exact(&mut reply).await.expect("method reply");
        assert_eq!(reply, [0x05, 0xFF]);
    }

    /// # Cancel safety
    /// cancel-safe: all sockets and pools are fresh and dropped on timeout.
    #[tokio::test]
    async fn silent_client_times_out_during_negotiation() {
        let (mut client, server) = socks_pair().await;
        let pools = (
            Arc::new(VirtualPortPool::new(PortProtocol::Tcp).expect("TCP port entropy")),
            Arc::new(UdpAssociationPool::new()),
        );
        client.write_all(&[0x05]).await.expect("half a greeting");
        let result = timeout(Duration::from_secs(2), handle_socks_client(server, Bus::new(), pools.0, pools.1))
            .await
            .expect("a stalled greeting must not pin the handler");
        assert_eq!(result.expect_err("stalled negotiation").kind(), io::ErrorKind::TimedOut);
    }

    #[test]
    fn accept_backoff_grows_to_a_one_second_cap() {
        let delays: Vec<_> = (1..=10).map(accept_backoff).collect();
        assert_eq!(delays[0], Duration::from_millis(20));
        assert!(delays.windows(2).all(|pair| pair[0] <= pair[1]), "backoff must not shrink: {delays:?}");
        assert_eq!(delays[9], Duration::from_secs(1));
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
    fn ipv4_mapped_udp_target_is_an_ipv4_destination() {
        let mapped = SocketAddr::new(IpAddr::V6(Ipv4Addr::new(192, 0, 2, 10).to_ipv6_mapped()), 53);
        let (target, _) = parse_socks_udp_request(&encode_socks_udp_response(mapped, b"q")).expect("mapped frame");
        assert_eq!(target, SocketAddr::from(([192, 0, 2, 10], 53)));
    }

    /// # Cancel safety
    /// cancel-safe: dropping it drops only fresh local sockets.
    #[tokio::test]
    async fn ipv4_mapped_connect_target_is_an_ipv4_destination() {
        let (mut client, mut server) = socks_pair().await;
        let mut request = vec![0x04];
        request.extend_from_slice(&Ipv4Addr::new(192, 0, 2, 10).to_ipv6_mapped().octets());
        request.extend_from_slice(&443u16.to_be_bytes());
        client.write_all(&request[1..]).await.expect("mapped target");
        let target = read_target(&mut server, request[0]).await.expect("target");
        assert_eq!(target, SocketAddr::from(([192, 0, 2, 10], 443)));
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
