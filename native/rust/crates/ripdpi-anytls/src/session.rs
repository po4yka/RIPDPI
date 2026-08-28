use std::collections::{HashMap, VecDeque};
use std::future::Future;
use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex, PoisonError, Weak};

use boring::ssl::SslVersion;
use boring::x509::X509;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::{Mutex, mpsc, oneshot};
use tokio_boring::SslStream;

use crate::frame::{Command, Frame, FrameError};
use crate::padding::{DEFAULT_PADDING_SCHEME, PaddingAction, PaddingScheme};

mod bridge;
mod owned_tasks;
mod udp;
pub use bridge::AnyTlsIo;
use owned_tasks::{OwnedTasks, Owner};

const UDP_OVER_TCP_V2_TARGET: &str = "sp.v2.udp-over-tcp.arpa";

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
    #[error("AnyTLS authentication was rejected")]
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
    state: Arc<Mutex<ClientState>>,
    padding_scheme: Arc<Mutex<PaddingScheme>>,
    tasks: Arc<OwnedTasks>,
    _owner: Option<Arc<Owner>>,
    owner_ref: Weak<Owner>,
}

struct ClientState {
    session: Option<SessionHandle>,
    session_waiters: Vec<oneshot::Sender<Result<SessionHandle, AnyTlsError>>>,
}

struct SessionHandle {
    outbound: mpsc::Sender<Outbound>,
    streams: Arc<StdMutex<HashMap<u32, StreamRoute>>>,
    next_stream_id: Arc<Mutex<u32>>,
    settings_sent: Arc<Mutex<bool>>,
    closing: Arc<AtomicBool>,
    tasks: Arc<OwnedTasks>,
}

impl Clone for SessionHandle {
    fn clone(&self) -> Self {
        Self {
            outbound: self.outbound.clone(),
            streams: Arc::clone(&self.streams),
            next_stream_id: Arc::clone(&self.next_stream_id),
            settings_sent: Arc::clone(&self.settings_sent),
            closing: Arc::clone(&self.closing),
            tasks: Arc::clone(&self.tasks),
        }
    }
}

