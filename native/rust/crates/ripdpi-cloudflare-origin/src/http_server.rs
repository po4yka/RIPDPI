use std::collections::HashMap;
use std::convert::Infallible;
use std::io;
use std::sync::Arc;

use bytes::Bytes;
use http::{Method, Request, Response, StatusCode};
use http_body_util::{BodyExt, Empty};
use hyper::body::Incoming;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use tokio::net::TcpListener;
use tokio::sync::{Mutex, mpsc};

use crate::config::OriginConfig;
use crate::errors::{classify_error, emit_structured_error};
use crate::http_body::{ChannelBody, XhttpBody};
use crate::path::extract_session_id;
use crate::session::run_session;

const STRUCTURED_READY_PREFIX: &str = "RIPDPI-READY|cloudflare-origin|";

struct SessionState {
    inbound_tx: mpsc::Sender<Bytes>,
    outbound_tx: mpsc::Sender<io::Result<Bytes>>,
    binding: Mutex<SessionBindingState>,
}

struct SessionBindingState {
    get_attached: bool,
    post_attached: bool,
    started: bool,
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
        let session = self.session_for(session_id.clone()).await;
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
        let session = self.session_for(session_id.clone()).await;
        {
            let mut binding = session.binding.lock().await;
            if binding.post_attached {
                return empty_response(StatusCode::CONFLICT);
            }
            binding.post_attached = true;
        }

        let inbound_tx = session.inbound_tx.clone();
        tokio::spawn(async move {
            if let Err(error) = pump_request_body(body, inbound_tx).await {
                emit_structured_error(classify_error(&error), &error);
            }
        });

        self.maybe_start_session(session_id, session).await;
        empty_response(StatusCode::OK)
    }

    async fn session_for(&self, session_id: String) -> Arc<SessionState> {
        let mut sessions = self.sessions.lock().await;
        sessions.entry(session_id).or_insert_with(new_session).clone()
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

fn new_session() -> Arc<SessionState> {
    let (inbound_tx, inbound_rx) = mpsc::channel::<Bytes>(64);
    let (outbound_tx, outbound_rx) = mpsc::channel::<io::Result<Bytes>>(64);
    Arc::new(SessionState {
        inbound_tx,
        outbound_tx,
        binding: Mutex::new(SessionBindingState {
            get_attached: false,
            post_attached: false,
            started: false,
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
