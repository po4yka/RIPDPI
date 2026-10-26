use std::collections::HashMap;
use std::convert::Infallible;
use std::io;
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Weak};
use std::time::Duration;

use bytes::Bytes;
use http::{Method, Request, Response, StatusCode};
use http_body_util::{BodyExt, Empty};
use hyper::body::Incoming;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper_util::rt::{TokioIo, TokioTimer};
use tokio::net::TcpListener;
use tokio::sync::{Mutex, Notify, Semaphore, mpsc};
use tokio::task::AbortHandle;
use tokio::time::sleep;

use crate::config::OriginConfig;
use crate::errors::{classify_error, emit_structured_error};
use crate::http_body::{ChannelBody, XhttpBody};
use crate::path::extract_session_id;
use crate::session::{is_public_destination, run_session};

const STRUCTURED_READY_PREFIX: &str = "RIPDPI-READY|cloudflare-origin|";
/// How long a new session id may wait for its GET and POST. Anyone who can
/// reach the origin can open one before authentication, so this is short.
const SESSION_ATTACH_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_LIVE_SESSIONS: usize = 1_024;
/// Most HTTP connections served at once: a session uses one GET and one
/// POST connection, plus slack for keep-alive connections between requests.
const MAX_CONNECTIONS: usize = 2 * MAX_LIVE_SESSIONS + 128;
/// Deadline for a client to send a whole request head. hyper applies it only
/// when the builder has a timer.
const HEADER_READ_TIMEOUT: Duration = Duration::from_secs(10);
/// POST body bytes forwarded to a session before it authenticates. The VLESS
/// header is far smaller; the rest waits, so unauthenticated peers cannot make
/// the origin buffer their uploads.
const PRE_AUTH_BODY_BYTES: usize = 64 * 1024;
/// Pause after a failed accept, so descriptor exhaustion does not spin.
const ACCEPT_RETRY_DELAY: Duration = Duration::from_millis(100);

/// Session authentication states. Authentication and eviction both move a
/// session out of `AUTH_PENDING` with one compare-exchange, so exactly one of
/// them wins: an authenticated session is never evicted, and an evicted
/// session can never authenticate.
const AUTH_PENDING: u8 = 0;
const AUTH_DONE: u8 = 1;
const AUTH_EVICTED: u8 = 2;

// Lock order: sessions -> binding. No code holds a binding lock while it
// waits for the sessions lock.

struct SessionState {
    binding: Mutex<SessionBindingState>,
    /// Creation order, so a full table evicts the oldest pre-auth session.
    seq: u64,
    /// One of `AUTH_PENDING`, `AUTH_DONE` or `AUTH_EVICTED`.
    auth: AtomicU8,
    /// Woken when `auth` leaves `AUTH_PENDING`. Shared with the POST pump,
    /// which must not keep the session itself alive.
    auth_changed: Arc<Notify>,
}

impl SessionState {
    /// Marks the session authenticated. Returns false if it was evicted first.
    fn authenticate(&self) -> bool {
        // Ordering: AcqRel pairs with `evict`, so the two transitions agree;
        // the state guards no other memory.
        let won = self.auth.compare_exchange(AUTH_PENDING, AUTH_DONE, Ordering::AcqRel, Ordering::Acquire).is_ok();
        self.auth_changed.notify_waiters();
        won
    }

    /// Marks the session evicted. Returns false if it authenticated first.
    fn evict(&self) -> bool {
        // Ordering: see `authenticate`.
        let won = self.auth.compare_exchange(AUTH_PENDING, AUTH_EVICTED, Ordering::AcqRel, Ordering::Acquire).is_ok();
        self.auth_changed.notify_waiters();
        won
    }

    fn is_pending(&self) -> bool {
        // Ordering: a hint for candidate selection; `evict` decides.
        self.auth.load(Ordering::Acquire) == AUTH_PENDING
    }
}

struct SessionBindingState {
    get_attached: bool,
    post_attached: bool,
    started: bool,
    inbound_tx: Option<mpsc::Sender<Bytes>>,
    inbound_rx: Option<mpsc::Receiver<Bytes>>,
    /// The only GET-stream sender, moved into the session task at start, so
    /// the GET response ends as soon as that task drops it.
    outbound_tx: Option<mpsc::Sender<io::Result<Bytes>>>,
    outbound_rx: Option<mpsc::Receiver<io::Result<Bytes>>>,
    /// The running session task, aborted when the session is evicted.
    task: Option<AbortHandle>,
}

