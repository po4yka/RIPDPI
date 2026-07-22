//! Test-only QUIC path-MTU-discovery (PMTUD) fault injection.
//!
//! [`MtuDropSocket`] is a [`quinn::AsyncUdpSocket`] wrapper that simulates a
//! path-MTU cliff: it silently drops QUIC **1-RTT (short-header) datagrams**
//! whose UDP-payload length exceeds a runtime-adjustable threshold, while always
//! forwarding **long-header** packets (Initial / Handshake / 0-RTT / Retry /
//! Version-Negotiation) so the QUIC handshake itself always completes. The drop
//! is silent — `try_send` reports success and `poll_recv` skips the datagram —
//! so the QUIC stack observes ordinary path loss and must recover via DPLPMTUD
//! black-hole detection (RFC 8899 §4.3). That is exactly the failure a carrier
//! handover, VPN nesting, or a jumbo-frame path produces in production.
//!
//! The threshold is shared through an [`MtuThreshold`] handle so a test can open
//! a connection with no dropping, let PMTUD validate a high path MTU, then lower
//! the threshold mid-connection to model the cliff.
//!
//! GSO/GRO are disabled (`max_{transmit,receive}_segments == 1`) so each
//! `try_send` / `poll_recv` carries exactly one datagram, keeping the size check
//! unambiguous.
//!
//! Dev/test only — never compiled into the app.

use std::fmt;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::task::{Context, Poll, ready};

use quinn::{AsyncUdpSocket, UdpPoller};

/// Threshold sentinel meaning "drop nothing" — no datagram is this large.
const PASS_ALL: usize = usize::MAX;

/// Handle to adjust an [`MtuDropSocket`]'s drop threshold after construction,
/// e.g. to lower the simulated path MTU mid-connection.
#[derive(Clone, Debug)]
pub struct MtuThreshold {
    max_short_header_payload: Arc<AtomicUsize>,
}

impl MtuThreshold {
    /// Start dropping 1-RTT datagrams whose UDP payload exceeds `max_payload`
    /// bytes. Long-header (handshake) packets are never dropped.
    pub fn set(&self, max_payload: usize) {
        // Ordering: Relaxed — a standalone threshold flag with no happens-before
        // relationship to other state; the recv/send task only needs the new
        // value to become visible eventually.
        self.max_short_header_payload.store(max_payload, Ordering::Relaxed);
    }

    /// Forward every datagram regardless of size (the construction default).
    pub fn pass_all(&self) {
        // Ordering: Relaxed — see `set`.
        self.max_short_header_payload.store(PASS_ALL, Ordering::Relaxed);
    }
}

/// A [`quinn::AsyncUdpSocket`] that drops oversized 1-RTT datagrams to simulate a
/// path-MTU cliff. See the crate-level docs for the mechanism.
pub struct MtuDropSocket {
    io: tokio::net::UdpSocket,
    max_short_header_payload: Arc<AtomicUsize>,
    dropped_tx: AtomicUsize,
    dropped_rx: AtomicUsize,
    max_dropped_tx_len: AtomicUsize,
    max_dropped_rx_len: AtomicUsize,
}

/// Redacted fault-injection evidence. Counts and sizes are retained; packet
/// bytes and peer addresses are deliberately never captured.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MtuDropEvidence {
    pub dropped_tx: usize,
    pub dropped_rx: usize,
    pub max_dropped_tx_len: usize,
    pub max_dropped_rx_len: usize,
}

impl fmt::Debug for MtuDropSocket {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MtuDropSocket")
            .field("local_addr", &self.io.local_addr().ok())
            .field("max_short_header_payload", &self.max_short_header_payload.load(Ordering::Relaxed))
            .finish()
    }
}

impl MtuDropSocket {
    /// Bind a fresh loopback UDP socket (`127.0.0.1:0`) and wrap it. Returns the
    /// socket (ready to hand to [`quinn::Endpoint::new_with_abstract_socket`] or
    /// a loopback fixture's `start_with_socket`), a threshold handle, and the
    /// bound address. Starts in pass-all mode.
    pub fn bind_localhost() -> io::Result<(Arc<Self>, MtuThreshold, SocketAddr)> {
        Self::bind_loopback(IpAddr::V4(Ipv4Addr::LOCALHOST))
    }

