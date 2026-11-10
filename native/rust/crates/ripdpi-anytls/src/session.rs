use std::collections::{HashMap, VecDeque};
use std::future::Future;
use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex, PoisonError, Weak};
use std::time::Duration;

use boring::x509::X509;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::{Mutex, mpsc, oneshot, watch};
use tokio::time::Instant;
use tokio_boring::SslStream;

use crate::frame::{Command, Frame, FrameError};
use crate::padding::{DEFAULT_PADDING_SCHEME, PaddingAction, PaddingScheme};

mod bridge;
mod owned_tasks;
mod udp;
pub use bridge::AnyTlsIo;
use owned_tasks::{OwnedTasks, Owner};

const UDP_OVER_TCP_V2_TARGET: &str = "sp.v2.udp-over-tcp.arpa";
/// An open that has seen no inbound carrier frame for this long sends a
/// HeartRequest.
const CARRIER_PROBE_AFTER: Duration = Duration::from_secs(3);
/// An open whose carrier sends no frame this long after a HeartRequest fails,
/// and the carrier is evicted for new opens.
const CARRIER_PROBE_TIMEOUT: Duration = Duration::from_secs(5);
/// An idle pooled session older than this is closed at the next sweep.
const IDLE_SESSION_TIMEOUT: Duration = Duration::from_secs(30);
/// Period of the idle-session sweep, as the reference client's default.
const IDLE_SWEEP_INTERVAL: Duration = Duration::from_secs(30);
/// A fresh session of a server whose version is not known yet waits this long
/// for cmdServerSettings. A v2 server sends it right after it handles the first
/// packet (Settings, SYN, PSH), before it dials the target, so it arrives within
/// one round trip. Silence for this long means a v1 server, which has no
/// SYNACK and ignores HeartRequest. The client remembers the answer, so only the
/// first session of a v1 server pays the wait.
const SERVER_SETTINGS_WAIT: Duration = Duration::from_secs(2);
/// A new session must finish its TCP connect, TLS handshake and auth in this time.
const SESSION_ESTABLISH_TIMEOUT: Duration = Duration::from_secs(10);

/// Open a protected TCP connection to the AnyTLS server.
///
/// Builds the socket explicitly and protects its fd via the in-process
/// `VpnService.protect()` registry BEFORE connect, so the non-loopback carrier
/// socket bypasses the app's own TUN route. REL-1. When `bind_ip` is set, the
/// resolved server address must match its family or the connect fails closed,
/// and the carrier socket is bound to the address before connect (interface
/// pinning).
async fn connect_protected_tcp(
    host: &str,
    port: u16,
    bind_ip: Option<std::net::IpAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<TcpStream> {
    let addrs = socket_protection.resolve_host(host, port).await?;
    let server_addr = match bind_ip {
        Some(ip) => addrs.into_iter().find(|addr| addr.is_ipv4() == ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no server address matches outbound bind IP family")
        })?,
        None => addrs
            .into_iter()
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no address resolved for anytls server"))?,
    };
    let socket = match server_addr {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    protect_outbound_socket(&socket, server_addr, socket_protection)?;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    let tcp = socket.connect(server_addr).await?;
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

/// Protect a freshly created outbound socket via the registered
/// `VpnService.protect()` callback before it connects to a non-loopback peer.
///
/// No-op for loopback. Fails closed for a non-loopback target when no callback
/// is registered. Mirrors the `ripdpi-vless` gold-standard helper. REL-1.
fn protect_outbound_socket<T: AsRawFd>(
    socket: &T,
    target: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect_non_loopback(socket.as_raw_fd(), target)
        .map_err(|error| io::Error::new(error.kind(), format!("protect AnyTLS outbound socket: {error}")))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TargetAddr {
    Ipv4(Ipv4Addr),
    Ipv6(Ipv6Addr),
    Domain(String),
}

#[derive(Clone, PartialEq, Eq)]
pub struct AnyTlsClientConfig {
    pub server_host: String,
    pub server_port: u16,
    pub server_name: String,
    pub password: String,
    pub tls_fingerprint_profile: String,
    pub root_certificate_pem: Option<String>,
    pub client_name: String,
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    /// Local address the carrier socket is bound to before connect (interface
    /// pinning). `None` binds to an unspecified address.
    pub outbound_bind_ip: Option<std::net::IpAddr>,
}

// Hand-written `Debug` so the AnyTLS password and root-certificate material never
// surface in logs, diagnostics, or crash reports. Mirrors the redaction pattern in
// `ripdpi-mieru` (`MieruConfig`) and `ripdpi-ssh` (`SshConfig`). The `Eq`/`PartialEq`
// derives are retained for test equality; only `Debug` is overridden.
impl std::fmt::Debug for AnyTlsClientConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("AnyTlsClientConfig")
            .field("server_host", &self.server_host)
            .field("server_port", &self.server_port)
            .field("server_name", &self.server_name)
            .field("password", &"<redacted>")
            .field("tls_fingerprint_profile", &self.tls_fingerprint_profile)
            .field("root_certificate_pem", &self.root_certificate_pem.as_ref().map(|_| "<redacted>"))
            .field("client_name", &self.client_name)
            .field("socket_protection", &self.socket_protection)
            .field("outbound_bind_ip", &self.outbound_bind_ip)
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnyTlsDatagram {
    pub target: TargetAddr,
    pub port: u16,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, thiserror::Error)]
pub enum AnyTlsError {
    #[error("invalid AnyTLS config: {0}")]
    Config(String),
    #[error("I/O error: {0}")]
    Io(String),
    #[error("TLS profile error: {0}")]
    TlsProfile(String),
    #[error("TLS config error: {0}")]
    TlsConfig(String),
    #[error("TLS handshake error: {0}")]
    TlsHandshake(String),
    #[error("AnyTLS frame error: {0}")]
    Frame(#[from] FrameError),
    #[error("AnyTLS padding error: {0}")]
    Padding(String),
    #[error("AnyTLS authentication was rejected: wrong password or not an AnyTLS server")]
    AuthenticationRejected,
    #[error("AnyTLS session closed")]
    SessionClosed,
    #[error("AnyTLS stream open rejected: {0}")]
    StreamOpenRejected(String),
    #[error("AnyTLS alert: {0}")]
    Alert(String),
    #[error("AnyTLS target domain is too long: {0}")]
    DomainTooLong(usize),
    #[error("AnyTLS UDP datagram payload is too long: {0}")]
    DatagramTooLong(usize),
    #[error("invalid AnyTLS datagram")]
    InvalidDatagram,
}

impl From<std::io::Error> for AnyTlsError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error.to_string())
    }
}

#[derive(Clone)]
pub struct AnyTlsClient {
    config: Arc<AnyTlsClientConfig>,
    state: Arc<StdMutex<ClientState>>,
    padding_scheme: Arc<Mutex<PaddingScheme>>,
    tasks: Arc<OwnedTasks>,
    _owner: Option<Arc<Owner>>,
    owner_ref: Weak<Owner>,
}

/// Sessions that carry no stream. Each session carries at most one stream, so
/// the carrier reader can wait for a slow stream reader without stalling a
/// sibling; a finished stream returns its session here for reuse.
struct ClientState {
    idle: Vec<IdleSession>,
    next_seq: u64,
    /// Protocol version of the server as learned from its sessions; 0 until known.
    learned_peer_version: u8,
    sweeper_running: bool,
    idle_timeout: Duration,
    sweep_interval: Duration,
}

struct IdleSession {
    session: SessionHandle,
    since: Instant,
}