#[derive(Clone)]
struct OriginServer {
    config: Arc<OriginConfig>,
    /// Which upstream addresses a session may dial.
    target_allowed: fn(std::net::IpAddr) -> bool,
    sessions: Arc<Mutex<HashMap<String, Arc<SessionState>>>>,
    next_session_seq: Arc<AtomicU64>,
}

pub(crate) async fn run(config: OriginConfig) -> io::Result<()> {
    let listener = TcpListener::bind(&config.listen).await?;
    let listener_address = listener.local_addr()?.to_string();
    println!("{STRUCTURED_READY_PREFIX}{}|{listener_address}", env!("CARGO_PKG_VERSION"));

    let server = OriginServer {
        config: Arc::new(config),
        target_allowed: is_public_destination,
        sessions: Arc::new(Mutex::new(HashMap::new())),
        next_session_seq: Arc::new(AtomicU64::new(0)),
    };
    serve(listener, server).await
}

/// Serves HTTP/1 connections from `listener`. An accept error is reported
/// and retried, never returned: it would end the helper and the tunnel.
///
/// # Cancel safety
///
/// cancel-safe: connections run in spawned tasks; dropping the accept loop
/// only stops new connections.
async fn serve(listener: TcpListener, server: OriginServer) -> io::Result<()> {
    let connection_slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    loop {
        let stream = match listener.accept().await {
            Ok((stream, _)) => stream,
            Err(error) => {
                // EMFILE, ENFILE, ECONNABORTED and ENOBUFS pass; the published tunnel must outlive them.
                emit_structured_error(classify_error(&error), &error);
                sleep(ACCEPT_RETRY_DELAY).await;
                continue;
            }
        };
        let Ok(slot) = Arc::clone(&connection_slots).try_acquire_owned() else {
            // Closing at once keeps descriptors free; a queue would still hold them.
            drop(stream);
            continue;
        };
        let server = server.clone();
        tokio::spawn(async move {
            let _slot = slot;
            let io = TokioIo::new(stream);
            let service = service_fn(move |request| server.clone().handle_request(request));
            let connection = http1::Builder::new()
                .timer(TokioTimer::new())
                .header_read_timeout(HEADER_READ_TIMEOUT)
                .serve_connection(io, service);
            if let Err(error) = connection.await {
                let error = io::Error::other(format!("cloudflare origin connection failed: {error}"));
                emit_structured_error(classify_error(&error), &error);
            }
        });
    }
}

impl OriginServer {
    async fn handle_request(self, request: Request<Incoming>) -> Result<Response<XhttpBody>, Infallible> {
        let path = request.uri().path().to_owned();
        let Some(session_id) = extract_session_id(&self.config.path, &path) else {
            return Ok(empty_response(StatusCode::NOT_FOUND));
        };
        match *request.method() {
            Method::GET => Ok(self.handle_get(session_id).await),
            Method::POST => Ok(self.handle_post(session_id, request.into_body()).await),
            _ => Ok(empty_response(StatusCode::METHOD_NOT_ALLOWED)),
        }
    }

    async fn handle_get(&self, session_id: String) -> Response<XhttpBody> {
        let Some(session) = self.session_for(session_id.clone()).await else {
            return empty_response(StatusCode::SERVICE_UNAVAILABLE);
        };
        let outbound_rx = {
            let mut binding = session.binding.lock().await;
            if binding.get_attached {
                return empty_response(StatusCode::CONFLICT);
            }
            binding.get_attached = true;
            binding.outbound_rx.take()
        };
        let Some(outbound_rx) = outbound_rx else {
            return empty_response(StatusCode::CONFLICT);
        };
        self.maybe_start_session(session_id, session).await;
        response(StatusCode::OK, ChannelBody::new(outbound_rx).boxed())
    }

    async fn handle_post(&self, session_id: String, body: Incoming) -> Response<XhttpBody> {
        let Some(session) = self.session_for(session_id.clone()).await else {
            return empty_response(StatusCode::SERVICE_UNAVAILABLE);
        };
        let inbound_tx = {
            let mut binding = session.binding.lock().await;
            if binding.post_attached {
                return empty_response(StatusCode::CONFLICT);
            }
            binding.post_attached = true;
            let Some(inbound_tx) = binding.inbound_tx.take() else {
                return empty_response(StatusCode::CONFLICT);
            };
            inbound_tx
        };

        let (weak, auth_changed) = (Arc::downgrade(&session), Arc::clone(&session.auth_changed));
        tokio::spawn(async move {
            if let Err(error) = pump_request_body(body, inbound_tx, &weak, &auth_changed).await {
                emit_structured_error(classify_error(&error), &error);
            }
        });

        self.maybe_start_session(session_id, session).await;
        empty_response(StatusCode::OK)
    }