struct StreamRoute {
    inbound: mpsc::Sender<Vec<u8>>,
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
    outbound: mpsc::Sender<Outbound>,
    inbound: mpsc::Receiver<Vec<u8>>,
    read_buffer: VecDeque<u8>,
    streams: Arc<StdMutex<HashMap<u32, StreamRoute>>>,
    /// Set once [`AnyTlsStream::close`] queued the substream's FIN so `Drop`
    /// does not send a duplicate.
    fin_queued: bool,
    tasks: Arc<OwnedTasks>,
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

impl AnyTlsClient {
    pub fn new(config: AnyTlsClientConfig) -> Result<Self, AnyTlsError> {
        if config.server_host.is_empty() || config.server_name.is_empty() || config.password.is_empty() {
            return Err(AnyTlsError::Config("server_host, server_name, and password are required".to_owned()));
        }
        let padding_scheme = PaddingScheme::parse(DEFAULT_PADDING_SCHEME.as_bytes())
            .map_err(|error| AnyTlsError::Padding(error.to_string()))?;
        let tasks = Arc::new(OwnedTasks::default());
        let owner = Arc::new(Owner(Arc::clone(&tasks)));
        Ok(Self {
            tasks,
            owner_ref: Arc::downgrade(&owner),
            _owner: Some(owner),
            config: Arc::new(config),
            state: Arc::new(Mutex::new(ClientState { session: None, session_waiters: Vec::new() })),
            padding_scheme: Arc::new(Mutex::new(padding_scheme)),
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
        let mut state = self.state.lock().await;
        state.session = None;
        state.session_waiters.clear();
        result
    }

    /// # Cancel safety
    /// An owned worker retains pending-route cleanup; dropping this future
    /// signals cancellation without abandoning factory-owned work.
    pub async fn open_tcp(&self, target: TargetAddr, port: u16) -> Result<AnyTlsStream, AnyTlsError> {
        self.open_encoded_target(encode_target(&target, port)?).await
    }

    /// # Cancel safety
    /// Cancel-safe: the owned worker rolls back an unaccepted route.
    async fn open_encoded_target(&self, target: Vec<u8>) -> Result<AnyTlsStream, AnyTlsError> {
        let (cancel_tx, cancel_rx) = oneshot::channel();
        let (result_tx, result_rx) = oneshot::channel();
        let (accepted_tx, accepted_rx) = oneshot::channel();
        let mut cancel_on_drop = CancelOpenOnDrop(Some(cancel_tx));
        let client = self.worker_clone();
        self.tasks.spawn(async move {
            client.open_tcp_worker(target, cancel_rx, result_tx, accepted_rx).await;
        })?;

        let result = result_rx.await.unwrap_or(Err(AnyTlsError::SessionClosed));
        if result.is_ok() {
            accepted_tx.send(()).ok();
        }
        cancel_on_drop.disarm();
        result
    }

    /// # Cancel safety:
    /// Conditional: cancellation during session/send/ack waits removes the
    /// pending route. Abrupt abort requires whole-client teardown, whose
    /// carrier worker clears all routes.
    async fn open_tcp_worker(
        &self,
        target: Vec<u8>,
        mut cancel_rx: oneshot::Receiver<()>,
        result_tx: oneshot::Sender<Result<AnyTlsStream, AnyTlsError>>,
        accepted_rx: oneshot::Receiver<()>,
    ) {
        let session = tokio::select! {
            biased;
            _ = &mut cancel_rx => return,
            result = self.session() => match result {
                Ok(session) => session,
                Err(error) => {
                    result_tx.send(Err(error)).ok();
                    return;
                }
            },
        };
        let stream_id = session.allocate_stream_id().await;

        let frames = vec![Frame::control(Command::Syn, stream_id), Frame::with_data(Command::Psh, stream_id, target)];

        if !matches!(cancel_rx.try_recv(), Err(oneshot::error::TryRecvError::Empty)) {
            return;
        }

        let (inbound_tx, inbound_rx) = mpsc::channel(32);
        let (ack_tx, mut ack_rx) = oneshot::channel();
        session
            .streams
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(stream_id, StreamRoute { inbound: inbound_tx, open_ack: Some(ack_tx) });

        let send_result = tokio::select! {
            biased;
            _ = &mut cancel_rx => {
                close_pending_stream(&session, stream_id, false);
                return;
            }
            result = session.send_open(frames, &self.config.client_name, &self.padding_scheme) => result,
        };
        if let Err(error) = send_result {
            close_pending_stream(&session, stream_id, false);
            self.clear_cached_session(&session).await;
            result_tx.send(Err(error)).ok();
            return;
        }

        let ack_result = tokio::select! {
            biased;
            _ = &mut cancel_rx => {
                close_pending_stream(&session, stream_id, true);
                return;
            }
            result = &mut ack_rx => result,
        };
        match ack_result {
            Ok(Ok(())) => {
                let Some(owner) = self.owner_ref.upgrade() else {
                    close_pending_stream(&session, stream_id, true);
                    let _ = result_tx.send(Err(AnyTlsError::SessionClosed));
                    return;
                };
                let stream = AnyTlsStream {
                    tasks: Arc::clone(&self.tasks),
                    owner: Some(owner),
                    stream_id,
                    outbound: session.outbound.clone(),
                    inbound: inbound_rx,
                    read_buffer: VecDeque::new(),
                    streams: Arc::clone(&session.streams),
                    fin_queued: false,
                };
                if result_tx.send(Ok(stream)).is_err() || accepted_rx.await.is_err() {
                    close_pending_stream(&session, stream_id, true);
                }
            }
            Ok(Err(error)) => {
                close_pending_stream(&session, stream_id, true);
                if matches!(error, AnyTlsError::SessionClosed) {
                    self.clear_cached_session(&session).await;
                }
                result_tx.send(Err(error)).ok();
            }
            Err(_) => {
                close_pending_stream(&session, stream_id, true);
                self.clear_cached_session(&session).await;
                result_tx.send(Err(AnyTlsError::SessionClosed)).ok();
            }
        }
    }

    async fn clear_cached_session(&self, session: &SessionHandle) {
        let mut state = self.state.lock().await;
        let should_clear = state.session.as_ref().is_some_and(|cached| cached.same_session(session));
        if should_clear {
            state.session = None;
        }
    }

    /// # Cancel safety:
    /// Cancel-safe: the owned open worker rolls back an unaccepted route; after
    /// the open returns, association publication has no intervening await.
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
        client.establish_session(transport).await?;
        client.open_tcp(target, port).await
    }

    /// # Cancel safety
    /// Establishment is owned by the client task group. Cancelling one waiter
    /// leaves siblings intact; client close joins the shared construction.
    async fn session(&self) -> Result<SessionHandle, AnyTlsError> {
        let (receiver, should_start) = {
            let mut state = self.state.lock().await;
            if let Some(session) = state.session.clone() {
                if !session.outbound.is_closed() && !session.closing.load(Ordering::Acquire) {
                    return Ok(session);
                }
                state.session = None;
            }

            let (sender, receiver) = oneshot::channel();
            let should_start = state.session_waiters.is_empty();
            state.session_waiters.push(sender);
            (receiver, should_start)
        };

        if should_start {
            let client = self.worker_clone();
            self.tasks.spawn(async move {
                let result =
                    tokio::time::timeout(std::time::Duration::from_secs(10), client.establish_network_session())
                        .await
                        .unwrap_or_else(|_| Err(AnyTlsError::Io("AnyTLS connection timed out".into())));
                let waiters = {
                    let mut state = client.state.lock().await;
                    std::mem::take(&mut state.session_waiters)
                };
                for waiter in waiters {
                    waiter.send(result.clone()).ok();
                }
            })?;
        }

        receiver.await.unwrap_or(Err(AnyTlsError::SessionClosed))
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
    /// registration and cached-session publication have no intervening await.
    async fn establish_session<S>(&self, transport: S) -> Result<SessionHandle, AnyTlsError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let mut tls = connect_tls(&self.config, transport).await?;
        // Clone the padding scheme and drop the guard before awaiting: holding the
        // state Mutex across the `write_auth` TLS write would serialize concurrent
        // `open_tcp` callers behind handshake I/O.
        let padding_scheme = self.padding_scheme.lock().await.clone();
        write_auth(&mut tls, &self.config.password, &padding_scheme).await?;

        let (outbound_tx, outbound_rx) = mpsc::channel(128);
        let streams = Arc::new(StdMutex::new(HashMap::new()));
        let session = SessionHandle {
            outbound: outbound_tx,
            streams: Arc::clone(&streams),
            next_stream_id: Arc::new(Mutex::new(1)),
            settings_sent: Arc::new(Mutex::new(false)),
            closing: Arc::new(AtomicBool::new(false)),
            tasks: Arc::clone(&self.tasks),
        };
        let state = Arc::downgrade(&self.state);
        let session_padding_scheme = Arc::clone(&self.padding_scheme);
        let reader_outbound = session.outbound.downgrade();
        let lifetime = SessionWorker { streams, closing: Arc::clone(&session.closing), tasks: Arc::clone(&self.tasks) };
        // Acquire the publication lock before spawning: no await can abandon an
        // unreturned carrier after it has entered the owned task group.
        let mut client_state = self.state.lock().await;
        self.tasks.spawn(async move {
            run_session(tls, outbound_rx, reader_outbound, session_padding_scheme, state, lifetime).await;
        })?;
        client_state.session = Some(session.clone());
        Ok(session)
    }
}

impl SessionHandle {
    fn same_session(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.next_stream_id, &other.next_stream_id)
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

fn close_pending_stream(session: &SessionHandle, stream_id: u32, syn_committed: bool) {
    if session.streams.lock().unwrap_or_else(PoisonError::into_inner).remove(&stream_id).is_some() && syn_committed {
        queue_fin(&session.outbound, stream_id, &session.tasks);
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
            self.outbound
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

    /// Half-close the substream towards the server: queue a FIN frame while
    /// leaving the stream registered, so the inbound direction keeps working.
    ///
    /// Use this when the local writer is done but the peer may still send
    /// data. Dropping the stream instead closes it in one step (route release
    /// plus FIN). Idempotent: a second `close` is a no-op, and after a
    /// successful `close` the `Drop` impl no longer sends a duplicate FIN.
    ///
    /// # Cancel safety
    /// Cancel-safe: a cancelled bounded send does not enqueue FIN. Once the
    /// send commits, the flag is set without another await.
    pub async fn close(&mut self) -> Result<(), AnyTlsError> {
        if self.fin_queued {
            return Ok(());
        }
        self.outbound
            .send(Outbound::Batch(vec![Frame::control(Command::Fin, self.stream_id)]))
            .await
            .map_err(|_| AnyTlsError::SessionClosed)?;
        self.fin_queued = true;
        Ok(())
    }
}

impl Drop for AnyTlsStream {
    fn drop(&mut self) {
        if self.streams.lock().unwrap_or_else(PoisonError::into_inner).remove(&self.stream_id).is_some()
            && !self.fin_queued
        {
            queue_fin(&self.outbound, self.stream_id, &self.tasks);
        }
    }
}

async fn connect_tls<S>(config: &AnyTlsClientConfig, transport: S) -> Result<SslStream<S>, AnyTlsError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| AnyTlsError::TlsProfile(error.to_string()))?;
    builder
        .set_min_proto_version(Some(SslVersion::TLS1_3))
        .map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    builder
        .set_max_proto_version(Some(SslVersion::TLS1_3))
        .map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    if let Some(root_pem) = &config.root_certificate_pem {
        let cert = X509::from_pem(root_pem.as_bytes()).map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
        builder.cert_store_mut().add_cert(cert).map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    }