struct SessionHandle {
    /// Creation order within the client; the idle session with the highest
    /// value is reused first.
    seq: u64,
    /// Protocol version of the server end of this session: 0 while unknown,
    /// 1 for a server that sent no cmdServerSettings, else its `v` setting.
    peer_version: Arc<watch::Sender<u8>>,
    outbound: mpsc::Sender<Outbound>,
    streams: Arc<StdMutex<HashMap<u32, StreamRoute>>>,
    next_stream_id: Arc<Mutex<u32>>,
    settings_sent: Arc<Mutex<bool>>,
    closing: Arc<AtomicBool>,
    /// Frames the carrier reader received; liveness evidence for opens.
    inbound_frames: Arc<AtomicU64>,
    tasks: Arc<OwnedTasks>,
}

impl Clone for SessionHandle {
    fn clone(&self) -> Self {
        Self {
            seq: self.seq,
            peer_version: Arc::clone(&self.peer_version),
            outbound: self.outbound.clone(),
            streams: Arc::clone(&self.streams),
            next_stream_id: Arc::clone(&self.next_stream_id),
            settings_sent: Arc::clone(&self.settings_sent),
            closing: Arc::clone(&self.closing),
            inbound_frames: Arc::clone(&self.inbound_frames),
            tasks: Arc::clone(&self.tasks),
        }
    }
}

struct StreamRoute {
    inbound: mpsc::Sender<Vec<u8>>,
    /// Set when the peer sent cmdFIN, before the route is removed: only a FIN
    /// is a clean end of the stream.
    peer_fin: Arc<AtomicBool>,
    open_ack: Option<oneshot::Sender<Result<(), AnyTlsError>>>,
}

enum Outbound {
    Batch(Vec<Frame>),
}

struct CancelOpenOnDrop(Option<oneshot::Sender<()>>);

impl CancelOpenOnDrop {
    fn disarm(&mut self) {
        self.0.take();
    }
}

impl Drop for CancelOpenOnDrop {
    fn drop(&mut self) {
        if let Some(sender) = self.0.take() {
            sender.send(()).ok();
        }
    }
}

pub struct AnyTlsStream {
    stream_id: u32,
    session: SessionHandle,
    pool: Weak<StdMutex<ClientState>>,
    inbound: mpsc::Receiver<Vec<u8>>,
    peer_fin: Arc<AtomicBool>,
    read_buffer: VecDeque<u8>,
    /// Set once [`AnyTlsStream::close`] queued the substream's FIN so `Drop`
    /// does not send a duplicate.
    fin_queued: bool,
    /// The SYN was committed to the carrier queue, so a FIN must follow it.
    syn_sent: bool,
    /// Return the session to the idle pool on drop. Cleared when the session
    /// state is doubtful (failed open, silent carrier).
    reusable: bool,
    owner: Option<Arc<Owner>>,
}

pub struct AnyTlsUdpOverTcp {
    stream: AnyTlsStream,
    pending: Vec<u8>,
}

impl std::fmt::Debug for AnyTlsStream {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("AnyTlsStream").field("stream_id", &self.stream_id).finish_non_exhaustive()
    }
}

/// The padding scheme of one server profile. Clients built with the same
/// value keep a scheme the server pushed with `cmdUpdatePaddingScheme` after
/// the session that learned it is gone, as the protocol requires.
#[derive(Clone)]
pub struct SharedPaddingScheme(Arc<Mutex<PaddingScheme>>);

impl Default for SharedPaddingScheme {
    fn default() -> Self {
        // Infallible: DEFAULT_PADDING_SCHEME is a constant; tests/padding.rs parses it.
        let scheme = PaddingScheme::parse(DEFAULT_PADDING_SCHEME.as_bytes()).expect("default padding scheme parses");
        Self(Arc::new(Mutex::new(scheme)))
    }
}

impl AnyTlsClient {
    pub fn new(config: AnyTlsClientConfig) -> Result<Self, AnyTlsError> {
        Self::with_padding_scheme(config, SharedPaddingScheme::default())
    }

    /// Builds a client that reads and updates `padding_scheme`.
    pub fn with_padding_scheme(
        config: AnyTlsClientConfig,
        padding_scheme: SharedPaddingScheme,
    ) -> Result<Self, AnyTlsError> {
        if config.server_host.is_empty() || config.server_name.is_empty() || config.password.is_empty() {
            return Err(AnyTlsError::Config("server_host, server_name, and password are required".to_owned()));
        }
        let tasks = Arc::new(OwnedTasks::default());
        let owner = Arc::new(Owner(Arc::clone(&tasks)));
        Ok(Self {
            tasks,
            owner_ref: Arc::downgrade(&owner),
            _owner: Some(owner),
            config: Arc::new(config),
            state: Arc::new(StdMutex::new(ClientState {
                idle: Vec::new(),
                next_seq: 0,
                learned_peer_version: 0,
                sweeper_running: false,
                idle_timeout: IDLE_SESSION_TIMEOUT,
                sweep_interval: IDLE_SWEEP_INTERVAL,
            })),
            padding_scheme: padding_scheme.0,
        })
    }

    fn worker_clone(&self) -> Self {
        Self {
            config: Arc::clone(&self.config),
            state: Arc::clone(&self.state),
            padding_scheme: Arc::clone(&self.padding_scheme),
            tasks: Arc::clone(&self.tasks),
            _owner: None,
            owner_ref: self.owner_ref.clone(),
        }
    }

    /// Close admission and signal every owned worker, including bridge pumps.
    pub fn cancel(&self) {
        self.tasks.cancel();
    }

    /// # Cancel safety
    /// The task group retains all joins across cancellation; repeated close
    /// resumes draining and does not erase an observed worker panic.
    pub async fn close(&self) -> Result<(), AnyTlsError> {
        let result = self.tasks.close().await;
        self.state.lock().unwrap_or_else(PoisonError::into_inner).idle.clear();
        result
    }

    /// # Cancel safety
    /// An owned worker retains pending-route cleanup; dropping this future
    /// signals cancellation without abandoning factory-owned work.
    pub async fn open_tcp(&self, target: TargetAddr, port: u16) -> Result<AnyTlsStream, AnyTlsError> {
        self.open_encoded_target(encode_target(&target, port)?).await
    }

    /// # Cancel safety
    /// Cancel-safe: the owned worker returns an unused session to the pool;
    /// a committed stream is closed by its drop, which sends the FIN.
    async fn open_encoded_target(&self, target: Vec<u8>) -> Result<AnyTlsStream, AnyTlsError> {
        let (cancel_tx, cancel_rx) = oneshot::channel();
        let (result_tx, result_rx) = oneshot::channel();
        let mut cancel_on_drop = CancelOpenOnDrop(Some(cancel_tx));
        let client = self.worker_clone();
        self.tasks.spawn(async move {
            client.open_tcp_worker(target, cancel_rx, result_tx).await;
        })?;

        let result = result_rx.await.unwrap_or(Err(AnyTlsError::SessionClosed));
        cancel_on_drop.disarm();
        result
    }

