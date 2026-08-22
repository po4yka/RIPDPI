use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::sync::Once;

use quinn::congestion::{BbrConfig, CubicConfig, NewRenoConfig};
use quinn::{ClientConfig, Endpoint, TransportConfig, VarInt};
use ripdpi_native_protect::SocketProtectionPolicy;
use rustls::pki_types::CertificateDer;
use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::config::Config;

const MAX_CONCURRENT_STREAMS: u32 = 512;
const MAX_IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);
static RUSTLS_PROVIDER: Once = Once::new();

#[derive(Debug, Clone, Copy)]
pub(crate) struct ClientSocketSpec {
    pub(crate) ipv6: bool,
    pub(crate) bind_low_port: bool,
    pub(crate) socket_protection: SocketProtectionPolicy,
    /// Interface pinning: the carrier binds to this address instead of an
    /// unspecified one. The family is validated against the resolved server
    /// address at resolve time (`resolve_server_addr`).
    pub(crate) outbound_bind_ip: Option<std::net::IpAddr>,
}

pub(crate) fn build_endpoint(
    config: &Config,
    tls_config: RustlsClientConfig,
    socket_spec: ClientSocketSpec,
) -> io::Result<(Endpoint, std::net::UdpSocket)> {
    let transport = build_transport_config(config.keepalive_interval_ms, &config.congestion_control)?;

    let mut client_config = ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config).map_err(io::Error::other)?,
    ));
    client_config.transport_config(Arc::new(transport));

    let socket = build_client_udp_socket(socket_spec)?;
    let socket_clone = socket.try_clone()?;
    let mut endpoint = Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?;
    endpoint.set_default_client_config(client_config);
    Ok((endpoint, socket_clone))
}

fn build_transport_config(keepalive_interval_ms: u32, congestion_control: &str) -> io::Result<TransportConfig> {
    let mut transport = TransportConfig::default();
    transport.max_concurrent_bidi_streams(VarInt::from_u32(MAX_CONCURRENT_STREAMS));
    transport.max_concurrent_uni_streams(VarInt::from_u32(MAX_CONCURRENT_STREAMS));
    let idle_timeout = MAX_IDLE_TIMEOUT
        .try_into()
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC idle timeout: {error}")))?;
    transport.max_idle_timeout(Some(idle_timeout));
    if keepalive_interval_ms > 0 {
        transport.keep_alive_interval(Some(std::time::Duration::from_millis(u64::from(keepalive_interval_ms))));
    }
    match congestion_control.trim().to_ascii_lowercase().as_str() {
        "cubic" => transport.congestion_controller_factory(Arc::new(CubicConfig::default())),
        "new_reno" | "newreno" => transport.congestion_controller_factory(Arc::new(NewRenoConfig::default())),
        _ => transport.congestion_controller_factory(Arc::new(BbrConfig::default())),
    };
    Ok(transport)
}

pub(crate) fn build_client_udp_socket(socket_spec: ClientSocketSpec) -> io::Result<std::net::UdpSocket> {
    let mut ipv6 = socket_spec.ipv6;
    let mut bind_addr =
        if ipv6 { SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)) } else { SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)) };
    if let Some(ip) = socket_spec.outbound_bind_ip {
        ipv6 = ip.is_ipv6();
        bind_addr = SocketAddr::new(ip, 0);
    }
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    if ipv6 {
        let _ = socket.set_only_v6(false);
    }
    if socket_spec.bind_low_port {
        try_bind_low_port(&socket, bind_addr.ip())?;
    } else {
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    // VpnService.protect() invariant (vpnservice-protect-invariant.md): the TUIC
    // QUIC carrier is a UDP socket bound locally to 0.0.0.0:0 (or a low port);
    // its remote server address is NOT known here — quinn connects/sends on it
    // later. Protect the fd now, AFTER bind and BEFORE handing the socket to
    // quinn (`.into()`), so the socket is kept out of the TUN route before the
    // kernel ever uses it. quinn keeps this exact fd for the whole endpoint, and
    // `Endpoint::rebind` re-enters this same function for migration, so every
    // carrier socket is covered. The `try_clone()` at the call sites dups this
    // fd (same kernel socket), so protecting the original protects the clone.
    //
    // The explicit inactive policy is a no-op for proxy/host paths. The
    // VPN-required policy consults the callback and propagates missing/rejected
    // protection, dropping the socket here before Quinn can perform I/O.
    socket_spec.socket_protection.protect(socket.as_raw_fd())?;
    Ok(socket.into())
}