    let connector = builder.build();
    let ssl = connector.configure().map_err(|error| AnyTlsError::TlsConfig(error.to_string()))?;
    tokio_boring::connect(ssl, &config.server_name, transport)
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
    let padding_len = padding_scheme.auth_padding0_len(0).map_err(|error| AnyTlsError::Padding(error.to_string()))?;
    let mut packet = Vec::with_capacity(34 + padding_len);
    packet.extend_from_slice(&Sha256::digest(password.as_bytes()));
    packet.extend_from_slice(
        &u16::try_from(padding_len).map_err(|_| AnyTlsError::Padding("padding0 too long".to_owned()))?.to_be_bytes(),
    );
    packet.resize(packet.len() + padding_len, 0);
    stream.write_all(&packet).await?;
    Ok(())
}

struct SessionWorker {
    streams: Arc<StdMutex<HashMap<u32, StreamRoute>>>,
    closing: Arc<AtomicBool>,
    tasks: Arc<OwnedTasks>,
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
    client_state: Weak<Mutex<ClientState>>,
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
                .write_plan_for_packet(packet_index, &bytes, 0)
                .map_err(|error| AnyTlsError::Padding(error.to_string()))?;
            packet_index += 1;
            for action in actions {
                match action {
                    PaddingAction::Payload(payload) => writer.write_all(&payload).await?,
                    PaddingAction::Waste { len } => {
                        let waste = Frame::with_data(Command::Waste, 0, vec![0; len]).encode()?;
                        writer.write_all(&waste).await?;
                    }
                }
            }
        }
        Ok::<(), AnyTlsError>(())
    };