    /// # Cancel safety:
    /// Conditional: cancellation during session/send/ack waits drops the
    /// pending stream, which removes its route, queues a FIN only after a
    /// committed SYN, and returns the session to the pool. Abrupt abort
    /// requires whole-client teardown, whose carrier worker clears all routes.
    async fn open_tcp_worker(
        &self,
        target: Vec<u8>,
        mut cancel_rx: oneshot::Receiver<()>,
        result_tx: oneshot::Sender<Result<AnyTlsStream, AnyTlsError>>,
    ) {
        let session = tokio::select! {
            biased;
            _ = &mut cancel_rx => return,
            result = self.acquire_session() => match result {
                Ok(session) => session,
                Err(error) => {
                    result_tx.send(Err(error)).ok();
                    return;
                }
            },
        };
        let stream_id = session.allocate_stream_id().await;
        let frames = vec![Frame::control(Command::Syn, stream_id), Frame::with_data(Command::Psh, stream_id, target)];

        let (inbound_tx, inbound_rx) = mpsc::channel(32);
        let (ack_tx, mut ack_rx) = oneshot::channel();
        let peer_fin = Arc::new(AtomicBool::new(false));
        // The drop of this pending stream undoes every step below.
        let mut stream = AnyTlsStream {
            stream_id,
            session: session.clone(),
            pool: Arc::downgrade(&self.state),
            inbound: inbound_rx,
            peer_fin: Arc::clone(&peer_fin),
            read_buffer: VecDeque::new(),
            fin_queued: false,
            syn_sent: false,
            reusable: true,
            owner: None,
        };
        if !matches!(cancel_rx.try_recv(), Err(oneshot::error::TryRecvError::Empty)) {
            return;
        }
        session
            .streams
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(stream_id, StreamRoute { inbound: inbound_tx, peer_fin, open_ack: Some(ack_tx) });

        // A silently dead carrier (path black hole, no RST) would otherwise
        // hold every open until the caller's deadline.
        let carrier_dead = watch_carrier(&session);
        tokio::pin!(carrier_dead);
        let send_result = tokio::select! {
            biased;
            _ = &mut cancel_rx => return,
            () = &mut carrier_dead => Err(carrier_unresponsive()),
            result = session.send_open(frames, &self.config.client_name, &self.padding_scheme) => result,
        };
        if let Err(error) = send_result {
            stream.reusable = false;
            result_tx.send(Err(error)).ok();
            return;
        }
        stream.syn_sent = true;

        // v2 servers answer a SYN with SYNACK; v1 servers never do. Decide from
        // the session's peer version, waiting a bounded time for ServerSettings
        // when it is still unknown.
        let mut version_rx = session.peer_version.subscribe();
        let settings_deadline = tokio::time::sleep(SERVER_SETTINGS_WAIT);
        tokio::pin!(settings_deadline);
        let ack_result = loop {
            let version = *version_rx.borrow_and_update();
            if version == 1 {
                break Ok(Ok(()));
            }
            tokio::select! {
                biased;
                _ = &mut cancel_rx => return,
                result = &mut ack_rx => break result,
                () = &mut carrier_dead => {
                    stream.reusable = false;
                    result_tx.send(Err(carrier_unresponsive())).ok();
                    return;
                }
                _ = version_rx.changed() => {}
                () = &mut settings_deadline, if version == 0 => {
                    session.learn_peer_version(&self.state, 1);
                }
            }
        };
        match ack_result {
            Ok(Ok(())) => {
                let Some(owner) = self.owner_ref.upgrade() else {
                    stream.reusable = false;
                    result_tx.send(Err(AnyTlsError::SessionClosed)).ok();
                    return;
                };
                stream.owner = Some(owner);
                // A receiver that is gone drops the stream, which closes it.
                result_tx.send(Ok(stream)).ok();
            }
            Ok(Err(error)) => {
                stream.reusable = matches!(error, AnyTlsError::StreamOpenRejected(_));
                result_tx.send(Err(error)).ok();
            }
            Err(_) => {
                stream.reusable = false;
                result_tx.send(Err(AnyTlsError::SessionClosed)).ok();
            }
        }
    }

    /// # Cancel safety
    /// Cancel-safe: the idle pool is read without an await; a cancelled dial
    /// drops the unpublished transport.
    async fn acquire_session(&self) -> Result<SessionHandle, AnyTlsError> {
        if let Some(session) = self.take_idle_session() {
            return Ok(session);
        }
        tokio::time::timeout(SESSION_ESTABLISH_TIMEOUT, self.establish_network_session())
            .await
            .unwrap_or_else(|_| Err(AnyTlsError::Io("AnyTLS connection timed out".into())))
    }

    fn learned_peer_version(&self) -> u8 {
        self.state.lock().unwrap_or_else(PoisonError::into_inner).learned_peer_version
    }

    /// The idle session with the highest `seq`, if one is still alive.
    fn take_idle_session(&self) -> Option<SessionHandle> {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        state.idle.retain(|idle| !idle.session.is_dead());
        let newest = state.idle.iter().enumerate().max_by_key(|(_, idle)| idle.session.seq)?.0;
        Some(state.idle.swap_remove(newest).session)
    }

    /// # Cancel safety:
    /// Cancel-safe: the open worker returns an unused session to the pool; the
    /// association is published after the open returns, with no await between.
    pub async fn open_udp_over_tcp(&self) -> Result<AnyTlsUdpOverTcp, AnyTlsError> {
        let mut target = encode_target(&TargetAddr::Domain(UDP_OVER_TCP_V2_TARGET.to_owned()), 0)?;
        // UoT v2 requires its request before the server can acknowledge SYN:
        // isConnect=false, followed by an unused SOCKS destination 0.0.0.0:0.
        // Datagram mode carries the actual destination in every packet.
        target.extend_from_slice(&[0, 1, 0, 0, 0, 0, 0, 0]);
        let stream = self.open_encoded_target(target).await?;
        Ok(AnyTlsUdpOverTcp { stream, pending: Vec::new() })
    }

    pub async fn open_tcp_over<S>(
        config: AnyTlsClientConfig,
        transport: S,
        target: TargetAddr,
        port: u16,
    ) -> Result<AnyTlsStream, AnyTlsError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let client = Self::new(config)?;
        let session = client.establish_session(transport).await?;
        release_session(&client.state, session);
        client.open_tcp(target, port).await
    }

    async fn establish_network_session(&self) -> Result<SessionHandle, AnyTlsError> {
        // PROTECT INVARIANT: the carrier socket is protected before connect via the
        // in-process VpnService.protect registry (loopback-skipped, fail-closed under
        // a live TUN) — matching the ripdpi-vless / ripdpi-xhttp gold-standard
        // pattern. AnyTLS is a standalone relay kind (transport_descriptor.rs
        // build_anytls), reachable under a live TUN; own-UID exclusion via
        // computeAppRoutingPlan remains the second layer. `establish_session` over an
        // existing transport carries no OS socket of its own. REL-1 / REL-3. See
        // .claude/rules/vpnservice-protect-invariant.md.
        let tcp = connect_protected_tcp(
            self.config.server_host.as_str(),
            self.config.server_port,
            self.config.outbound_bind_ip,
            self.config.socket_protection,
        )
        .await?;
        self.establish_session(tcp).await
    }

    /// # Cancel safety:
    /// Cancel-safe: cancellation drops the unpublished TLS transport. Worker
    /// registration has no await after the session is built.
    async fn establish_session<S>(&self, transport: S) -> Result<SessionHandle, AnyTlsError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let mut tls = connect_tls(&self.config, transport).await?;
        // Clone the padding scheme and drop the guard before awaiting: holding
        // the lock across the `write_auth` TLS write would serialize concurrent
        // opens behind handshake I/O.
        let padding_scheme = self.padding_scheme.lock().await.clone();
        write_auth(&mut tls, &self.config.password, &padding_scheme).await?;

        let (outbound_tx, outbound_rx) = mpsc::channel(128);
        let streams = Arc::new(StdMutex::new(HashMap::new()));
        let seq = {
            let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
            state.next_seq += 1;
            state.next_seq
        };
        let peer_version = Arc::new(watch::channel(self.learned_peer_version()).0);
        let session = SessionHandle {
            seq,
            peer_version: Arc::clone(&peer_version),
            outbound: outbound_tx,
            streams: Arc::clone(&streams),
            next_stream_id: Arc::new(Mutex::new(1)),
            settings_sent: Arc::new(Mutex::new(false)),
            closing: Arc::new(AtomicBool::new(false)),
            inbound_frames: Arc::new(AtomicU64::new(0)),
            tasks: Arc::clone(&self.tasks),
        };
        let state = Arc::downgrade(&self.state);
        let session_padding_scheme = Arc::clone(&self.padding_scheme);
        let reader_outbound = session.outbound.downgrade();
        let lifetime = SessionWorker {
            streams,
            peer_version,
            closing: Arc::clone(&session.closing),
            inbound_frames: Arc::clone(&session.inbound_frames),
        };
        self.tasks.spawn(async move {
            run_session(tls, outbound_rx, reader_outbound, session_padding_scheme, state, lifetime).await;
        })?;
        Ok(session)
    }
}

