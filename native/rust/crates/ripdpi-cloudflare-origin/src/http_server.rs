use std::collections::HashMap;
use std::convert::Infallible;
use std::io;
use std::sync::{Arc, Weak};
use std::time::Duration;

use bytes::Bytes;
use http::{Method, Request, Response, StatusCode};
use http_body_util::{BodyExt, Empty};
use hyper::body::Incoming;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use tokio::net::TcpListener;
use tokio::sync::{Mutex, mpsc};
use tokio::time::sleep;

use crate::config::OriginConfig;
use crate::errors::{classify_error, emit_structured_error};
use crate::http_body::{ChannelBody, XhttpBody};
use crate::path::extract_session_id;
use crate::session::run_session;

const STRUCTURED_READY_PREFIX: &str = "RIPDPI-READY|cloudflare-origin|";
const SESSION_ATTACH_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_LIVE_SESSIONS: usize = 1_024;

struct SessionState {
    outbound_tx: mpsc::Sender<io::Result<Bytes>>,
    binding: Mutex<SessionBindingState>,
}

struct SessionBindingState {
    get_attached: bool,
    post_attached: bool,
    started: bool,
    inbound_tx: Option<mpsc::Sender<Bytes>>,
    inbound_rx: Option<mpsc::Receiver<Bytes>>,
    outbound_rx: Option<mpsc::Receiver<io::Result<Bytes>>>,
}

#[derive(Clone)]
struct OriginServer {
    config: Arc<OriginConfig>,
    sessions: Arc<Mutex<HashMap<String, Arc<SessionState>>>>,
}

pub(crate) async fn run(config: OriginConfig) -> io::Result<()> {
    let listener = TcpListener::bind(&config.listen).await?;
    let listener_address = listener.local_addr()?.to_string();
    println!("{STRUCTURED_READY_PREFIX}{}|{listener_address}", env!("CARGO_PKG_VERSION"));

    let server = OriginServer { config: Arc::new(config), sessions: Arc::new(Mutex::new(HashMap::new())) };

    loop {
        let (stream, _) = listener.accept().await?;
        let server = server.clone();
        tokio::spawn(async move {
            let io = TokioIo::new(stream);
            let service = service_fn(move |request| server.clone().handle_request(request));
            if let Err(error) = http1::Builder::new().serve_connection(io, service).await {
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

        tokio::spawn(async move {
            if let Err(error) = pump_request_body(body, inbound_tx).await {
                emit_structured_error(classify_error(&error), &error);
            }
        });

        self.maybe_start_session(session_id, session).await;
        empty_response(StatusCode::OK)
    }

    // cancel-safe: cancellation during the scan leaves the map unchanged; insertion
    // and timer registration complete without further awaits.
    async fn session_for(&self, session_id: String) -> Option<Arc<SessionState>> {
        let mut sessions = self.sessions.lock().await;
        if let Some(session) = sessions.get(&session_id) {
            return Some(Arc::clone(session));
        }
        if sessions.len() >= MAX_LIVE_SESSIONS {
            return None;
        }
        let session = new_session();
        sessions.insert(session_id.clone(), Arc::clone(&session));
        let sessions = Arc::clone(&self.sessions);
        let weak = Arc::downgrade(&session);
        tokio::spawn(async move {
            sleep(SESSION_ATTACH_TIMEOUT).await;
            expire_unstarted_session(&sessions, &session_id, &weak).await;
        });
        Some(session)
    }

    async fn maybe_start_session(&self, session_id: String, session: Arc<SessionState>) {
        let inbound_rx = {
            let mut binding = session.binding.lock().await;
            if !(binding.get_attached && binding.post_attached) || binding.started {
                return;
            }
            binding.started = true;
            binding.inbound_rx.take()
        };
        let Some(inbound_rx) = inbound_rx else {
            return;
        };
        let expected_uuid = self.config.uuid;
        let protect_path = self.config.protect_path.clone();
        let sessions = Arc::clone(&self.sessions);
        let outbound_tx = session.outbound_tx.clone();
        tokio::spawn(async move {
            let result = run_session(inbound_rx, outbound_tx, expected_uuid, protect_path.as_deref()).await;
            if let Err(error) = result {
                emit_structured_error(classify_error(&error), &error);
            }
            sessions.lock().await.remove(&session_id);
        });
    }
}

// cancel-safe: the map entry stays unchanged until both locks are held.
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

fn new_session() -> Arc<SessionState> {
    let (inbound_tx, inbound_rx) = mpsc::channel::<Bytes>(64);
    let (outbound_tx, outbound_rx) = mpsc::channel::<io::Result<Bytes>>(64);
    Arc::new(SessionState {
        outbound_tx,
        binding: Mutex::new(SessionBindingState {
            get_attached: false,
            post_attached: false,
            started: false,
            inbound_tx: Some(inbound_tx),
            inbound_rx: Some(inbound_rx),
            outbound_rx: Some(outbound_rx),
        }),
    })
}

async fn pump_request_body(mut body: Incoming, inbound_tx: mpsc::Sender<Bytes>) -> io::Result<()> {
    while let Some(frame) = body.frame().await {
        let frame = frame.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("POST body read failed: {error}"))
        })?;
        if let Ok(data) = frame.into_data()
            && inbound_tx.send(data).await.is_err()
        {
            break;
        }
    }
    Ok(())
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
            sessions: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    #[tokio::test]
    // cancel-safe: the test owns its socket and channel resources.
    async fn post_sender_closure_reaches_session_while_state_is_retained() {
        let session = new_session();
        let mut binding = session.binding.lock().await;
        let sender = binding.inbound_tx.take().expect("POST sender");
        let mut receiver = binding.inbound_rx.take().expect("session receiver");
        drop(binding);

        drop(sender);
        assert!(receiver.recv().await.is_none());
    }
    #[tokio::test]
    // cancel-safe: this test owns only in-memory session entries.
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
    // cancel-safe: this test owns only in-memory session entries.
    async fn live_limit_rejects_new_ids_even_when_existing_sessions_started() {
        let server = server();
        for index in 0..MAX_LIVE_SESSIONS {
            assert!(server.session_for(format!("session-{index}")).await.is_some());
        }
        assert!(server.session_for("overflow".to_owned()).await.is_none());

        let active = server.sessions.lock().await.get("session-0").cloned().expect("first session");
        active.binding.lock().await.started = true;
        assert!(server.session_for("new-pending".to_owned()).await.is_none());
        assert!(server.session_for("session-0".to_owned()).await.is_some());
    }
}