fn try_bind_low_port(socket: &Socket, bind_ip: std::net::IpAddr) -> io::Result<()> {
    for port in [2048u16, 2053, 2080, 2443, 3000, 3074, 4096] {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

pub(crate) fn build_tls_config(
    enable_early_data: bool,
    additional_roots: Option<Vec<CertificateDer<'static>>>,
) -> io::Result<RustlsClientConfig> {
    ensure_crypto_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    if let Some(certificates) = additional_roots {
        for certificate in certificates {
            roots.add(certificate).map_err(io::Error::other)?;
        }
    }

    let mut tls_config = RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h3".to_vec()];
    tls_config.enable_early_data = enable_early_data;
    Ok(tls_config)
}

pub(crate) fn ensure_crypto_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

pub(crate) async fn establish_connection(
    endpoint: &Endpoint,
    config: &Config,
    server_addr: SocketAddr,
) -> io::Result<quinn::Connection> {
    let connecting = endpoint.connect(server_addr, &config.server_name).map_err(io::Error::other)?;
    if config.zero_rtt {
        match connecting.into_0rtt() {
            Ok((connection, accepted)) => {
                let _ = accepted.await;
                Ok(connection)
            }
            Err(connecting) => Ok(connecting.await.map_err(io::Error::other)?),
        }
    } else {
        Ok(connecting.await.map_err(io::Error::other)?)
    }
}

pub(crate) fn validate_config(config: &Config) -> io::Result<()> {
    if config.server.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC server"));
    }
    if config.server_port <= 0 || config.server_port > i32::from(u16::MAX) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid TUIC server port"));
    }
    if config.server_name.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC server name"));
    }
    if config.uuid.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC UUID"));
    }
    if config.password.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC password"));
    }
    match config.congestion_control.trim().to_ascii_lowercase().as_str() {
        "bbr" | "cubic" | "new_reno" | "newreno" => {}
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported TUIC congestion control {other:?}"),
            ));
        }
    }
    Ok(())
}

pub(crate) async fn resolve_server_addr(
    server: &str,
    port: i32,
    outbound_bind_ip: Option<std::net::IpAddr>,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<SocketAddr> {
    let addrs = socket_protection.resolve_host(server, port as u16).await?;
    let resolved = match outbound_bind_ip {
        Some(bind_ip) => addrs.into_iter().find(|addr| addr.is_ipv4() == bind_ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no server address matches outbound bind IP family")
        })?,
        None => addrs
            .into_iter()
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "unable to resolve TUIC server"))?,
    };
    Ok(resolved)
}

#[cfg(test)]
mod transport_tests {
    use super::*;

    #[test]
    fn transport_retains_finite_idle_timeout_when_keepalive_is_disabled() {
        let transport = build_transport_config(0, "bbr").expect("build transport config");
        let rendered = format!("{transport:?}");

        assert!(
            rendered.contains("max_idle_timeout: Some(60000)"),
            "TUIC carrier must retain a finite blackhole watchdog: {rendered}"
        );
        assert!(
            rendered.contains("keep_alive_interval: None"),
            "test precondition requires disabled keepalive: {rendered}"
        );
    }
}