/// Return a healthy session to the idle pool and make sure a sweeper runs.
fn release_session(state: &Arc<StdMutex<ClientState>>, session: SessionHandle) {
    if session.is_dead() {
        return;
    }
    let tasks = Arc::clone(&session.tasks);
    let start_sweeper = {
        let mut guard = state.lock().unwrap_or_else(PoisonError::into_inner);
        guard.idle.push(IdleSession { session, since: Instant::now() });
        !std::mem::replace(&mut guard.sweeper_running, true)
    };
    if start_sweeper {
        let weak = Arc::downgrade(state);
        if tasks.spawn(sweep_idle_sessions(weak)).is_err() {
            state.lock().unwrap_or_else(PoisonError::into_inner).sweeper_running = false;
        }
    }
}

/// Closes pooled sessions that stayed idle past the timeout; ends when the
/// pool is empty, and the next release starts it again.
///
/// # Cancel safety
/// cancel-safe: it only sleeps and edits the pool under a short std lock.
async fn sweep_idle_sessions(state: Weak<StdMutex<ClientState>>) {
    loop {
        let interval = match state.upgrade() {
            Some(state) => state.lock().unwrap_or_else(PoisonError::into_inner).sweep_interval,
            None => return,
        };
        tokio::time::sleep(interval).await;
        let Some(state) = state.upgrade() else { return };
        let mut guard = state.lock().unwrap_or_else(PoisonError::into_inner);
        let timeout = guard.idle_timeout;
        guard.idle.retain(|idle| !idle.session.is_dead() && idle.since.elapsed() < timeout);
        if guard.idle.is_empty() {
            guard.sweeper_running = false;
            return;
        }
    }
}

impl SessionHandle {
    /// Record the server's version for this session and for later sessions, but
    /// only while the session version is still unknown.
    fn learn_peer_version(&self, state: &Arc<StdMutex<ClientState>>, version: u8) {
        let learned = self.peer_version.send_if_modified(|current| {
            let unknown = *current == 0;
            if unknown {
                *current = version;
            }
            unknown
        });
        if learned {
            state.lock().unwrap_or_else(PoisonError::into_inner).learned_peer_version = version;
        }
    }

    fn is_dead(&self) -> bool {
        self.closing.load(Ordering::Acquire) || self.outbound.is_closed()
    }

    async fn allocate_stream_id(&self) -> u32 {
        let mut guard = self.next_stream_id.lock().await;
        let id = *guard;
        *guard += 1;
        id
    }

    /// # Cancel safety
    /// Cancel-safe: sender cancellation does not commit a batch. The Settings
    /// flag changes only after commit, with no await between commit and flag.
    async fn send_open(
        &self,
        mut frames: Vec<Frame>,
        client_name: &str,
        padding: &Mutex<PaddingScheme>,
    ) -> Result<(), AnyTlsError> {
        let mut sent = self.settings_sent.lock().await;
        if !*sent {
            let padding_md5 = padding.lock().await.padding_md5().to_owned();
            let settings = crate::frame::ClientSettings::new(client_name, padding_md5).encode();
            frames.insert(0, Frame::with_data(Command::Settings, 0, settings));
        }
        self.outbound.send(Outbound::Batch(frames)).await.map_err(|_| AnyTlsError::SessionClosed)?;
        *sent = true;
        Ok(())
    }
}

fn carrier_unresponsive() -> AnyTlsError {
    AnyTlsError::Io("AnyTLS carrier stopped responding".to_owned())
}

/// Completes when the carrier of `session` stops responding: no inbound frame
/// for [`CARRIER_PROBE_AFTER`] and then none for [`CARRIER_PROBE_TIMEOUT`]
/// after a HeartRequest. A live server answers the request even while it is
/// still dialing the target of a pending open. A carrier busy with a large
/// upload can also miss the deadline (the request waits behind the upload), so
/// the caller fails only its open and evicts the carrier; streams already on it
/// keep running.
///
/// # Cancel safety
///
/// cancel-safe: it only sleeps, reads a counter and queues a HeartRequest
/// with `try_send`.
async fn watch_carrier(session: &SessionHandle) {
    // A v1 server ignores HeartRequest, so probe only a server proven to be v2.
    if session.peer_version.subscribe().wait_for(|version| *version >= 2).await.is_err() {
        std::future::pending::<()>().await;
    }
    loop {
        let seen = session.inbound_frames.load(Ordering::Acquire);
        tokio::time::sleep(CARRIER_PROBE_AFTER).await;
        if session.inbound_frames.load(Ordering::Acquire) != seen {
            continue;
        }
        // A full queue means the writer is stalled as well; the timeout decides.
        session.outbound.try_send(Outbound::Batch(vec![Frame::control(Command::HeartRequest, 0)])).ok();
        tokio::time::sleep(CARRIER_PROBE_TIMEOUT).await;
        if session.inbound_frames.load(Ordering::Acquire) == seen {
            return;
        }
    }
}

fn queue_fin(outbound: &mpsc::Sender<Outbound>, stream_id: u32, tasks: &OwnedTasks) {
    let frame = Outbound::Batch(vec![Frame::control(Command::Fin, stream_id)]);
    match outbound.try_send(frame) {
        Ok(()) | Err(mpsc::error::TrySendError::Closed(_)) => {}
        Err(mpsc::error::TrySendError::Full(frame)) => {
            let outbound = outbound.clone();
            let _ = tasks.spawn(async move {
                let _ = outbound.send(frame).await;
            });
        }
    }
}

impl AnyTlsStream {
    /// # Cancel safety
    /// Not cancel-safe: completed chunks remain queued if a later chunk is
    /// cancelled. Callers must close the logical stream after cancellation.
    pub async fn write_all(&mut self, data: &[u8]) -> Result<(), AnyTlsError> {
        // The wire length is u16. Oversized application writes must be split
        // before entering the writer queue, otherwise frame encoding terminates
        // the entire shared TLS carrier, including unrelated streams.
        for chunk in data.chunks(usize::from(u16::MAX)) {
            self.session
                .outbound
                .send(Outbound::Batch(vec![Frame::with_data(Command::Psh, self.stream_id, chunk.to_vec())]))
                .await
                .map_err(|_| AnyTlsError::SessionClosed)?;
        }
        Ok(())
    }

