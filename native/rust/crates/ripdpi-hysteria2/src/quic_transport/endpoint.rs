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
use std::os::fd::AsRawFd;
use std::sync::Arc;

use ripdpi_native_protect::{has_protect_callback, protect_socket_via_callback};
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
    // VpnService.protect() invariant: this QUIC carrier socket binds to a
    // wildcard local address (0.0.0.0:0 / [::]:0) and is connected to the
    // non-loopback Hysteria2 server later by `quinn` — the remote is not known
    // here, so the WARP UDP pattern applies rather than the fail-closed TCP
    // helper. Protect the fd BEFORE handing the socket to `quinn`
    // (`Endpoint::new` / the Salamander `new_with_abstract_socket` path), so the
    // datagrams `quinn` later sends on it are kept out of the TUN route instead
    // of looping back into the tunnel the VPN owns. `quinn` keeps this exact fd
    // for the endpoint's lifetime, and every migration / port-hopping rebind
    // re-enters this same function, so protecting here covers them too. A no-op
    // when no callback is registered (host loopback e2e tests), which is why the
    // loopback-skip / fail-closed posture of the TCP helper does not apply.
    // See .claude/rules/vpnservice-protect-invariant.md and
    // ripdpi-warp-core/src/wireguard/socket.rs.
    if has_protect_callback() {
        protect_socket_via_callback(socket.as_raw_fd())?;
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

#[cfg(test)]
mod protect_tests {
    //! `build_client_udp_socket` must protect the QUIC carrier fd before the
    //! socket reaches `quinn` (vpnservice-protect-invariant.md). The protect
    //! callback registry is process-global, so these tests serialize on
    //! `PROTECT_TEST_LOCK` and clear the slot before releasing it.

    use super::*;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};

    use ripdpi_native_protect::{
        ProtectCallback, has_protect_callback, register_protect_callback, unregister_protect_callback,
    };

    // The protect registry is process-global. These tests register/unregister
    // a callback, so they must not run concurrently with each other (or with
    // any other test that observes the registry). The guard never spans an
    // `.await`, so a std `Mutex` is correct here.
    static PROTECT_TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
        calls: AtomicUsize,
    }

    impl RecordingCallback {
        fn new() -> Arc<Self> {
            Arc::new(Self { last_fd: AtomicI32::new(-1), calls: AtomicUsize::new(0) })
        }
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: std::os::fd::RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::SeqCst);
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
    }

    #[test]
    fn carrier_socket_is_protected_with_its_own_fd_before_quinn() {
        let _guard = PROTECT_TEST_LOCK.lock().expect("protect test lock");
        let cb = RecordingCallback::new();
        register_protect_callback(cb.clone());

        let socket = build_client_udp_socket(false, false).expect("bind ipv4 carrier socket");

        // The protect callback fired exactly once, and the fd it recorded is
        // the carrier socket's own fd — proving protect runs on the right fd
        // before the socket is handed to `quinn`.
        assert_eq!(cb.calls.load(Ordering::SeqCst), 1, "protect callback must be invoked exactly once");
        assert_eq!(
            cb.last_fd.load(Ordering::SeqCst),
            socket.as_raw_fd(),
            "protect must be called with the carrier socket's own fd",
        );

        unregister_protect_callback();
        drop(socket);
    }

    #[test]
    fn no_callback_is_a_noop_so_host_loopback_tests_still_bind() {
        let _guard = PROTECT_TEST_LOCK.lock().expect("protect test lock");
        unregister_protect_callback();
        assert!(!has_protect_callback(), "test precondition: no protect callback registered");

        // With no callback registered (the host loopback / e2e posture), the
        // protect step is a no-op and binding must still succeed — this is the
        // WARP UDP semantics, not the fail-closed TCP posture.
        let socket = build_client_udp_socket(false, false).expect("bind must succeed without a callback");
        assert_ne!(socket.local_addr().expect("local addr").port(), 0, "socket must be bound");
        drop(socket);
    }
}
