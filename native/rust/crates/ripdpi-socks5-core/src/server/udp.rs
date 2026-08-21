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
pub async fn run_udp_proxy<T: AsyncRead + AsyncWrite + Unpin>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    addr: &TargetAddr,
    peer_bind_ip: Option<IpAddr>,
    reply_ip: IpAddr,
    outbound_bind_ip: Option<IpAddr>,
) -> Result<T, SocksServerError> {
    run_udp_proxy_custom(proto, addr, peer_bind_ip, reply_ip, move |inbound| async move {
        let outbound = udp_bind_random_port(outbound_bind_ip).err_when("binding outbound udp socket")?;

        transfer_udp(inbound, outbound).await
    })
    .await
}

/// Handle the associate command by running a UDP proxy until the connection is done.
///
/// This version allows passing in a custom transfer function while reusing the initialization code.
pub async fn run_udp_proxy_custom<T, F, R>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    _addr: &TargetAddr,
    peer_bind_ip: Option<IpAddr>,
    reply_ip: IpAddr,
    transfer: F,
) -> Result<T, SocksServerError>
where
    T: AsyncRead + AsyncWrite + Unpin,
    F: FnOnce(Socket) -> R,
    R: Future<Output = Result<(), SocksServerError>>,
{
    // The DST.ADDR and DST.PORT fields contain the address and port that
    // the client expects to use to send UDP datagrams on for the
    // association. The server MAY use this information to limit access
    // to the association.
    // @see Page 6, https://datatracker.ietf.org/doc/html/rfc1928.
    //
    // We do NOT limit the access from the client currently in this implementation.

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
    buf: &mut [u8],
) -> Result<(), SocksServerError> {
    let (size, client_addr) = inbound.recv_from(buf).await.err_when("udp receiving from")?;
    debug!("SOCKS UDP request received");
    inbound.connect(client_addr).await.err_when("connecting udp inbound")?;

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
    outbound.send_to(data, target_addr).await.err_when("udp sending to")?;
    Ok(())
}

async fn handle_udp_requests(inbound: &UdpSocket, outbound: &UdpSocket) -> Result<(), SocksServerError> {
    let mut buf = vec![0u8; 8192];
    let outbound_v6 = outbound.local_addr().err_when("udp outbound local addr")?.is_ipv6();
    loop {
        match handle_udp_request(inbound, outbound, outbound_v6, &mut buf).await {
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
pub async fn transfer_udp(inbound: Socket, outbound: Socket) -> Result<(), SocksServerError> {
    let inbound = UdpSocket::from_std(inbound.into()).err_when("wrapping inbound socket")?;
    let outbound = UdpSocket::from_std(outbound.into()).err_when("wrapping outbound socket")?;
    let req_fut = handle_udp_requests(&inbound, &outbound);
    let res_fut = handle_udp_responses(&inbound, &outbound);
    try_join!(req_fut, res_fut).map(|_| ())
}