    let reader = async {
        loop {
            let frame = read_frame(&mut reader).await?;
            match frame.command() {
                Command::SynAck => handle_synack(&streams, frame.stream_id(), frame.data()),
                Command::Psh => {
                    let stream_id = frame.stream_id();
                    let delivery_failed = {
                        let guard = streams.lock().unwrap_or_else(PoisonError::into_inner);
                        guard
                            .get(&stream_id)
                            .is_some_and(|route| route.inbound.try_send(frame.data().to_vec()).is_err())
                    };
                    if delivery_failed
                        && streams.lock().unwrap_or_else(PoisonError::into_inner).remove(&stream_id).is_some()
                    {
                        // Backpressure is per logical stream: a full or closed mailbox
                        // terminates only that stream instead of parking the carrier
                        // reader and blocking every multiplexed sibling behind it.
                        if let Some(outbound_tx) = outbound_tx.upgrade() {
                            queue_fin(&outbound_tx, stream_id, &lifetime.tasks);
                        }
                    }
                }
                Command::Fin => {
                    streams.lock().unwrap_or_else(PoisonError::into_inner).remove(&frame.stream_id());
                }
                Command::UpdatePaddingScheme => {
                    let scheme =
                        PaddingScheme::parse(frame.data()).map_err(|error| AnyTlsError::Padding(error.to_string()))?;
                    *padding_scheme.lock().await = scheme;
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
                Command::Waste
                | Command::Settings
                | Command::ServerSettings
                | Command::HeartResponse
                | Command::Syn => {}
            }
        }
    };
    let session_result = supervise_session_io(reader, writer).await;
    closing.store(true, Ordering::Release);
    if let Some(client_state) = client_state.upgrade() {
        let mut state = client_state.lock().await;
        if state.session.as_ref().is_some_and(|session| Arc::ptr_eq(&session.closing, &closing)) {
            state.session = None;
        }
    }
    if !matches!(session_result, Err(AnyTlsError::Alert(_))) {
        fail_all_streams(&streams, AnyTlsError::SessionClosed);
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
            open_ack
                .send(Err(match &error {
                    AnyTlsError::Alert(message) => AnyTlsError::Alert(message.clone()),
                    AnyTlsError::SessionClosed => AnyTlsError::SessionClosed,
                    _ => AnyTlsError::SessionClosed,
                }))
                .ok();
        }
    }
    guard.clear();
}

