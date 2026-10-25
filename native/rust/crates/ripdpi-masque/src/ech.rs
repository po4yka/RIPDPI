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
/// Cancellation drops the owned socket and its pending connection; during the
/// protect step the blocking task owns the socket and drops it when done.
async fn connect_tcp(
    target: SocketAddr,
    timeout: Duration,
    bind_ip: Option<IpAddr>,
    policy: SocketProtectionPolicy,
) -> io::Result<TcpStream> {
    let socket = if target.is_ipv6() { tokio::net::TcpSocket::new_v6()? } else { tokio::net::TcpSocket::new_v4()? };
    // The protect callback is a binder call on Android: keep it off the runtime
    // thread. The task owns the socket, so its fd cannot be closed and reused
    // while protect runs.
    let socket =
        tokio::task::spawn_blocking(move || policy.protect_non_loopback(socket.as_raw_fd(), target).map(|()| socket))
            .await
            .map_err(|error| io::Error::other(format!("ECH bootstrap protect task failed: {error}")))??;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    let stream = tokio::time::timeout(timeout, socket.connect(target)).await??;
    stream.set_nodelay(true)?;
    stream.into_std()
}
