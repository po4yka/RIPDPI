fn udp_bind_random_port(addr: Option<IpAddr>) -> io::Result<Socket> {
    if let Some(addr) = addr {
        let sock_addr = SocketAddr::new(addr, 0);
        let socket = Socket::new(Domain::for_address(sock_addr), Type::DGRAM, None)?;
        socket.bind(&sock_addr.into())?;
        Ok(socket)
    } else {
        const V4_UNSPEC: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
        const V6_UNSPEC: SocketAddr = SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0);
        Socket::new(Domain::IPV6, Type::DGRAM, None)
            .and_then(|socket| socket.set_only_v6(false).map(|_| socket))
            .and_then(|socket| socket.bind(&V6_UNSPEC.into()).map(|_| socket))
            .or_else(|_| {
                Socket::new(Domain::IPV4, Type::DGRAM, None)
                    .and_then(|socket| socket.bind(&V4_UNSPEC.into()).map(|_| socket))
            })
    }
    .and_then(|socket| socket.set_nonblocking(true).map(|_| socket))
}

/// Handle the associate command by running a UDP proxy until the connection is done.
/// `control_peer_ip` must come from the authenticated TCP control connection.
/// The request's DST.ADDR and DST.PORT constrain the UDP sender when concrete.
pub async fn run_udp_proxy<T: AsyncRead + AsyncWrite + Unpin>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    addr: &TargetAddr,
    control_peer_ip: IpAddr,
    peer_bind_ip: Option<IpAddr>,
    reply_ip: IpAddr,
    outbound_bind_ip: Option<IpAddr>,
) -> Result<T, SocksServerError> {
    let source = UdpSourceConstraint::new(control_peer_ip, addr);
    run_udp_proxy_custom(proto, peer_bind_ip, reply_ip, move |inbound| async move {
        let outbound = udp_bind_random_port(outbound_bind_ip).err_when("binding outbound udp socket")?;

        transfer_udp_with_source(inbound, outbound, source).await
    })
    .await
}

/// Handle the associate command by running a UDP proxy until the connection is done.
///
/// This version allows passing in a custom transfer function while reusing the initialization code.
pub(crate) async fn run_udp_proxy_custom<T, F, R>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    peer_bind_ip: Option<IpAddr>,
    reply_ip: IpAddr,
    transfer: F,
) -> Result<T, SocksServerError>
where
    T: AsyncRead + AsyncWrite + Unpin,
    F: FnOnce(Socket) -> R,
    R: Future<Output = Result<(), SocksServerError>>,
{
    // By default, listen on a UDP6 socket, so that the client can connect
    // to it with either IPv4 or IPv6.
    let peer_sock = try_notify!(proto, udp_bind_random_port(peer_bind_ip).err_when("binding client udp socket"));

    let peer_addr = try_notify!(proto, peer_sock.local_addr().err_when("getting peer's local addr"));

    let reply_port = peer_addr.as_socket().ok_or(SocksServerError::Bug("addr not IP"))?.port();

    // Respect the pre-populated reply IP address.
    let mut inner = proto.reply_success(SocketAddr::new(reply_ip, reply_port)).await?;

    let udp_fut = transfer(peer_sock);
    let tcp_fut = wait_on_tcp(&mut inner);
    match try_join!(udp_fut, tcp_fut) {
        Ok(_) => warn!("unreachable"),
        Err(SocksServerError::EOF) => debug!("EOF on controlling TCP stream, closed UDP proxy"),
        Err(_) => warn!("SOCKS UDP proxy ended with an error"),
    }
    Ok(inner)
}

#[derive(Clone, Copy)]
struct UdpSourceConstraint {
    control_peer_ip: IpAddr,
    declared_ip: Option<IpAddr>,
    declared_port: Option<u16>,
}

impl UdpSourceConstraint {
    fn new(control_peer_ip: IpAddr, declared: &TargetAddr) -> Self {
        let (declared_ip, port) = match declared {
            TargetAddr::Ip(addr) => (Some(addr.ip()).filter(|ip| !ip.is_unspecified()), addr.port()),
            // A domain is not a verifiable source IP without a DNS lookup.
            TargetAddr::Domain(_, port) => (None, *port),
        };
        Self { control_peer_ip, declared_ip, declared_port: (port != 0).then_some(port) }
    }

    fn allows(self, source: SocketAddr) -> bool {
        same_peer_ip(source.ip(), self.control_peer_ip)
            && self.declared_ip.is_none_or(|ip| same_peer_ip(source.ip(), ip))
            && self.declared_port.is_none_or(|port| source.port() == port)
    }
}