#[cfg(test)]
mod protect_tests {
    //! `build_client_udp_socket` must protect the QUIC carrier fd BEFORE quinn
    //! ever uses it (vpnservice-protect-invariant.md). The carrier binds to
    //! 0.0.0.0:0 and quinn connects later, so the WARP no-fail-closed-when-absent
    //! posture applies (no remote is known at the bind site). The protect
    //! callback registry is process-global, so every test serializes on
    //! `PROTECT_TEST_LOCK` and clears the slot before releasing it.

    use super::*;
    use std::os::fd::RawFd;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};

    use ripdpi_native_protect::{
        ProtectCallback, has_protect_callback, register_protect_callback, unregister_protect_callback,
    };

    // These tests are fully synchronous (no `.await`), so a std Mutex guard is
    // safe and does not risk clippy `await_holding_lock`.
    static PROTECT_TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
        calls: AtomicUsize,
        fail_with: Option<io::ErrorKind>,
    }

    impl RecordingCallback {
        fn new(fail_with: Option<io::ErrorKind>) -> Arc<Self> {
            Arc::new(Self { last_fd: AtomicI32::new(-1), calls: AtomicUsize::new(0), fail_with })
        }
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::SeqCst);
            self.calls.fetch_add(1, Ordering::SeqCst);
            match self.fail_with {
                Some(kind) => Err(io::Error::new(kind, "test protect failure")),
                None => Ok(()),
            }
        }
    }

    const SPEC: ClientSocketSpec = ClientSocketSpec {
        ipv6: false,
        bind_low_port: false,
        socket_protection: SocketProtectionPolicy::VpnRequired,
        outbound_bind_ip: None,
    };

    const INACTIVE_SPEC: ClientSocketSpec =
        ClientSocketSpec { socket_protection: SocketProtectionPolicy::Inactive, ..SPEC };

    #[test]
    fn carrier_socket_is_protected_before_quinn_owns_it() {
        let _guard = PROTECT_TEST_LOCK.lock().expect("test lock");
        let cb = RecordingCallback::new(None);
        register_protect_callback(cb.clone());

        let socket = build_client_udp_socket(SPEC).expect("bind + protect carrier socket");
        // The callback recorded the live fd of the bound socket — proving protect
        // ran after bind and before the socket was returned to quinn.
        assert_eq!(cb.calls.load(Ordering::SeqCst), 1, "protect callback must fire exactly once");
        assert_eq!(
            cb.last_fd.load(Ordering::SeqCst),
            socket.as_raw_fd(),
            "callback must receive the bound carrier socket fd"
        );

        unregister_protect_callback();
    }

    #[test]
    fn protect_failure_fails_socket_build_closed() {
        let _guard = PROTECT_TEST_LOCK.lock().expect("test lock");
        let cb = RecordingCallback::new(Some(io::ErrorKind::PermissionDenied));
        register_protect_callback(cb.clone());

        let err = build_client_udp_socket(SPEC).expect_err("protect rejection must fail the socket build");
        assert_eq!(cb.calls.load(Ordering::SeqCst), 1, "protect callback must be invoked");
        assert_eq!(err.kind(), io::ErrorKind::PermissionDenied, "protect error kind must propagate (fail-closed)");

        unregister_protect_callback();
    }

    #[test]
    fn no_callback_is_a_noop_so_host_paths_still_bind() {
        let _guard = PROTECT_TEST_LOCK.lock().expect("test lock");
        unregister_protect_callback();
        assert!(!has_protect_callback(), "test precondition: no protect callback registered");

        // The inactive proxy/host policy does not depend on callback presence.
        let socket = build_client_udp_socket(INACTIVE_SPEC).expect("carrier bind must succeed with no callback");
        assert!(socket.local_addr().is_ok(), "socket must be a live, bound UDP socket");
    }

    #[test]
    fn vpn_required_without_callback_fails_closed() {
        let _guard = PROTECT_TEST_LOCK.lock().expect("test lock");
        unregister_protect_callback();

        let error = build_client_udp_socket(SPEC).expect_err("VPN mode must reject a missing callback");
        assert_eq!(error.kind(), io::ErrorKind::NotConnected);
    }
}
