use std::io;
use std::net::SocketAddr;

use crate::config::MasqueConfig;

/// Bind a UDP socket for the MASQUE QUIC client.
///
/// MASQUE used to carry its own byte-for-byte copy of this logic; it now
/// delegates to the shared `ripdpi-hysteria2::quic_transport` helper so the
/// `quinn` client-socket setup lives in exactly one place. See the
/// `refactor-quic-and-h3-into-a-composable-transport-crate` task: the
/// composable QUIC transport is a module of `ripdpi-hysteria2`, and MASQUE
/// is one of its consumers.
pub(super) fn build_client_udp_socket(ipv6: bool, bind_low_port: bool) -> io::Result<std::net::UdpSocket> {
    // VpnService.protect() invariant: the returned QUIC client socket is
    // protected INSIDE `ripdpi_hysteria2::build_client_udp_socket` (the WARP
    // bind-then-protect-before-`.into()` pattern), so the H3 datapath inherits
    // protection here with no extra call. quinn keeps that exact fd across any
    // later rebind through the same builder, so the whole H3 endpoint is
    // covered. The H2 fallback (`crate::h2`) builds its own TCP carrier socket
    // and protects it there. See .claude/rules/vpnservice-protect-invariant.md.
    ripdpi_hysteria2::build_client_udp_socket(ipv6, bind_low_port)
}

pub(super) fn maybe_rebind_quic_endpoint(
    config: &MasqueConfig,
    endpoint: &quinn::Endpoint,
    proxy_addr: SocketAddr,
) -> io::Result<()> {
    if !config.quic_migrate_after_handshake {
        return Ok(());
    }
    let replacement = build_client_udp_socket(proxy_addr.is_ipv6(), config.quic_bind_low_port)?;
    endpoint.rebind(replacement)
}