    /// Returns the session for `session_id`, creating it when it is new.
    ///
    /// A full table evicts the oldest session that has not authenticated, so
    /// unauthenticated peers that open many ids cannot lock out real clients.
    /// Only a table full of authenticated sessions rejects a new id.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: cancellation while waiting for a lock leaves the map
    /// unchanged; an eviction removes its entry before it aborts the task, and
    /// insertion and timer registration complete without further awaits.
    async fn session_for(&self, session_id: String) -> Option<Arc<SessionState>> {
        let mut sessions = self.sessions.lock().await;
        if let Some(session) = sessions.get(&session_id) {
            return Some(Arc::clone(session));
        }
        if sessions.len() >= MAX_LIVE_SESSIONS && !evict_oldest_unauthenticated(&mut sessions).await {
            return None;
        }
        // Ordering: the counter only orders sessions for eviction; it
        // publishes no other memory.
        let session = new_session(self.next_session_seq.fetch_add(1, Ordering::Relaxed));
        sessions.insert(session_id.clone(), Arc::clone(&session));
        let sessions = Arc::clone(&self.sessions);
        let weak = Arc::downgrade(&session);
        tokio::spawn(async move {
            sleep(SESSION_ATTACH_TIMEOUT).await;
            expire_unstarted_session(&sessions, &session_id, &weak).await;
        });
        Some(session)
    }

    /// Starts the session task once both GET and POST are attached.
    ///
    /// The task is spawned and its abort handle stored while the binding lock
    /// is held, so an eviction (which takes the binding lock after the sessions
    /// lock) always finds the handle of a started session.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the only await is the binding lock; spawning and storing
    /// the handle happen with no await in between.
    async fn maybe_start_session(&self, session_id: String, session: Arc<SessionState>) {
        let mut binding = session.binding.lock().await;
        if !(binding.get_attached && binding.post_attached) || binding.started {
            return;
        }
        binding.started = true;
        let (Some(inbound_rx), Some(outbound_tx)) = (binding.inbound_rx.take(), binding.outbound_tx.take()) else {
            return;
        };
        let expected_uuid = self.config.uuid;
        let target_allowed = self.target_allowed;
        let protect_path = self.config.protect_path.clone();
        let sessions = Arc::clone(&self.sessions);
        let weak = Arc::downgrade(&session);
        let task = tokio::spawn(async move {
            let on_authenticated = || weak.upgrade().is_some_and(|session| session.authenticate());
            let result = run_session(
                inbound_rx,
                outbound_tx,
                expected_uuid,
                protect_path.as_deref(),
                target_allowed,
                on_authenticated,
            )
            .await;
            if let Err(error) = result {
                emit_structured_error(classify_error(&error), &error);
            }
            remove_if_current(&sessions, &session_id, &weak).await;
        });
        binding.task = Some(task.abort_handle());
    }
}

/// Removes the oldest session that has not authenticated and aborts its task.
/// Returns false when every session has authenticated.
///
/// The caller holds the sessions lock. A session that authenticates during
/// the scan wins its compare-exchange, so it is skipped, not evicted.
///
/// # Cancel safety
///
/// cancel-safe: the state change and the map removal happen with no await in
/// between. A cancel while waiting for the binding lock only skips the abort;
/// the session is already `AUTH_EVICTED`, so it can never authenticate and
/// ends at its own header deadline.
async fn evict_oldest_unauthenticated(sessions: &mut HashMap<String, Arc<SessionState>>) -> bool {
    let mut candidates: Vec<(u64, String)> = sessions
        .iter()
        .filter(|(_, session)| session.is_pending())
        .map(|(id, session)| (session.seq, id.clone()))
        .collect();
    candidates.sort_unstable();
    let Some(evicted) = candidates.into_iter().find_map(|(_, id)| {
        let won = sessions.get(&id).is_some_and(|session| session.evict());
        if won { sessions.remove(&id) } else { None }
    }) else {
        return false;
    };
    if let Some(task) = evicted.binding.lock().await.task.take() {
        task.abort();
    }
    true
}