    /// # Cancel safety
    /// Partial chunks remain buffered until the complete read returns.
    pub async fn read_exact_len(&mut self, len: usize) -> Result<Vec<u8>, AnyTlsError> {
        while self.read_buffer.len() < len {
            let chunk = self.inbound.recv().await.ok_or(AnyTlsError::SessionClosed)?;
            self.read_buffer.extend(chunk);
        }
        Ok(self.read_buffer.drain(..len).collect())
    }

    /// # Cancel safety
    /// No bytes are consumed until this future returns a complete chunk.
    pub async fn read_chunk(&mut self) -> Result<Vec<u8>, AnyTlsError> {
        if !self.read_buffer.is_empty() {
            return Ok(self.read_buffer.drain(..).collect());
        }
        self.inbound.recv().await.ok_or(AnyTlsError::SessionClosed)
    }

    /// Close the substream: queue a FIN frame. AnyTLS FIN closes the stream in
    /// both directions, so the peer sends nothing new after it; the route
    /// stays registered only for frames already in flight. It is not a
    /// half-close. Idempotent: a second `close` is a no-op, and after a
    /// successful `close` the `Drop` impl no longer sends a duplicate FIN.
    ///
    /// # Cancel safety
    /// Cancel-safe: a cancelled bounded send does not enqueue FIN. Once the
    /// send commits, the flag is set without another await.
    pub async fn close(&mut self) -> Result<(), AnyTlsError> {
        if self.fin_queued {
            return Ok(());
        }
        self.session
            .outbound
            .send(Outbound::Batch(vec![Frame::control(Command::Fin, self.stream_id)]))
            .await
            .map_err(|_| AnyTlsError::SessionClosed)?;
        self.fin_queued = true;
        Ok(())
    }
}

impl Drop for AnyTlsStream {
    fn drop(&mut self) {
        let routed =
            self.session.streams.lock().unwrap_or_else(PoisonError::into_inner).remove(&self.stream_id).is_some();
        if routed && self.syn_sent && !self.fin_queued {
            queue_fin(&self.session.outbound, self.stream_id, &self.session.tasks);
        }
        if self.reusable
            && let Some(pool) = self.pool.upgrade()
        {
            release_session(&pool, self.session.clone());
        }
    }
}

/// # Cancel safety:
///
/// NOT cancel-safe: a partial TLS handshake can remain on a borrowed transport;
/// discard the underlying transport if the future is cancelled.
async fn connect_tls<S>(config: &AnyTlsClientConfig, transport: S) -> Result<SslStream<S>, AnyTlsError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let server_name = ripdpi_tls_profiles::normalize_server_name(&config.server_name)
        .map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    // Keep the profile's version range: forcing TLS 1.3 only drops the TLS 1.2
    // suites and extensions the browser sends, so the ClientHello would match no
    // real browser. The server still negotiates TLS 1.3.
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| AnyTlsError::TlsProfile(error.to_string()))?;
    if let Some(root_pem) = &config.root_certificate_pem {
        let cert = X509::from_pem(root_pem.as_bytes()).map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
        builder.cert_store_mut().add_cert(cert).map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    }

    let connector = builder.build();
    let ssl = connector.configure().map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    tokio_boring::connect(ssl, &server_name, transport)
        .await
        .map_err(|error| AnyTlsError::TlsHandshake(error.to_string()))
}

async fn write_auth<S>(
    stream: &mut SslStream<S>,
    password: &str,
    padding_scheme: &PaddingScheme,
) -> Result<(), AnyTlsError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let padding_len =
        padding_scheme.auth_padding0_len(random_selector()).map_err(|error| AnyTlsError::Padding(error.to_string()))?;
    let mut packet = Vec::with_capacity(34 + padding_len);
    packet.extend_from_slice(&Sha256::digest(password.as_bytes()));
    packet.extend_from_slice(
        &u16::try_from(padding_len).map_err(|_| AnyTlsError::Padding("padding0 too long".to_owned()))?.to_be_bytes(),
    );
    packet.resize(packet.len() + padding_len, 0);
    stream.write_all(&packet).await?;
    Ok(())
}

/// A CSPRNG draw for one padding size range, as the reference client draws
/// every range with crypto/rand.
fn random_selector() -> u64 {
    let mut bytes = [0_u8; 8];
    // rand_bytes fails only when BoringSSL's DRBG is unusable; the range
    // minimum is then a padding size, not a security loss.
    boring::rand::rand_bytes(&mut bytes).map_or(0, |()| u64::from_ne_bytes(bytes))
}

struct SessionWorker {
    streams: Arc<StdMutex<HashMap<u32, StreamRoute>>>,
    peer_version: Arc<watch::Sender<u8>>,
    closing: Arc<AtomicBool>,
    inbound_frames: Arc<AtomicU64>,
}
impl Drop for SessionWorker {
    fn drop(&mut self) {
        self.closing.store(true, Ordering::Release);
        fail_all_streams(&self.streams, AnyTlsError::SessionClosed);
    }
}