    /// IPv6 counterpart to [`Self::bind_localhost`].
    pub fn bind_localhost_v6() -> io::Result<(Arc<Self>, MtuThreshold, SocketAddr)> {
        Self::bind_loopback(IpAddr::V6(Ipv6Addr::LOCALHOST))
    }

    /// Bind the selected loopback family and start in pass-all mode.
    pub fn bind_loopback(ip: IpAddr) -> io::Result<(Arc<Self>, MtuThreshold, SocketAddr)> {
        if !ip.is_loopback() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "MTU-drop fixture requires a loopback address"));
        }
        let std_socket = std::net::UdpSocket::bind(SocketAddr::new(ip, 0))?;
        std_socket.set_nonblocking(true)?;
        let local_addr = std_socket.local_addr()?;
        let threshold = Arc::new(AtomicUsize::new(PASS_ALL));
        let socket = Arc::new(Self {
            io: tokio::net::UdpSocket::from_std(std_socket)?,
            max_short_header_payload: Arc::clone(&threshold),
            dropped_tx: AtomicUsize::new(0),
            dropped_rx: AtomicUsize::new(0),
            max_dropped_tx_len: AtomicUsize::new(0),
            max_dropped_rx_len: AtomicUsize::new(0),
        });
        Ok((socket, MtuThreshold { max_short_header_payload: threshold }, local_addr))
    }

    pub fn evidence(&self) -> MtuDropEvidence {
        MtuDropEvidence {
            dropped_tx: self.dropped_tx.load(Ordering::Relaxed),
            dropped_rx: self.dropped_rx.load(Ordering::Relaxed),
            max_dropped_tx_len: self.max_dropped_tx_len.load(Ordering::Relaxed),
            max_dropped_rx_len: self.max_dropped_rx_len.load(Ordering::Relaxed),
        }
    }

    /// Whether this datagram should be silently dropped: a QUIC short-header
    /// (1-RTT) packet — high bit of the first byte clear — that exceeds the
    /// current threshold. Empty and long-header datagrams are always kept.
    ///
    /// Note: a QUIC stateless reset is deliberately indistinguishable from a
    /// short-header packet, so it is classified as one here; resets are always
    /// far smaller than any realistic threshold, so this never false-drops.
    fn should_drop(&self, datagram: &[u8]) -> bool {
        let is_short_header = datagram.first().is_some_and(|first| first & 0x80 == 0);
        // Ordering: Relaxed — single-flag read, eventual visibility (see `MtuThreshold::set`).
        is_short_header && datagram.len() > self.max_short_header_payload.load(Ordering::Relaxed)
    }
}

impl AsyncUdpSocket for MtuDropSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        Box::pin(MtuDropPoller { socket: self })
    }

    fn try_send(&self, transmit: &quinn::udp::Transmit<'_>) -> io::Result<()> {
        // GSO is disabled (`max_transmit_segments() == 1`), so `contents` is a
        // single datagram.
        if self.should_drop(transmit.contents) {
            self.dropped_tx.fetch_add(1, Ordering::Relaxed);
            self.max_dropped_tx_len.fetch_max(transmit.contents.len(), Ordering::Relaxed);
            // Report the datagram as sent; the peer never receives it, so the
            // sender's DPLPMTUD must detect the black hole and probe down.
            return Ok(());
        }
        self.io.try_io(tokio::io::Interest::WRITABLE, || {
            let sent = self.io.try_send_to(transmit.contents, transmit.destination)?;
            if sent != transmit.contents.len() {
                return Err(io::Error::new(io::ErrorKind::WriteZero, "short MTU-drop UDP send"));
            }
            Ok(())
        })
    }

    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [std::io::IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        loop {
            ready!(self.io.poll_recv_ready(cx))?;
            let capacity = bufs.first().map_or(0, |buffer| buffer.len());
            let mut scratch = vec![0u8; capacity.max(2048)];
            match self.io.try_io(tokio::io::Interest::READABLE, || self.io.try_recv_from(&mut scratch)) {
                Ok((received, addr)) => {
                    if self.should_drop(&scratch[..received]) {
                        self.dropped_rx.fetch_add(1, Ordering::Relaxed);
                        self.max_dropped_rx_len.fetch_max(received, Ordering::Relaxed);
                        // Simulate the datagram lost on the path: keep polling.
                        continue;
                    }
                    let first = bufs
                        .first_mut()
                        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing QUIC receive buffer"))?;
                    if received > first.len() {
                        return Poll::Ready(Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            "datagram exceeds QUIC receive buffer",
                        )));
                    }
                    first[..received].copy_from_slice(&scratch[..received]);
                    meta[0] = quinn::udp::RecvMeta { addr, len: received, stride: received, ecn: None, dst_ip: None };
                    return Poll::Ready(Ok(1));
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.io.local_addr()
    }

    fn max_transmit_segments(&self) -> usize {
        1
    }

    fn max_receive_segments(&self) -> usize {
        1
    }

    fn may_fragment(&self) -> bool {
        false
    }
}