/// Removes `session_id` only while it still maps to the session in `weak`, so
/// a finished session never removes a newer session that reused its id.
///
/// # Cancel safety
///
/// cancel-safe: the map is changed only while its lock is held.
async fn remove_if_current(
    sessions: &Mutex<HashMap<String, Arc<SessionState>>>,
    session_id: &str,
    weak: &Weak<SessionState>,
) {
    let mut sessions = sessions.lock().await;
    let is_current = match (sessions.get(session_id), weak.upgrade()) {
        (Some(current), Some(session)) => Arc::ptr_eq(current, &session),
        _ => false,
    };
    if is_current {
        sessions.remove(session_id);
    }
}

/// # Cancel safety
///
/// cancel-safe: the map entry stays unchanged until both locks are held.
async fn expire_unstarted_session(
    sessions: &Mutex<HashMap<String, Arc<SessionState>>>,
    session_id: &str,
    weak: &Weak<SessionState>,
) {
    let Some(session) = weak.upgrade() else { return };
    let mut sessions = sessions.lock().await;
    if !sessions.get(session_id).is_some_and(|current| Arc::ptr_eq(current, &session)) {
        return;
    }
    let binding = session.binding.lock().await;
    if !binding.started {
        sessions.remove(session_id);
    }
}

fn new_session(seq: u64) -> Arc<SessionState> {
    let (inbound_tx, inbound_rx) = mpsc::channel::<Bytes>(64);
    let (outbound_tx, outbound_rx) = mpsc::channel::<io::Result<Bytes>>(64);
    Arc::new(SessionState {
        binding: Mutex::new(SessionBindingState {
            get_attached: false,
            post_attached: false,
            started: false,
            inbound_tx: Some(inbound_tx),
            inbound_rx: Some(inbound_rx),
            outbound_tx: Some(outbound_tx),
            outbound_rx: Some(outbound_rx),
            task: None,
        }),
        seq,
        auth: AtomicU8::new(AUTH_PENDING),
        auth_changed: Arc::new(Notify::new()),
    })
}

/// Forwards POST body chunks to the session. It stops when the session's
/// receiver is gone, so a stalled body cannot keep its connection open after
/// the session ended or expired. After `PRE_AUTH_BODY_BYTES` it waits until
/// the session authenticates.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the body, which closes its connection.
async fn pump_request_body(
    mut body: Incoming,
    inbound_tx: mpsc::Sender<Bytes>,
    session: &Weak<SessionState>,
    auth_changed: &Notify,
) -> io::Result<()> {
    let mut forwarded = 0usize;
    loop {
        if forwarded >= PRE_AUTH_BODY_BYTES && !wait_until_authenticated(session, auth_changed, &inbound_tx).await {
            break;
        }
        let frame = tokio::select! {
            frame = body.frame() => frame,
            () = inbound_tx.closed() => break,
        };
        let Some(frame) = frame else { break };
        let frame = frame.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("POST body read failed: {error}"))
        })?;
        if let Ok(data) = frame.into_data() {
            forwarded = forwarded.saturating_add(data.len());
            if inbound_tx.send(data).await.is_err() {
                break;
            }
        }
    }
    Ok(())
}

/// Returns true once the session has authenticated, false when it was
/// evicted, ended or expired. Holds no strong reference while it waits.
///
/// # Cancel safety
///
/// cancel-safe: it only waits for a notification or a channel close.
async fn wait_until_authenticated(
    session: &Weak<SessionState>,
    auth_changed: &Notify,
    inbound_tx: &mpsc::Sender<Bytes>,
) -> bool {
    loop {
        let notified = auth_changed.notified();
        tokio::pin!(notified);
        // Register before the state check, so a change after it still wakes us.
        notified.as_mut().enable();
        // Ordering: Acquire pairs with the AcqRel transitions in `authenticate` and `evict`.
        match session.upgrade().map(|state| state.auth.load(Ordering::Acquire)) {
            Some(AUTH_DONE) => return true,
            Some(AUTH_PENDING) => {}
            _ => return false,
        }
        tokio::select! {
            () = &mut notified => {}
            () = inbound_tx.closed() => return false,
        }
    }
}