/// Wait until a TCP stream (that's not supposed to receive anything) closes.
///
/// This is intended for cancelling the `transfer_udp` task.
pub async fn wait_on_tcp<I>(stream: &mut I) -> Result<(), SocksServerError>
where
    I: AsyncRead + Unpin,
{
    let mut buf = [0; 1];
    match stream.read(&mut buf).await {
        Ok(0) => Err(SocksServerError::EOF),
        Ok(_) => Err(SocksServerError::UnexpectedUdpControlGarbage(buf[0])),
        Err(err) => Err(err).err_when("waiting on UDP control stream"),
    }
}

async fn handle_udp_request(
    inbound: &UdpSocket,
    outbound: &UdpSocket,
    outbound_v6: bool,
    source: UdpSourceConstraint,
    buf: &mut [u8],
) -> Result<(), SocksServerError> {
    let (size, client_addr) = inbound.recv_from(buf).await.err_when("udp receiving from")?;
    debug!("SOCKS UDP request received");
    if !source.allows(client_addr) {
        return Ok(());
    }
    // Check the already pinned association before DNS or outbound I/O.
    if let Ok(peer) = inbound.peer_addr()
        && peer != client_addr
    {
        return Ok(());
    }

    let (frag, target_addr, data) = parse_udp_request(&buf[..size]).await?;

    if frag != 0 {
        debug!("Discard UDP frag packets sliently.");
        return Ok(());
    }

    debug!("SOCKS UDP request target_kind={}", target_addr.logging_kind());
    let mut target_addr = target_addr
        .resolve_dns()
        .await?
        .socket_addr()
        .ok_or(SocksServerError::Bug("unresolved UDP target address"))?;

    if outbound_v6 {
        target_addr.set_ip(match target_addr.ip() {
            std::net::IpAddr::V4(v4) => std::net::IpAddr::V6(v4.to_ipv6_mapped()),
            v6 @ std::net::IpAddr::V6(_) => v6,
        });
    }
    // Lock the relay to the first valid UDP source only after parsing.
    match inbound.peer_addr() {
        Ok(peer) if peer != client_addr => return Ok(()),
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotConnected => {
            inbound.connect(client_addr).await.err_when("connecting udp inbound")?;
        }
        Err(error) => return Err(error).err_when("reading UDP association peer"),
    }
    outbound.send_to(data, target_addr).await.err_when("udp sending to")?;
    Ok(())
}

fn same_peer_ip(source: IpAddr, control: IpAddr) -> bool {
    match (source, control) {
        (IpAddr::V4(v4), IpAddr::V6(v6)) | (IpAddr::V6(v6), IpAddr::V4(v4)) => v6.to_ipv4_mapped() == Some(v4),
        _ => source == control,
    }
}

async fn handle_udp_requests(inbound: &UdpSocket, outbound: &UdpSocket, source: UdpSourceConstraint) -> Result<(), SocksServerError> {
    let mut buf = vec![0u8; 8192];
    let outbound_v6 = outbound.local_addr().err_when("udp outbound local addr")?.is_ipv6();
    loop {
        match handle_udp_request(inbound, outbound, outbound_v6, source, &mut buf).await {
            Ok(_) => trace!("handled udp response"),
            Err(_) => debug!("SOCKS UDP request handling failed"),
        }
    }
}

async fn handle_udp_response(
    inbound: &UdpSocket,
    outbound: &UdpSocket,
    buf: &mut [u8],
) -> Result<(), SocksServerError> {
    let (size, mut remote_addr) = outbound.recv_from(buf).await.err_when("udp receiving from")?;
    debug!("SOCKS UDP response received");

    // Clients don't tend to expect v6-mapped addresses when they connect to v4 ones
    if let std::net::IpAddr::V6(v6) = remote_addr.ip()
        && let Some(v4) = v6.to_ipv4_mapped() {
            remote_addr.set_ip(std::net::IpAddr::V4(v4));
        }

    let mut data = new_udp_header(remote_addr)?;
    data.extend_from_slice(&buf[..size]);
    inbound.send(&data).await.err_when("udp sending")?;

    Ok(())
}

async fn handle_udp_responses(inbound: &UdpSocket, outbound: &UdpSocket) -> Result<(), SocksServerError> {
    let mut buf = vec![0u8; 8192];
    loop {
        match handle_udp_response(inbound, outbound, &mut buf).await {
            Ok(_) => trace!("handled udp response"),
            Err(_) => debug!("SOCKS UDP response handling failed"),
        }
    }
}

/// Run a bidirectional UDP SOCKS proxy for a given pair of inbound (SOCKS client) and outbound sockets.
/// `control_peer_ip` must come from the authenticated TCP control connection.
/// A nonzero association DST.PORT restricts the UDP source port. A concrete
/// DST.ADDR restricts its source IP. With DST.PORT=0, clients sharing one IP
/// (for example behind one NAT) cannot be distinguished before the first packet.
// NOT cancel-safe: cancellation drops the relay sockets and ends the association.
pub async fn transfer_udp(inbound: Socket, outbound: Socket, control_peer_ip: IpAddr, declared: &TargetAddr) -> Result<(), SocksServerError> {
    transfer_udp_with_source(inbound, outbound, UdpSourceConstraint::new(control_peer_ip, declared)).await
}