#[derive(Debug)]
struct MtuDropPoller {
    socket: Arc<MtuDropSocket>,
}

impl UdpPoller for MtuDropPoller {
    fn poll_writable(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.socket.io.poll_send_ready(cx)
    }
}

#[cfg(test)]
mod tests {
    //! Deterministic DoD control for the regression suite: a raw-quinn echo over
    //! an [`MtuDropSocket`]-wrapped client socket, run with and without DPLPMTUD,
    //! independent of any protocol crate.
    //!
    //! NOTE on the achievable teeth. A QUIC connection cannot be *killed* by
    //! dropping only oversized datagrams: RFC 9000 guarantees a 1200-byte base
    //! MTU that always passes, and quinn's black-hole detector lowers the path
    //! MTU to that base on sustained loss even when `mtu_discovery_config` is
    //! `None` (only *upward* re-probing is gated). So "disable PMTUD → connection
    //! dies" is physically false here. The real, observable PMTUD regression is
    //! *discovery*: with it enabled the connection validates a path MTU well
    //! above the 1200 base; with it disabled the connection is pinned at the base.
    //! [`pmtud_enabled_discovers_larger_path_mtu_than_disabled`] is the
    //! "disabling `mtu_discovery_config` fails a named test" teeth;
    //! [`mtu_drop_socket_injects_recoverable_cliff`] proves the fault injector
    //! caps the path MTU below the unconstrained path while the transfer survives.

    use std::net::{Ipv4Addr, SocketAddr, UdpSocket as StdUdpSocket};
    use std::sync::Arc;
    use std::time::Duration;

    use quinn::{
        AsyncUdpSocket, ClientConfig, Endpoint, EndpointConfig, MtuDiscoveryConfig, ServerConfig, TokioRuntime,
        TransportConfig,
    };
    use tokio::sync::watch;
    use tokio::task::JoinSet;

    use super::MtuDropSocket;

    #[tokio::test]
    // cancel-safe: the bound socket is test-owned and has no externally visible state.
    async fn mtu_drop_socket_supports_ipv6_loopback() {
        let (_socket, _threshold, address) = MtuDropSocket::bind_localhost_v6().expect("bind IPv6 MTU-drop socket");
        assert!(address.is_ipv6());
        assert!(address.ip().is_loopback());
    }

    #[tokio::test]
    // cancel-safe: socket, peer, threshold, and counters are confined to this test future.
    async fn mtu_drop_socket_reports_redacted_drop_evidence() {
        let (socket, threshold, _address) = MtuDropSocket::bind_localhost().expect("bind MTU-drop socket");
        let peer = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind peer");
        threshold.set(100);
        let payload = vec![0x40; 101];
        socket
            .try_send(&quinn::udp::Transmit {
                destination: peer.local_addr().expect("peer address"),
                ecn: None,
                contents: &payload,
                segment_size: None,
                src_ip: None,
            })
            .expect("silent drop reports send success");

        let evidence = socket.evidence();
        assert_eq!(evidence.dropped_tx, 1);
        assert_eq!(evidence.max_dropped_tx_len, payload.len());
        assert_eq!(evidence.dropped_rx, 0);
        assert_eq!(evidence.max_dropped_rx_len, 0);
    }

