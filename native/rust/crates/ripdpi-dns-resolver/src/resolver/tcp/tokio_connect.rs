use std::io;
use std::net::SocketAddr;

use tokio::net::TcpStream;

pub(super) async fn connect(address: SocketAddr) -> io::Result<TcpStream> {
    // allow: hook-gated fallback — reached only when no direct_tcp_connector hook
    // is supplied AND none is required (non-VPN / no active TUN context). On
    // Android the integrator installs a protect()-backed connector and sets
    // require_direct_tcp_connector(); see vpnservice-protect-invariant.md and the
    // resolver_socket_sites_are_hook_gated audit test.
    TcpStream::connect(address).await
}