/// # Cancel safety:
/// Conditional: cancellation discards the whole TLS carrier. The worker guard
/// marks it closed and removes every stream route when either direction ends.
async fn run_session<S>(
    tls: SslStream<S>,
    mut outbound_rx: mpsc::Receiver<Outbound>,
    outbound_tx: mpsc::WeakSender<Outbound>,
    padding_scheme: Arc<Mutex<PaddingScheme>>,
    client_state: Weak<StdMutex<ClientState>>,
    lifetime: SessionWorker,
) where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let streams = Arc::clone(&lifetime.streams);
    let closing = Arc::clone(&lifetime.closing);
    let (mut reader, mut writer) = tokio::io::split(tls);
    let write_padding_scheme = Arc::clone(&padding_scheme);
    let writer = async move {
        let mut packet_index = 1_u32;
        while let Some(outbound) = outbound_rx.recv().await {
            let Outbound::Batch(frames) = outbound;
            let mut bytes = Vec::new();
            for frame in frames {
                let encoded = frame.encode()?;
                bytes.extend_from_slice(&encoded);
            }
            let scheme = write_padding_scheme.lock().await.clone();
            let actions = scheme
                .write_plan_for_packet_with(packet_index, &bytes, random_selector)
                .map_err(|error| AnyTlsError::Padding(error.to_string()))?;
            packet_index += 1;
            for action in actions {
                match action {
                    PaddingAction::Payload(payload) => writer.write_all(&payload).await?,
                    PaddingAction::PayloadThenWaste { mut payload, waste_len } => {
                        // One write, so the payload and its padding leave as one TLS record.
                        payload.extend_from_slice(&Frame::with_data(Command::Waste, 0, vec![0; waste_len]).encode()?);
                        writer.write_all(&payload).await?;
                    }
                    PaddingAction::Waste { len } => {
                        let waste = Frame::with_data(Command::Waste, 0, vec![0; len]).encode()?;
                        writer.write_all(&waste).await?;
                    }
                }
            }
        }
        Ok::<(), AnyTlsError>(())
    };

    // A wrong password makes the server close the connection or fall back to
    // another service, so no valid AnyTLS frame ever arrives. A v1 server also
    // sends no frame after a good auth, but it keeps the connection open, so
    // only the end of the carrier counts as a verdict.
    let saw_known_frame = AtomicBool::new(false);
    let reader = async {
        loop {
            let frame = read_frame(&mut reader).await?;
            lifetime.inbound_frames.fetch_add(1, Ordering::Release);
            let Some(frame) = frame else {
                // A command this client does not know: it is skipped, as the
                // reference client does, so a newer server cannot kill the session.
                continue;
            };
            saw_known_frame.store(true, Ordering::Relaxed);
            match frame.command() {
                Command::SynAck => handle_synack(&streams, frame.stream_id(), frame.data()),
                Command::Psh => {
                    let inbound = streams
                        .lock()
                        .unwrap_or_else(PoisonError::into_inner)
                        .get(&frame.stream_id())
                        .map(|route| route.inbound.clone());
                    // A session carries one stream, so waiting for a slow reader
                    // is real TCP backpressure and stalls no sibling. A stream
                    // that is gone drops the data.
                    if let Some(inbound) = inbound {
                        inbound.send(frame.data().to_vec()).await.ok();
                    }
                }
                Command::Fin => {
                    let route = streams.lock().unwrap_or_else(PoisonError::into_inner).remove(&frame.stream_id());
                    if let Some(route) = route {
                        route.peer_fin.store(true, Ordering::Release);
                    }
                }
                Command::UpdatePaddingScheme => {
                    // An unusable scheme keeps the current one; the reference
                    // only logs it instead of closing the carrier.
                    if let Ok(scheme) = PaddingScheme::parse(frame.data()) {
                        *padding_scheme.lock().await = scheme;
                    }
                }
                Command::HeartRequest => {
                    outbound_tx
                        .upgrade()
                        .ok_or(AnyTlsError::SessionClosed)?
                        .send(Outbound::Batch(vec![Frame::control(Command::HeartResponse, frame.stream_id())]))
                        .await
                        .map_err(|_| AnyTlsError::SessionClosed)?;
                }
                Command::Alert => {
                    let message = String::from_utf8_lossy(frame.data()).into_owned();
                    tokio::time::sleep(std::time::Duration::from_millis(25)).await;
                    fail_all_streams(&streams, AnyTlsError::Alert(message.clone()));
                    return Err(AnyTlsError::Alert(message));
                }
                Command::ServerSettings => {
                    if let Some(version) = crate::frame::server_settings_version(frame.data()) {
                        lifetime.peer_version.send_replace(version);
                        if let Some(state) = client_state.upgrade() {
                            state.lock().unwrap_or_else(PoisonError::into_inner).learned_peer_version = version;
                        }
                    }
                }
                Command::Waste | Command::Settings | Command::HeartResponse | Command::Syn => {}
            }
        }
    };
    let session_result = supervise_session_io(reader, writer).await;
    closing.store(true, Ordering::Release);
    if let Some(client_state) = client_state.upgrade() {
        let mut state = client_state.lock().unwrap_or_else(PoisonError::into_inner);
        state.idle.retain(|idle| !Arc::ptr_eq(&idle.session.closing, &closing));
    }
    if !matches!(session_result, Err(AnyTlsError::Alert(_))) {
        let rejected =
            !saw_known_frame.load(Ordering::Relaxed) && matches!(session_result, Err(AnyTlsError::SessionClosed));
        fail_all_streams(
            &streams,
            if rejected { AnyTlsError::AuthenticationRejected } else { AnyTlsError::SessionClosed },
        );
    }
}

async fn supervise_session_io<R, W>(reader: R, writer: W) -> Result<(), AnyTlsError>
where
    R: Future<Output = Result<(), AnyTlsError>>,
    W: Future<Output = Result<(), AnyTlsError>>,
{
    tokio::pin!(reader);
    tokio::pin!(writer);
    tokio::select! {
        result = &mut reader => result,
        result = &mut writer => match result {
            Ok(()) => Err(AnyTlsError::SessionClosed),
            Err(error) => Err(error),
        },
    }
}

fn handle_synack(streams: &Arc<StdMutex<HashMap<u32, StreamRoute>>>, stream_id: u32, data: &[u8]) {
    if let Some(route) = streams.lock().unwrap_or_else(PoisonError::into_inner).get_mut(&stream_id)
        && let Some(open_ack) = route.open_ack.take()
    {
        let result = if data.is_empty() {
            Ok(())
        } else {
            Err(AnyTlsError::StreamOpenRejected(String::from_utf8_lossy(data).into_owned()))
        };
        open_ack.send(result).ok();
    }
}

fn fail_all_streams(streams: &Arc<StdMutex<HashMap<u32, StreamRoute>>>, error: AnyTlsError) {
    let mut guard = streams.lock().unwrap_or_else(PoisonError::into_inner);
    for route in guard.values_mut() {
        if let Some(open_ack) = route.open_ack.take() {
            open_ack.send(Err(error.clone())).ok();
        }
    }
    guard.clear();
}