async fn read_frame<R>(reader: &mut R) -> Result<Frame, AnyTlsError>
where
    R: tokio::io::AsyncRead + Unpin,
{
    let mut header = [0_u8; crate::frame::FRAME_HEADER_LEN];
    reader.read_exact(&mut header).await.map_err(|error| {
        if error.kind() == std::io::ErrorKind::UnexpectedEof {
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
    Frame::decode(&frame)?.map(|(frame, _)| frame).ok_or(AnyTlsError::SessionClosed)
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
mod session_cache_tests {
    use std::collections::HashMap;
    use std::sync::Arc;
    use std::sync::atomic::AtomicBool;
    use std::time::Duration;

    use tokio::sync::{Mutex, mpsc};

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
        client.state.lock().await.session = Some(SessionHandle {
            outbound,
            streams: Arc::new(std::sync::Mutex::new(HashMap::new())),
            next_stream_id: Arc::new(Mutex::new(1)),
            settings_sent: Arc::new(Mutex::new(false)),
            closing: Arc::new(AtomicBool::new(false)),
            tasks: Arc::clone(&client.tasks),
        });

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            client.open_tcp(TargetAddr::Domain("example.test".to_owned()), 443),
        )
        .await;

        assert!(result.is_ok(), "closed cached session must not self-deadlock while being evicted");
        assert!(client.state.lock().await.session.is_none(), "closed cached session must be evicted");
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
        let routes = Arc::new(std::sync::Mutex::new(HashMap::new()));
        client.state.lock().await.session = Some(SessionHandle {
            outbound,
            streams: routes.clone(),
            next_stream_id: Arc::new(Mutex::new(1)),
            settings_sent: Arc::new(Mutex::new(false)),
            closing: Arc::new(AtomicBool::new(false)),
            tasks: Arc::clone(&client.tasks),
        });
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
        let stream = super::AnyTlsStream {
            stream_id: 1,
            outbound,
            inbound,
            read_buffer: std::collections::VecDeque::new(),
            streams: Arc::new(std::sync::Mutex::new(HashMap::new())),
            fin_queued: false,
            tasks: Arc::default(),
            owner: None,
        };
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
