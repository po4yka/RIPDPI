use super::*;
use crate::session::socks5::associate_feedback;
use crate::session::socks5::{associate, handshake};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::net::TcpSocket;

/// # Cancel safety:
/// cancel-safe: cancellation drops both pending sockets, including a rejected private control.
pub(super) async fn connect(
    proxy: SocketAddr,
    auth: Auth,
    protect_path: Option<&str>,
    memory_budget: UdpMemoryBudget,
) -> io::Result<UdpSession> {
    let mut ctrl = control(proxy, &auth, protect_path).await?;
    let mut early_udp = None;
    let mut feedback = None;
    let relay = if proxy.ip().is_loopback() && matches!(auth, Auth::UserPass { .. }) {
        let udp = bind_udp(ctrl.local_addr()?.ip(), proxy.ip(), protect_path)?;
        let result = associate_feedback(&mut ctrl, proxy.ip(), udp.local_addr()?).await?;
        if let Some((relay, nonce)) = result {
            early_udp = Some(udp);
            feedback = Some(nonce);
            relay
        } else {
            // A rejected private exchange cannot be reused for standard ASSOCIATE.
            drop(ctrl);
            ctrl = control(proxy, &auth, protect_path).await?;
            associate(&mut ctrl, proxy.ip()).await?
        }
    } else {
        associate(&mut ctrl, proxy.ip()).await?
    };
    let udp = match early_udp {
        Some(udp) => udp,
        None => bind_udp(
            if relay.is_ipv4() {
                std::net::Ipv4Addr::UNSPECIFIED.into()
            } else {
                std::net::Ipv6Addr::UNSPECIFIED.into()
            },
            relay.ip(),
            protect_path,
        )?,
    };
    udp.connect(relay).await?;
    Ok(UdpSession {
        control: Arc::new(ctrl),
        invalidated: CancellationToken::new(),
        udp: Arc::new(udp),
        memory_budget,
        recv_timeout: DEFAULT_RECV_TIMEOUT,
        feedback,
        #[cfg(test)]
        next_datagram_id: Arc::new(std::sync::atomic::AtomicU64::new(1)),
    })
}

/// # Cancel safety:
/// cancel-safe: the pending control is dropped if connect or authentication is cancelled.
async fn control(proxy: SocketAddr, auth: &Auth, protect_path: Option<&str>) -> io::Result<TcpStream> {
    let socket = if proxy.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };
    protect_socket_if_available(&socket, proxy.ip(), protect_path)?;
    let mut stream = socket.connect(proxy).await?;
    handshake(&mut stream, auth).await?;
    Ok(stream)
}

fn bind_udp(
    bind_ip: std::net::IpAddr,
    relay_ip: std::net::IpAddr,
    protect_path: Option<&str>,
) -> io::Result<UdpSocket> {
    let domain = if bind_ip.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    protect_socket_if_available(&socket, relay_ip, protect_path)?;
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))?;
    socket.set_nonblocking(true)?;
    UdpSocket::from_std(socket.into())
}
