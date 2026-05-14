//! Shared QUIC client UDP-socket and `quinn::Endpoint` construction.
//!
//! `build_client_udp_socket` / `try_bind_low_port` were byte-for-byte
//! duplicated between `ripdpi-hysteria2::tls_quic` and
//! `ripdpi-masque::h3::socket`. This module is the single shared copy. It
//! also exposes [`build_quic_endpoint`], which stands up a `quinn::Endpoint`
//! with a client config from the [`QuicTransportConfig`] factory -- the step
//! Hysteria2 and MASQUE each open-coded.

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use super::config::QuicTransportConfig;
use crate::error::Result;

/// The low source ports a `bind_low_port` profile tries, in order. These look
/// like well-known service ports to a passive observer, unlike an ephemeral
/// high port. Mirrors the list Hysteria2 and MASQUE each carried privately.
const LOW_PORT_CANDIDATES: [u16; 7] = [2048, 2053, 2080, 2443, 3000, 3074, 4096];

/// Bind a UDP socket suitable for a QUIC client endpoint.
///
/// `ipv6` selects the address family; `bind_low_port` makes the socket try
/// the [`LOW_PORT_CANDIDATES`] before falling back to an ephemeral port. An
/// IPv6 socket is configured dual-stack (`set_only_v6(false)`) where the OS
/// allows it.
pub fn build_client_udp_socket(ipv6: bool, bind_low_port: bool) -> io::Result<std::net::UdpSocket> {
    let bind_addr =
        if ipv6 { SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)) } else { SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)) };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    if ipv6 {
        let _ = socket.set_only_v6(false);
    }
    if bind_low_port {
        try_bind_low_port(&socket, bind_addr.ip())?;
    } else {
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    Ok(socket.into())
}

/// Try each of [`LOW_PORT_CANDIDATES`] on `bind_ip`, falling back to an
/// ephemeral port if every candidate is taken.
fn try_bind_low_port(socket: &Socket, bind_ip: IpAddr) -> io::Result<()> {
    for port in LOW_PORT_CANDIDATES {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

/// Stand up a `quinn::Endpoint` for a QUIC client, using the shared
/// [`QuicTransportConfig`] factory for the client config.
///
/// `ipv6` selects the UDP socket's address family (callers pass
/// `target_addr.is_ipv6()`). The returned endpoint already has the profile's
/// client config installed as the default, so the caller only has to
/// `endpoint.connect(addr, server_name)`.
pub fn build_quic_endpoint(config: &QuicTransportConfig, ipv6: bool) -> Result<quinn::Endpoint> {
    let socket = build_client_udp_socket(ipv6, config.bind_low_port)?;
    let mut endpoint =
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?;
    endpoint.set_default_client_config(config.build_quinn_client_config()?);
    Ok(endpoint)
}

/// Rebind `endpoint` to a fresh UDP socket when the profile asked for
/// post-handshake migration, so `quinn` performs an RFC 9000 path validation.
/// A no-op when [`QuicTransportConfig::migrate_after_handshake`] is `false`.
pub fn maybe_rebind_endpoint(config: &QuicTransportConfig, endpoint: &quinn::Endpoint, ipv6: bool) -> io::Result<()> {
    if !config.migrate_after_handshake {
        return Ok(());
    }
    let replacement = build_client_udp_socket(ipv6, config.bind_low_port)?;
    endpoint.rebind(replacement)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_an_ipv4_client_socket_on_an_ephemeral_port() {
        let socket = build_client_udp_socket(false, false).expect("bind ipv4 socket");
        let local = socket.local_addr().expect("local addr");
        assert!(local.is_ipv4());
        assert_ne!(local.port(), 0, "an ephemeral port must have been assigned");
    }

    #[test]
    fn builds_an_ipv6_client_socket() {
        let socket = build_client_udp_socket(true, false).expect("bind ipv6 socket");
        assert!(socket.local_addr().expect("local addr").is_ipv6());
    }

    #[test]
    fn low_port_bind_lands_on_a_candidate_or_falls_back() {
        // Either a low candidate was free, or every candidate was taken and
        // the fallback ephemeral port was used. Both are valid outcomes; the
        // socket must simply be bound.
        let socket = build_client_udp_socket(false, true).expect("bind low-port socket");
        let port = socket.local_addr().expect("local addr").port();
        assert_ne!(port, 0, "socket must be bound to some port");
    }

    // The `build_quic_endpoint` tests use `#[tokio::test]`: `quinn::Endpoint`
    // construction requires a live Tokio runtime context.

    #[tokio::test]
    async fn builds_a_quic_endpoint_from_the_shared_config_factory() {
        // The endpoint builder is the step Hysteria2 and MASQUE each
        // open-coded; here it is exercised through the shared factory.
        let config = QuicTransportConfig::new("example.com");
        let endpoint = build_quic_endpoint(&config, false).expect("build endpoint");
        assert!(endpoint.local_addr().expect("endpoint addr").is_ipv4());
    }

    #[tokio::test]
    async fn builds_a_quic_endpoint_for_an_insecure_profile() {
        let config = QuicTransportConfig::new("cover.example").with_insecure(true);
        assert!(build_quic_endpoint(&config, false).is_ok());
    }

    #[tokio::test]
    async fn maybe_rebind_is_a_noop_when_migration_disabled() {
        let config = QuicTransportConfig::new("example.com");
        let endpoint = build_quic_endpoint(&config, false).expect("build endpoint");
        let before = endpoint.local_addr().expect("addr before");
        maybe_rebind_endpoint(&config, &endpoint, false).expect("rebind noop");
        assert_eq!(endpoint.local_addr().expect("addr after"), before, "no migration => same socket");
    }

    #[tokio::test]
    async fn maybe_rebind_swaps_the_socket_when_migration_enabled() {
        let config = QuicTransportConfig::new("example.com").with_migrate_after_handshake(true);
        let endpoint = build_quic_endpoint(&config, false).expect("build endpoint");
        let before = endpoint.local_addr().expect("addr before");
        maybe_rebind_endpoint(&config, &endpoint, false).expect("rebind");
        let after = endpoint.local_addr().expect("addr after");
        // A fresh socket is bound; the source port must have changed.
        assert_ne!(after.port(), before.port(), "migration must rebind to a new socket");
    }
}
