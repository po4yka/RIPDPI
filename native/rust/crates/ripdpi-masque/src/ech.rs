use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Duration;

use ripdpi_ech_dns::EncryptedDnsConnectHooks;
use ripdpi_native_protect::SocketProtectionPolicy;

pub(crate) fn connect_hooks(bind_ip: Option<IpAddr>, policy: SocketProtectionPolicy) -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new()
        .require_direct_tcp_connector()
        .require_direct_udp_binder()
        .with_direct_tcp_connector(move |target, timeout| connect_tcp(target, timeout, bind_ip, policy))
        .with_direct_udp_binder(move |address| {
            let address = bind_ip.map_or(address, |ip| SocketAddr::new(ip, address.port()));
            let socket = UdpSocket::bind(address)?;
            // Binding sends no packets. Protect before returning the socket to Quinn.
            policy.protect(socket.as_raw_fd())?;
            socket.set_nonblocking(true)?;
            Ok(socket)
        })
}

/// # Cancel safety:
/// Cancellation drops the owned socket and its pending connection.
async fn connect_tcp(
    target: SocketAddr,
    timeout: Duration,
    bind_ip: Option<IpAddr>,
    policy: SocketProtectionPolicy,
) -> io::Result<TcpStream> {
    let socket = if target.is_ipv6() { tokio::net::TcpSocket::new_v6()? } else { tokio::net::TcpSocket::new_v4()? };
    policy.protect_non_loopback(socket.as_raw_fd(), target)?;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    let stream = tokio::time::timeout(timeout, socket.connect(target)).await??;
    stream.set_nodelay(true)?;
    stream.into_std()
}