fn empty_response(status: StatusCode) -> Response<XhttpBody> {
    response(status, Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
}

fn response(status: StatusCode, body: XhttpBody) -> Response<XhttpBody> {
    Response::builder().status(status).body(body).expect("response build")
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::Arc;
    use std::time::Duration;

    use bytes::Bytes;
    use tokio::io::AsyncWriteExt;
    use tokio::net::TcpListener;
    use tokio::sync::Mutex;

    use super::{MAX_LIVE_SESSIONS, OriginConfig, OriginServer, expire_unstarted_session, new_session};

    fn server() -> OriginServer {
        OriginServer {
            config: Arc::new(OriginConfig {
                listen: "127.0.0.1:0".to_owned(),
                path: "/".to_owned(),
                uuid: [0; 16],
                protect_path: None,
            }),
            // The tests dial loopback stand-ins for upstream targets.
            target_allowed: |_| true,
            sessions: Arc::new(Mutex::new(HashMap::new())),
            next_session_seq: Arc::new(std::sync::atomic::AtomicU64::new(0)),
        }
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns its socket and channel resources.
    async fn post_sender_closure_reaches_session_while_state_is_retained() {
        let session = new_session(0);
        let mut binding = session.binding.lock().await;
        let sender = binding.inbound_tx.take().expect("POST sender");
        let mut receiver = binding.inbound_rx.take().expect("session receiver");
        drop(binding);

        drop(sender);
        assert!(receiver.recv().await.is_none());
    }
    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: this test owns only in-memory session entries.
    async fn pending_session_expires_without_removing_replacement_or_active_session() {
        let server = server();
        let first = server.session_for("one".to_owned()).await.expect("first session");
        let old = Arc::downgrade(&first);
        expire_unstarted_session(&server.sessions, "one", &old).await;
        assert!(server.sessions.lock().await.is_empty());

        let current = server.session_for("one".to_owned()).await.expect("replacement session");
        expire_unstarted_session(&server.sessions, "one", &old).await;
        assert!(Arc::ptr_eq(server.sessions.lock().await.get("one").expect("current entry"), &current));

        current.binding.lock().await.started = true;
        expire_unstarted_session(&server.sessions, "one", &Arc::downgrade(&current)).await;
        assert!(server.sessions.lock().await.contains_key("one"));
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: this test owns only in-memory session entries.
    async fn full_table_evicts_oldest_unauthenticated_session_for_a_new_id() {
        let server = server();
        for index in 0..MAX_LIVE_SESSIONS {
            assert!(server.session_for(format!("session-{index}")).await.is_some());
        }

        // Unauthenticated peers must not be able to lock out a real client.
        assert!(server.session_for("newcomer".to_owned()).await.is_some());

        let sessions = server.sessions.lock().await;
        assert_eq!(sessions.len(), MAX_LIVE_SESSIONS);
        assert!(!sessions.contains_key("session-0"), "oldest unauthenticated session must be evicted");
        assert!(sessions.contains_key("session-1"));
        assert!(sessions.contains_key("newcomer"));
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: this test owns only in-memory session entries.
    async fn full_table_of_authenticated_sessions_rejects_new_ids() {
        let server = server();
        for index in 0..MAX_LIVE_SESSIONS {
            let session = server.session_for(format!("session-{index}")).await.expect("session");
            assert!(session.authenticate());
        }

        assert!(server.session_for("overflow".to_owned()).await.is_none());
        assert!(server.session_for("session-0".to_owned()).await.is_some());
        assert_eq!(server.sessions.lock().await.len(), MAX_LIVE_SESSIONS);
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: this test owns only in-memory session entries.
    async fn authentication_and_eviction_never_both_win() {
        let server = server();
        let mut first_two = Vec::new();
        for index in 0..MAX_LIVE_SESSIONS {
            let session = server.session_for(format!("session-{index}")).await.expect("session");
            if index < 2 {
                first_two.push(session);
            }
        }
        // The oldest session authenticates just before the table fills up.
        assert!(first_two[0].authenticate());

        assert!(server.session_for("newcomer".to_owned()).await.is_some());

        let sessions = server.sessions.lock().await;
        assert!(sessions.contains_key("session-0"), "an authenticated session must not be evicted");
        assert!(!sessions.contains_key("session-1"), "the oldest pending session is evicted instead");
        drop(sessions);
        assert!(!first_two[1].authenticate(), "an evicted session must not authenticate later");
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns its sockets and channels.
    async fn upstream_close_ends_the_get_stream_while_the_post_stays_open() {
        let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream");
        let target = upstream.local_addr().expect("upstream address").to_string();
        let server = server();
        let session = server.session_for("s".to_owned()).await.expect("session");
        let (inbound_tx, mut outbound_rx) = {
            let mut binding = session.binding.lock().await;
            binding.get_attached = true;
            binding.post_attached = true;
            (binding.inbound_tx.take().expect("POST sender"), binding.outbound_rx.take().expect("GET receiver"))
        };
        server.maybe_start_session("s".to_owned(), session).await;
        let header = ripdpi_vless::wire::encode_request(&[0; 16], &[], &target).expect("VLESS header");
        inbound_tx.send(Bytes::from(header)).await.expect("send header");

        // The target answers and closes, as an HTTP/1.0 server does.
        let (mut connection, _) = upstream.accept().await.expect("accept upstream");
        connection.write_all(b"response").await.expect("upstream reply");
        drop(connection);

        let mut received = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), async {
            while let Some(frame) = outbound_rx.recv().await {
                received.extend_from_slice(&frame.expect("GET frame"));
            }
        })
        .await
        .expect("the GET stream must end when the upstream closes");
        assert!(received.ends_with(b"response"));
        drop(inbound_tx);
    }

    /// Starts `serve` on a loopback port and returns its address.
    async fn serve_on_loopback() -> std::net::SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind origin");
        let address = listener.local_addr().expect("origin address");
        tokio::spawn(super::serve(listener, server()));
        address
    }

    /// True when the origin closes `stream` within `wait`.
    async fn closed_within(stream: &mut tokio::net::TcpStream, wait: Duration) -> bool {
        use tokio::io::AsyncReadExt;
        let mut sink = Vec::new();
        tokio::time::timeout(wait, stream.read_to_end(&mut sink)).await.is_ok()
    }

    #[tokio::test(flavor = "current_thread")]
    async fn stalled_request_head_is_closed_at_the_header_deadline() {
        let address = serve_on_loopback().await;
        let mut client = tokio::net::TcpStream::connect(address).await.expect("connect");
        client.write_all(b"GET /session HTTP/1.1\r\nHost: origin\r\n").await.expect("partial head");
        tokio::time::sleep(Duration::from_millis(50)).await;

        // Freeze the clock after the real I/O; only the origin's own deadline can end this.
        tokio::time::pause();
        assert!(closed_within(&mut client, Duration::from_secs(3600)).await, "a stalled request head must be closed");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn connections_above_the_cap_are_closed() {
        let address = serve_on_loopback().await;
        let mut idle = Vec::new();
        for _ in 0..super::MAX_CONNECTIONS {
            idle.push(tokio::net::TcpStream::connect(address).await.expect("connect idle client"));
        }
        let mut extra = tokio::net::TcpStream::connect(address).await.expect("connect extra client");
        assert!(closed_within(&mut extra, Duration::from_secs(5)).await, "a connection above the cap must be closed");
        assert!(!closed_within(&mut idle[0], Duration::from_millis(200)).await, "admitted connections stay open");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn stalled_post_body_is_closed_when_its_session_expires() {
        let address = serve_on_loopback().await;
        let mut client = tokio::net::TcpStream::connect(address).await.expect("connect");
        // A POST with no GET: the session expires, but the body never ends.
        client
            .write_all(b"POST /session HTTP/1.1\r\nHost: origin\r\nContent-Length: 1000\r\n\r\nabc")
            .await
            .expect("POST head");
        tokio::time::sleep(Duration::from_millis(50)).await;

        tokio::time::pause();
        assert!(
            closed_within(&mut client, Duration::from_secs(3600)).await,
            "a stalled POST must not pin its connection"
        );
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn unauthenticated_post_buffers_only_a_small_prefix() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind origin");
        let address = listener.local_addr().expect("origin address");
        let origin = server();
        tokio::spawn(super::serve(listener, origin.clone()));

        // A POST with no GET streams 1 MiB before any VLESS header is checked.
        let mut client = tokio::net::TcpStream::connect(address).await.expect("connect");
        let body = vec![0x55u8; 1024 * 1024];
        let head = format!("POST /preauth HTTP/1.1\r\nHost: origin\r\nContent-Length: {}\r\n\r\n", body.len());
        client.write_all(head.as_bytes()).await.expect("POST head");
        let writer = tokio::spawn(async move {
            let _ = client.write_all(&body).await;
            client
        });
        tokio::time::sleep(Duration::from_millis(500)).await;

        let session = origin.sessions.lock().await.get("preauth").cloned().expect("pre-auth session");
        let mut binding = session.binding.lock().await;
        let receiver = binding.inbound_rx.as_mut().expect("unstarted session keeps its receiver");
        let mut buffered = 0;
        while let Ok(chunk) = receiver.try_recv() {
            buffered += chunk.len();
        }
        assert!(buffered < 256 * 1024, "{buffered} body bytes were buffered before authentication");
        drop(binding);
        writer.abort();
    }
}