    /// Steady-state transfer size — enough 1-RTT packets that a path-MTU change
    /// is exercised across many sends.
    const PAYLOAD_LEN: usize = 256 * 1024;
    /// Warm-up transfer that lets DPLPMTUD validate the path before any cliff.
    const WARMUP_LEN: usize = 64 * 1024;
    /// Simulated post-warm-up path-MTU cliff, between the 1200 base and the
    /// loopback-validated MTU (~1452).
    const DROP_THRESHOLD: usize = 1300;
    /// quinn's RFC 9000 base max-UDP-payload; the floor a disabled-PMTUD path and
    /// a recovered black hole both settle at.
    const BASE_MTU: u16 = 1200;
    const PAYLOAD_SHA256: &str = "0653241fc6bafa8ce77356a1d25dbfe6e44fd1d22c7faeb480816dcafdac4b02";

    struct ScenarioOutcome {
        integrity: bool,
        pre_mtu: u16,
        post_mtu: u16,
        oversized_drop_delta: usize,
        black_hole_delta: u64,
    }

    struct RawEchoServer {
        endpoint: Endpoint,
        addr: SocketAddr,
        cert_der: Vec<u8>,
    }

    fn build_echo_server() -> RawEchoServer {
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("self-signed cert");
        let cert_der = cert.cert.der().to_vec();
        let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());

        let mut server_tls =
            rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("tls versions")
                .with_no_client_auth()
                .with_single_cert(vec![rustls::pki_types::CertificateDer::from(cert_der.clone())], key_der.into())
                .expect("single cert");
        server_tls.alpn_protocols = vec![b"h3".to_vec()];