/// Reads one frame. A frame with an unknown command is consumed whole, using
/// its declared length, and reported as `None`.
///
/// # Cancel safety
/// NOT cancel-safe: a cancelled read can leave a partial frame consumed. Only
/// the session reader calls it, and it drops the whole session on cancellation.
async fn read_frame<R>(reader: &mut R) -> Result<Option<Frame>, AnyTlsError>
where
    R: tokio::io::AsyncRead + Unpin,
{
    let mut header = [0_u8; crate::frame::FRAME_HEADER_LEN];
    reader.read_exact(&mut header).await.map_err(|error| {
        if matches!(error.kind(), std::io::ErrorKind::UnexpectedEof | std::io::ErrorKind::ConnectionReset) {
            AnyTlsError::SessionClosed
        } else {
            AnyTlsError::Io(error.to_string())
        }
    })?;
    let data_len = usize::from(u16::from_be_bytes([header[5], header[6]]));
    let mut frame = Vec::with_capacity(header.len() + data_len);
    frame.extend_from_slice(&header);
    frame.resize(frame.len() + data_len, 0);
    reader.read_exact(&mut frame[header.len()..]).await?;
    match Frame::decode(&frame) {
        Ok(decoded) => Ok(decoded.map(|(frame, _)| frame)),
        Err(FrameError::UnknownCommand(_)) => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn encode_target(target: &TargetAddr, port: u16) -> Result<Vec<u8>, AnyTlsError> {
    let mut bytes = Vec::new();
    match target {
        TargetAddr::Ipv4(addr) => {
            bytes.push(0x01);
            bytes.extend_from_slice(&addr.octets());
        }
        TargetAddr::Ipv6(addr) => {
            bytes.push(0x04);
            bytes.extend_from_slice(&addr.octets());
        }
        TargetAddr::Domain(domain) => {
            let domain_bytes = domain.as_bytes();
            let len = u8::try_from(domain_bytes.len()).map_err(|_| AnyTlsError::DomainTooLong(domain_bytes.len()))?;
            bytes.push(0x03);
            bytes.push(len);
            bytes.extend_from_slice(domain_bytes);
        }
    }
    bytes.extend_from_slice(&port.to_be_bytes());
    Ok(bytes)
}

#[cfg(test)]
mod redaction_tests {
    use super::AnyTlsClientConfig;

    fn config_with_secrets() -> AnyTlsClientConfig {
        AnyTlsClientConfig {
            server_host: "example.com".to_owned(),
            server_port: 443,
            server_name: "example.com".to_owned(),
            password: "super-secret-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: Some("-----BEGIN CERTIFICATE-----\nSECRET\n-----END CERTIFICATE-----".to_owned()),
            client_name: "ripdpi-anytls/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        }
    }

    #[test]
    fn debug_redacts_password_and_root_certificate() {
        let rendered = format!("{:?}", config_with_secrets());
        assert!(!rendered.contains("super-secret-password"), "password leaked: {rendered}");
        assert!(!rendered.contains("BEGIN CERTIFICATE"), "root certificate leaked: {rendered}");
        assert!(rendered.contains("<redacted>"), "expected redaction marker: {rendered}");
        // Non-secret fields stay visible for diagnostics.
        assert!(rendered.contains("example.com"), "server_name should remain visible: {rendered}");
    }

    #[test]
    fn debug_omits_certificate_marker_when_absent() {
        let mut config = config_with_secrets();
        config.root_certificate_pem = None;
        let rendered = format!("{config:?}");
        assert!(rendered.contains("root_certificate_pem: None"), "expected None rendering: {rendered}");
    }
}

#[cfg(test)]
mod test_support {
    use super::*;

    impl SessionHandle {
        /// A handle with no carrier task behind `outbound`.
        pub(super) fn detached(seq: u64, outbound: mpsc::Sender<Outbound>, tasks: Arc<OwnedTasks>) -> Self {
            Self {
                seq,
                peer_version: Arc::new(watch::channel(2).0),
                outbound,
                streams: Arc::new(StdMutex::new(HashMap::new())),
                next_stream_id: Arc::new(Mutex::new(1)),
                settings_sent: Arc::new(Mutex::new(false)),
                closing: Arc::new(AtomicBool::new(false)),
                inbound_frames: Arc::new(AtomicU64::new(0)),
                tasks,
            }
        }
    }

    impl AnyTlsStream {
        /// A stream that is not registered with any client pool.
        pub(super) fn detached(
            outbound: mpsc::Sender<Outbound>,
            inbound: mpsc::Receiver<Vec<u8>>,
            tasks: Arc<OwnedTasks>,
            owner: Option<Arc<Owner>>,
        ) -> Self {
            Self {
                stream_id: 1,
                session: SessionHandle::detached(1, outbound, tasks),
                pool: Weak::new(),
                inbound,
                peer_fin: Arc::new(AtomicBool::new(false)),
                read_buffer: VecDeque::new(),
                fin_queued: false,
                syn_sent: true,
                reusable: false,
                owner,
            }
        }
    }
}

#[cfg(test)]
mod session_cache_tests {
    use std::sync::Arc;
    use std::time::Duration;

    use tokio::sync::mpsc;

    use local_network_fixture::{AnyTlsLoopback, AnyTlsLoopbackConfig};

    use super::{AnyTlsClient, AnyTlsClientConfig, Outbound, SessionHandle, TargetAddr};

    #[tokio::test]
    async fn open_tcp_does_not_deadlock_on_closed_cached_session() {
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: 0,
            server_name: "localhost".to_owned(),
            password: "test-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: None,
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let (outbound, outbound_rx) = mpsc::channel::<Outbound>(1);
        drop(outbound_rx);
        client.state.lock().expect("state").idle.push(super::IdleSession {
            session: SessionHandle::detached(1, outbound, Arc::clone(&client.tasks)),
            since: tokio::time::Instant::now(),
        });

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            client.open_tcp(TargetAddr::Domain("example.test".to_owned()), 443),
        )
        .await;

        assert!(result.is_ok(), "closed cached session must not self-deadlock while being evicted");
        assert!(client.state.lock().expect("state").idle.is_empty(), "closed cached session must be evicted");
    }

    #[tokio::test]
    async fn dropping_client_and_last_stream_releases_session_state() {
        let fixture =
            AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: fixture.port(),
            server_name: fixture.server_name().to_owned(),
            password: "fixture-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let stream = client
            .open_tcp(TargetAddr::Ipv4(std::net::Ipv4Addr::LOCALHOST), fixture.target_port())
            .await
            .expect("stream");
        let state = Arc::downgrade(&client.state);

        drop(stream);
        drop(client);

        tokio::time::timeout(Duration::from_secs(1), async {
            while state.upgrade().is_some() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("session task must not keep client state alive");
    }

    #[tokio::test]
    async fn cancelled_open_before_batch_commit_does_not_enqueue_fin() {
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".into(),
            server_port: 1,
            server_name: "outbound.invalid".into(),
            password: "loopback-test-password".into(),
            tls_fingerprint_profile: "chrome".into(),
            root_certificate_pem: None,
            client_name: "outbound-test".into(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let (outbound, mut receiver) = mpsc::channel(1);
        outbound.send(Outbound::Batch(Vec::new())).await.expect("fill carrier queue");
        let session = SessionHandle::detached(1, outbound, Arc::clone(&client.tasks));
        let routes = Arc::clone(&session.streams);
        client
            .state
            .lock()
            .expect("state")
            .idle
            .push(super::IdleSession { session, since: tokio::time::Instant::now() });
        let caller = client.clone();
        let opening =
            tokio::spawn(async move { caller.open_tcp(TargetAddr::Domain("interop.invalid".into()), 443).await });
        tokio::time::timeout(Duration::from_secs(1), async {
            while routes.lock().expect("routes").is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("open route inserted before blocked batch");
        opening.abort();
        let _ = opening.await;
        tokio::time::timeout(Duration::from_secs(1), async {
            while !routes.lock().expect("routes").is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("cancelled route removed");
        receiver.recv().await.expect("original queued batch");
        let unexpected = tokio::time::timeout(Duration::from_millis(30), receiver.recv()).await;
        assert!(unexpected.is_err(), "a stream whose SYN was never queued must not create a background FIN send");
        let caller = client.clone();
        let second =
            tokio::spawn(async move { caller.open_tcp(TargetAddr::Domain("interop.invalid".into()), 443).await });
        let Outbound::Batch(frames) = tokio::time::timeout(Duration::from_secs(1), receiver.recv())
            .await
            .expect("second batch deadline")
            .expect("second open batch");
        let syn = frames.iter().find(|frame| frame.command() == super::Command::Syn).expect("SYN");
        assert_eq!(syn.stream_id(), 2, "cancelled allocation is not reused");
        super::handle_synack(&routes, syn.stream_id(), &[]);
        let stream = second.await.expect("second caller joined").expect("second stream accepted");
        client.close().await.expect("join client workers");
        assert_eq!(
            frames[0].command(),
            super::Command::Settings,
            "first committed open must send Settings even when stream 1 was cancelled"
        );
        drop(stream);
    }

    #[tokio::test]
    async fn cancelled_datagram_read_preserves_partial_wire_header() {
        use std::future::Future;
        let (outbound, _outbound_rx) = mpsc::channel(1);
        let (inbound_tx, inbound) = mpsc::channel(2);
        let stream = super::AnyTlsStream::detached(outbound, inbound, Arc::default(), None);
        let mut udp = super::AnyTlsUdpOverTcp { stream, pending: Vec::new() };
        inbound_tx.send(vec![0]).await.expect("first partial IPv4 header");
        let mut recv = Box::pin(udp.recv_datagram());
        std::future::poll_fn(|cx| {
            assert!(recv.as_mut().poll(cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
        drop(recv); // The uplink branch of the SOCKS pump can win here.
        inbound_tx.send(vec![127, 0, 0, 1, 1, 187, 0, 3, b'a', b'b', b'c']).await.expect("remaining frame");
        let packet = tokio::time::timeout(Duration::from_millis(100), udp.recv_datagram())
            .await
            .expect("datagram deadline")
            .expect("cancelled header must be retained");
        assert_eq!(packet.target, TargetAddr::Ipv4(std::net::Ipv4Addr::LOCALHOST));
        assert_eq!(packet.port, 443);
        assert_eq!(packet.payload, b"abc");
    }

    fn idle_client() -> AnyTlsClient {
        AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: 1,
            server_name: "localhost".to_owned(),
            password: "test-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: None,
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client")
    }

    #[tokio::test]
    async fn the_idle_session_with_the_highest_seq_is_reused_first() {
        let client = idle_client();
        let mut carriers = Vec::new();
        for seq in [2, 5, 3] {
            let (outbound, carrier) = mpsc::channel::<Outbound>(1);
            carriers.push(carrier);
            super::release_session(&client.state, SessionHandle::detached(seq, outbound, Arc::clone(&client.tasks)));
        }
        let order: Vec<u64> = std::iter::from_fn(|| client.take_idle_session()).map(|session| session.seq).collect();
        assert_eq!(order, vec![5, 3, 2]);
    }

    #[tokio::test]
    async fn idle_sessions_are_closed_after_the_timeout() {
        let client = idle_client();
        {
            let mut state = client.state.lock().expect("state");
            state.idle_timeout = Duration::from_millis(150);
            state.sweep_interval = Duration::from_millis(40);
        }
        let (outbound, _carrier) = mpsc::channel::<Outbound>(1);
        super::release_session(&client.state, SessionHandle::detached(1, outbound, Arc::clone(&client.tasks)));
        tokio::time::sleep(Duration::from_millis(60)).await;
        assert_eq!(client.state.lock().expect("state").idle.len(), 1, "a session younger than the timeout stays");
        tokio::time::timeout(Duration::from_secs(2), async {
            while !client.state.lock().expect("state").idle.is_empty() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("a session idle past the timeout is closed");
        tokio::time::timeout(Duration::from_secs(1), async {
            while client.state.lock().expect("state").sweeper_running {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("the sweeper ends with an empty pool");
    }

    /// A frame with an unknown command is skipped with its data; the next frame
    /// still decodes.
    #[tokio::test]
    async fn read_frame_skips_an_unknown_command_with_its_data() {
        use tokio::io::AsyncWriteExt;
        let (mut peer, mut local) = tokio::io::duplex(64);
        peer.write_all(&[11, 0, 0, 0, 7, 0, 3, b'a', b'b', b'c']).await.expect("unknown frame");
        peer.write_all(&[super::Command::Psh as u8, 0, 0, 0, 1, 0, 2, b'o', b'k']).await.expect("known frame");

        assert_eq!(super::read_frame(&mut local).await.expect("unknown frame is not an error"), None);
        let frame = super::read_frame(&mut local).await.expect("next frame").expect("known frame");
        assert_eq!((frame.command(), frame.stream_id(), frame.data()), (super::Command::Psh, 1, &b"ok"[..]));
    }

    #[tokio::test]
    async fn writer_failure_terminates_session_supervision() {
        let reader = std::future::pending::<Result<(), super::AnyTlsError>>();
        let writer = std::future::ready(Err(super::AnyTlsError::Io("writer failed".to_owned())));

        let result = tokio::time::timeout(Duration::from_millis(100), super::supervise_session_io(reader, writer))
            .await
            .expect("writer failure must terminate supervision")
            .expect_err("writer failure must close the session");

        assert!(matches!(result, super::AnyTlsError::Io(error) if error == "writer failed"));
    }
}

#[cfg(test)]
mod protect_tests {
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::{io, protect_outbound_socket};

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let err = protect_outbound_socket(
            &listener,
            non_loopback(),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect_err("must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn non_loopback_is_protected_via_callback() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd());
        unregister_protect_callback();
    }

    #[test]
    fn loopback_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1);
        unregister_protect_callback();
    }
}

#[cfg(test)]
mod tls_profile_tests {
    use tokio::io::AsyncReadExt;

    use super::{AnyTlsClientConfig, PaddingAction, PaddingScheme, connect_tls, random_selector};

    #[test]
    fn padding_ranges_are_drawn_independently_per_packet() {
        let scheme = PaddingScheme::parse(b"stop=2\n1=100-1000,100-1000").expect("scheme");
        let draws: Vec<(usize, usize)> = (0..32)
            .map(|_| match scheme.write_plan_for_packet_with(1, b"x", random_selector).expect("plan").as_slice() {
                [PaddingAction::PayloadThenWaste { waste_len, .. }, PaddingAction::Waste { len }] => (*waste_len, *len),
                other => panic!("unexpected plan {other:?}"),
            })
            .collect();
        assert!(draws.iter().any(|draw| *draw != draws[0]), "sizes must vary between packets: {draws:?}");
        assert!(draws.iter().any(|(first, second)| first + 8 != *second), "ranges must be drawn independently");
    }

    /// Reads the ClientHello the client writes and returns its cipher suites.
    async fn client_hello_cipher_suites(profile: &str) -> Vec<u16> {
        let config = AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: 443,
            server_name: "example.test".to_owned(),
            password: "test-password".to_owned(),
            tls_fingerprint_profile: profile.to_owned(),
            root_certificate_pem: None,
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        };
        let (client, mut server) = tokio::io::duplex(64 * 1024);
        let handshake = tokio::spawn(async move { connect_tls(&config, client).await.map(|_| ()) });
        let mut header = [0_u8; 5];
        server.read_exact(&mut header).await.expect("record header");
        let mut body = vec![0_u8; usize::from(u16::from_be_bytes([header[3], header[4]]))];
        server.read_exact(&mut body).await.expect("ClientHello");
        handshake.abort();
        // Handshake header (4), legacy_version (2), random (32), session id.
        let session_id_end = 4 + 2 + 32 + 1 + usize::from(body[38]);
        let suites_len = usize::from(u16::from_be_bytes([body[session_id_end], body[session_id_end + 1]]));
        body[session_id_end + 2..session_id_end + 2 + suites_len]
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| u16::from_be_bytes(*pair))
            .collect()
    }

    #[tokio::test]
    async fn client_hello_keeps_the_browser_profile_tls12_cipher_suites() {
        const TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256: u16 = 0xc02f;
        let suites = client_hello_cipher_suites("chrome").await;
        assert!(
            suites.contains(&TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256),
            "a TLS 1.3-only override drops the TLS 1.2 suites a browser offers: {suites:04x?}"
        );
    }
}

#[cfg(test)]
mod bridge_tests {
    use std::sync::Arc;
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::sync::mpsc;

    use super::{AnyTlsStream, OwnedTasks, Owner};

    /// A stalled uplink (full carrier queue) must not stop the pump from
    /// draining the stream's downlink; one coupled loop starved the mailbox
    /// into a reset.
    #[tokio::test]
    async fn a_stalled_uplink_does_not_stop_downlink_delivery() {
        let tasks = Arc::new(OwnedTasks::default());
        let (outbound, _stalled_carrier) = mpsc::channel(1);
        let (inbound_tx, inbound) = mpsc::channel(32);
        let stream =
            AnyTlsStream::detached(outbound, inbound, Arc::clone(&tasks), Some(Arc::new(Owner(Arc::clone(&tasks)))));
        let mut app = stream.into_io().expect("bridge");
        // The first chunk fills the carrier queue; the pump then waits to send the second.
        app.write_all(b"first").await.expect("first upload");
        tokio::time::sleep(Duration::from_millis(50)).await;
        app.write_all(b"second").await.expect("second upload");
        tokio::time::sleep(Duration::from_millis(50)).await;

        inbound_tx.send(b"reply".to_vec()).await.expect("downlink chunk");
        let mut reply = [0; 5];
        tokio::time::timeout(Duration::from_secs(1), app.read_exact(&mut reply))
            .await
            .expect("downlink must flow while the uplink is stalled")
            .expect("read");
        assert_eq!(&reply, b"reply");
    }
}