// NOT cancel-safe: cancellation drops the relay sockets and ends the association.
async fn transfer_udp_with_source(inbound: Socket, outbound: Socket, source: UdpSourceConstraint) -> Result<(), SocksServerError> {
    let inbound = UdpSocket::from_std(inbound.into()).err_when("wrapping inbound socket")?;
    let outbound = UdpSocket::from_std(outbound.into()).err_when("wrapping outbound socket")?;
    let req_fut = handle_udp_requests(&inbound, &outbound, source);
    let res_fut = handle_udp_responses(&inbound, &outbound);
    try_join!(req_fut, res_fut).map(|_| ())
}

#[cfg(test)]
mod udp_peer_tests {
    use super::*;

    // cancel-safe: cancellation drops only local test sockets.
    #[tokio::test(flavor = "current_thread")]
    async fn udp_association_ignores_other_ip_and_locks_first_valid_source() {
        let inbound = UdpSocket::from_std(udp_bind_random_port(None).unwrap().into()).unwrap();
        let outbound = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let rogue = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let valid = UdpSocket::bind("[::1]:0").await.unwrap();
        let port = inbound.local_addr().unwrap().port();
        let mut request = vec![0, 0, 0, 1, 127, 0, 0, 1];
        request.extend_from_slice(&target.local_addr().unwrap().port().to_be_bytes());
        request.push(b'x');
        let mut buf = [0u8; 128];

        rogue.send_to(&request, (Ipv4Addr::LOCALHOST, port)).await.unwrap();
        let source = UdpSourceConstraint::new(
            IpAddr::V6(Ipv6Addr::LOCALHOST),
            &TargetAddr::Ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0)),
        );
        handle_udp_request(&inbound, &outbound, false, source, &mut buf)
            .await
            .unwrap();
        assert!(inbound.peer_addr().is_err(), "rogue source must not lock the relay");

        valid.send_to(&request, (Ipv6Addr::LOCALHOST, port)).await.unwrap();
        handle_udp_request(&inbound, &outbound, false, source, &mut buf)
            .await
            .unwrap();
        assert_eq!(inbound.peer_addr().unwrap(), valid.local_addr().unwrap());
        let mut echoed = [0u8; 1];
        let (size, _) = target.recv_from(&mut echoed).await.unwrap();
        assert_eq!(&echoed[..size], b"x");
    }

    #[test]
    fn ipv4_mapped_source_matches_ipv4_control_peer() {
        let v4 = Ipv4Addr::LOCALHOST;
        assert!(same_peer_ip(IpAddr::V6(v4.to_ipv6_mapped()), IpAddr::V4(v4)));
        assert!(!same_peer_ip(IpAddr::V6(Ipv6Addr::LOCALHOST), IpAddr::V4(v4)));
    }

    #[test]
    fn concrete_declared_ip_must_match_udp_source() {
        let control = IpAddr::V4(Ipv4Addr::LOCALHOST);
        let declared = TargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), 1234));
        let source = UdpSourceConstraint::new(control, &declared);
        assert!(!source.allows(SocketAddr::new(control, 1234)));
        assert!(!source.allows(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), 1234)));
    }

    // cancel-safe: cancellation drops only local test sockets.
    #[tokio::test(flavor = "current_thread")]
    async fn declared_source_port_rejects_same_ip_before_pin_and_forward() {
        let inbound = UdpSocket::from_std(udp_bind_random_port(Some(IpAddr::V4(Ipv4Addr::LOCALHOST))).unwrap().into()).unwrap();
        let outbound = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let rogue = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let valid = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let declared = TargetAddr::Ip(valid.local_addr().unwrap());
        let source = UdpSourceConstraint::new(IpAddr::V4(Ipv4Addr::LOCALHOST), &declared);
        let mut request = vec![0, 0, 0, 1, 127, 0, 0, 1];
        request.extend_from_slice(&target.local_addr().unwrap().port().to_be_bytes());
        request.push(b'x');
        let mut buf = [0u8; 128];

        rogue.send_to(&request, inbound.local_addr().unwrap()).await.unwrap();
        handle_udp_request(&inbound, &outbound, false, source, &mut buf).await.unwrap();
        assert!(inbound.peer_addr().is_err(), "same-IP wrong-port source must not pin the relay");
        assert!(tokio::time::timeout(Duration::from_millis(20), target.recv_from(&mut buf)).await.is_err());

        valid.send_to(&request, inbound.local_addr().unwrap()).await.unwrap();
        handle_udp_request(&inbound, &outbound, false, source, &mut buf).await.unwrap();
        assert_eq!(inbound.peer_addr().unwrap(), valid.local_addr().unwrap());
        let (size, _) = target.recv_from(&mut buf).await.unwrap();
        assert_eq!(&buf[..size], b"x");
    }
}