        let quic_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(server_tls).expect("quic server crypto");
        let mut server_cfg = ServerConfig::with_crypto(Arc::new(quic_crypto));
        let mut transport = TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().expect("idle timeout")));
        server_cfg.transport = Arc::new(transport);

        let std_socket = StdUdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind server socket");
        let endpoint = Endpoint::new(EndpointConfig::default(), Some(server_cfg), std_socket, Arc::new(TokioRuntime))
            .expect("server endpoint");
        let addr = endpoint.local_addr().expect("server addr");
        RawEchoServer { endpoint, addr, cert_der }
    }

    /// Accept connections and own every connection task until shutdown.
    ///
    /// # Cancel safety
    /// NOT cancel-safe: callers signal shutdown and await this function so its
    /// connection `JoinSet` can abort and join all children.
    async fn run_echo_server(endpoint: Endpoint, mut shutdown: watch::Receiver<bool>) {
        let mut connections = JoinSet::new();
        loop {
            tokio::select! {
                _ = shutdown.changed() => break,
                completed = connections.join_next(), if !connections.is_empty() => {
                    let _ = completed;
                }
                incoming = endpoint.accept() => {
                    let Some(incoming) = incoming else { break };
                    let child_shutdown = shutdown.clone();
                    connections.spawn(run_echo_connection(incoming, child_shutdown));
                }
            }
        }
        while connections.join_next().await.is_some() {}
    }

    /// Own every stream task for one echo connection.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: callers signal shutdown and await the owning server,
    /// which aborts and joins this connection and all stream children.
    async fn run_echo_connection(incoming: quinn::Incoming, mut shutdown: watch::Receiver<bool>) {
        let Ok(connection) = incoming.await else { return };
        let mut streams = JoinSet::new();
        loop {
            tokio::select! {
                _ = shutdown.changed() => break,
                completed = streams.join_next(), if !streams.is_empty() => {
                    let _ = completed;
                }
                stream = connection.accept_bi() => {
                    let Ok((send, recv)) = stream else { break };
                    streams.spawn(echo_stream(send, recv));
                }
            }
        }
        streams.abort_all();
        while streams.join_next().await.is_some() {}
    }

    /// Echo one bidirectional stream.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: `copy` may consume bytes before cancellation; the owning
    /// connection aborts and joins this fixture-confined task during shutdown.
    async fn echo_stream(mut send: quinn::SendStream, mut recv: quinn::RecvStream) {
        let _ = tokio::io::copy(&mut recv, &mut send).await;
        let _ = send.finish();
    }

    fn client_config(cert_der: &[u8], enable_pmtud: bool) -> ClientConfig {
        let mut roots = rustls::RootCertStore::empty();
        roots.add(rustls::pki_types::CertificateDer::from(cert_der.to_vec())).expect("add root");
        let mut client_tls =
            rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("tls versions")
                .with_root_certificates(roots)
                .with_no_client_auth();
        client_tls.alpn_protocols = vec![b"h3".to_vec()];

        let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(client_tls).expect("quic client crypto");
        let mut config = ClientConfig::new(Arc::new(quic_crypto));
        let mut transport = TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(20).try_into().expect("idle timeout")));
        // The only variable under test. Starting MTU stays at quinn's 1200 base;
        // with discovery enabled DPLPMTUD probes the path up to a larger MTU,
        // with it disabled the connection is pinned at the base.
        transport.mtu_discovery_config(if enable_pmtud { Some(MtuDiscoveryConfig::default()) } else { None });
        config.transport_config(Arc::new(transport));
        config
    }

    /// Write `len` bytes and read the echo back concurrently on one stream.
    /// Returns whether the bytes round-tripped intact.
    ///
    /// # Cancel safety
    /// NOT cancel-safe: dropping mid-call leaves the stream partially read; the
    /// scenario owns the stream for its whole lifetime, so that never happens.
    async fn echo(send: &mut quinn::SendStream, recv: &mut quinn::RecvStream, len: usize) -> bool {
        let payload = vec![0xC3u8; len];
        let mut back = vec![0u8; len];
        let write = async { send.write_all(&payload).await.expect("write payload") };
        let read = async { recv.read_exact(&mut back).await.expect("read echo") };
        tokio::join!(write, read);
        back == payload
    }

    /// Establish a connection over an [`MtuDropSocket`], warm up so DPLPMTUD can
    /// validate the (initially unconstrained) path MTU, optionally lower the drop
    /// threshold to model a mid-connection cliff, then transfer `PAYLOAD_LEN` and
    /// report `(round-trip intact, post-transfer path MTU)`.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: warm-up, cliff injection, telemetry, and joined server
    /// cleanup form one fixture-owned transaction.
    async fn run_scenario(enable_pmtud: bool, drop_after_warmup: Option<usize>) -> ScenarioOutcome {
        let server = build_echo_server();
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let server_task = tokio::spawn(run_echo_server(server.endpoint.clone(), shutdown_rx));

        let (socket, threshold, _addr) = MtuDropSocket::bind_localhost().expect("bind client socket");
        let mut client =
            Endpoint::new_with_abstract_socket(EndpointConfig::default(), None, socket.clone(), Arc::new(TokioRuntime))
                .expect("client endpoint");
        client.set_default_client_config(client_config(&server.cert_der, enable_pmtud));

        let connection = client.connect(server.addr, "localhost").expect("connect call").await.expect("handshake");
        let (mut send, mut recv) = connection.open_bi().await.expect("open_bi");

        // Warm up on the unconstrained path so DPLPMTUD can validate a high MTU.
        assert!(echo(&mut send, &mut recv, WARMUP_LEN).await, "warm-up integrity");
        tokio::time::sleep(Duration::from_millis(300)).await;
        let before = connection.stats().path;

        if let Some(threshold_bytes) = drop_after_warmup {
            threshold.set(threshold_bytes);
        }

        let integrity = echo(&mut send, &mut recv, PAYLOAD_LEN).await;
        tokio::time::sleep(Duration::from_millis(100)).await;
        let after = connection.stats().path;
        let drop_evidence = socket.evidence();
        let outcome = ScenarioOutcome {
            integrity,
            pre_mtu: before.current_mtu,
            post_mtu: after.current_mtu,
            oversized_drop_delta: drop_evidence.dropped_tx + drop_evidence.dropped_rx,
            black_hole_delta: after.black_holes_detected.saturating_sub(before.black_holes_detected),
        };
        connection.close(0_u32.into(), b"test complete");
        client.close(0_u32.into(), b"test complete");
        server.endpoint.close(0_u32.into(), b"test complete");
        let _ = shutdown_tx.send(true);
        server_task.await.expect("join raw echo server");
        outcome
    }

    /// DoD teeth: disabling `mtu_discovery_config` is observable. On a clear
    /// loopback path, DPLPMTUD validates a path MTU well above the 1200 base;
    /// disabled, the connection is pinned at the base. A regression that drops
    /// `mtu_discovery_config(Some(..))` collapses `enabled_mtu` to the base and
    /// fails the strict inequality.
    /// # Cancel safety
    ///
    /// NOT cancel-safe: both scenarios and their joined echo-server cleanup form
    /// one comparison that must run to completion.
    #[tokio::test(flavor = "multi_thread")]
    async fn pmtud_enabled_discovers_larger_path_mtu_than_disabled() {
        let enabled = run_scenario(true, None).await;
        let disabled = run_scenario(false, None).await;
        assert!(enabled.integrity && disabled.integrity, "both connections must round-trip on a clear path");
        assert!(
            disabled.post_mtu <= BASE_MTU,
            "PMTUD disabled must stay pinned at the {BASE_MTU} base, got {}",
            disabled.post_mtu
        );
        assert!(
            enabled.post_mtu > disabled.post_mtu && enabled.post_mtu >= 1400,
            "DPLPMTUD must validate a path MTU ({}) above the disabled base ({}); \
             disabling mtu_discovery_config regresses this",
            enabled.post_mtu,
            disabled.post_mtu,
        );
        assert_eq!(enabled.oversized_drop_delta + disabled.oversized_drop_delta, 0);
        assert_eq!(enabled.black_hole_delta + disabled.black_hole_delta, 0);
        println!(
            "PMTUD_MEASUREMENT {{\"blackHoleDelta\":0,\"caseId\":\"pmtud_clear_path_control\",\"highMtu\":{},\"integrity\":true,\"oversizedDropDelta\":0,\"payloadLength\":{},\"payloadSha256\":\"{}\",\"postCliffMtu\":null,\"preMtu\":{},\"targetFamily\":\"ipv4\",\"version\":\"pmtud_measurement_v1\"}}",
            enabled.post_mtu, PAYLOAD_LEN, PAYLOAD_SHA256, disabled.post_mtu,
        );
    }

    /// Proves the [`MtuDropSocket`] fault injector works: after validating a high
    /// path MTU it lowers the threshold mid-connection; the transfer survives
    /// (QUIC base MTU + black-hole recovery) and the path MTU is capped below the
    /// unconstrained path.
    /// # Cancel safety
    ///
    /// NOT cancel-safe: capped and clear scenarios plus joined cleanup form one
    /// fault-control comparison that must run to completion.
    #[tokio::test(flavor = "multi_thread")]
    async fn mtu_drop_socket_injects_recoverable_cliff() {
        let capped = run_scenario(true, Some(DROP_THRESHOLD)).await;
        let clear = run_scenario(true, None).await;
        assert!(capped.integrity && clear.integrity, "the transfer must survive the mid-connection MTU drop");
        assert!(
            capped.post_mtu <= DROP_THRESHOLD as u16,
            "after the injected cliff the path MTU ({}) must fall to/below the threshold ({DROP_THRESHOLD})",
            capped.post_mtu,
        );
        assert!(
            capped.post_mtu < clear.post_mtu,
            "the cliff must cap the path MTU ({}) below the unconstrained path ({})",
            capped.post_mtu,
            clear.post_mtu,
        );
        assert!(capped.pre_mtu >= 1400 && clear.post_mtu >= capped.pre_mtu);
        assert!(capped.oversized_drop_delta > 0);
        assert!(capped.black_hole_delta > 0);
        println!(
            "PMTUD_MEASUREMENT {{\"blackHoleDelta\":{},\"caseId\":\"pmtud_black_hole_fault_control\",\"highMtu\":{},\"integrity\":true,\"oversizedDropDelta\":{},\"payloadLength\":{},\"payloadSha256\":\"{}\",\"postCliffMtu\":{},\"preMtu\":{},\"targetFamily\":\"ipv4\",\"version\":\"pmtud_measurement_v1\"}}",
            capped.black_hole_delta,
            clear.post_mtu,
            capped.oversized_drop_delta,
            PAYLOAD_LEN,
            PAYLOAD_SHA256,
            capped.post_mtu,
            capped.pre_mtu,
        );
    }
}
