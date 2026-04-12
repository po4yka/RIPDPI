#![forbid(unsafe_code)]

use std::collections::HashMap;
use std::convert::Infallible;
use std::io;
use std::sync::Arc;

use bytes::Bytes;
use http::{Method, Request, Response, StatusCode};
use http_body_util::{BodyExt, Empty};
use hyper::body::{Body, Frame, Incoming};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Mutex};

const VERSION_FLAG: &str = "--version";
const STRUCTURED_READY_PREFIX: &str = "RIPDPI-READY|cloudflare-origin|";
const STRUCTURED_ERROR_PREFIX: &str = "RIPDPI-ERROR|cloudflare-origin|";
const STREAM_BUFFER_SIZE: usize = 16 * 1024;

type XhttpBody = http_body_util::combinators::BoxBody<Bytes, io::Error>;

#[derive(Clone)]
struct OriginConfig {
    listen: String,
    path: String,
    uuid: [u8; 16],
}

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

struct ChannelBody {
    receiver: mpsc::Receiver<io::Result<Bytes>>,
}

impl ChannelBody {
    fn new(receiver: mpsc::Receiver<io::Result<Bytes>>) -> Self {
        Self { receiver }
    }
}

impl Body for ChannelBody {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        match self.receiver.poll_recv(cx) {
            std::task::Poll::Ready(Some(Ok(bytes))) => std::task::Poll::Ready(Some(Ok(Frame::data(bytes)))),
            std::task::Poll::Ready(Some(Err(error))) => std::task::Poll::Ready(Some(Err(error))),
            std::task::Poll::Ready(None) => std::task::Poll::Ready(None),
            std::task::Poll::Pending => std::task::Poll::Pending,
        }
    }
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> io::Result<()> {
    if std::env::args().skip(1).any(|value| value == VERSION_FLAG) {
        println!("ripdpi-cloudflare-origin {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }

    match parse_config() {
        Ok(config) => run(config).await,
        Err(error) => {
            emit_structured_error(classify_error(&error), &error);
            Err(error)
        }
    }
}

fn parse_config() -> io::Result<OriginConfig> {
    let args = parse_args();
    let listen = args.get("listen").cloned().unwrap_or_else(|| "127.0.0.1:43128".to_string());
    let path = normalize_path(args.get("path").map(String::as_str).unwrap_or("/"));
    let uuid_raw = args
        .get("uuid")
        .map(String::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --uuid"))?;
    Ok(OriginConfig { listen, path, uuid: parse_uuid(uuid_raw)? })
}

fn parse_args() -> HashMap<String, String> {
    let mut parsed = HashMap::new();
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        if !flag.starts_with("--") {
            continue;
        }
        let value = args.next().unwrap_or_default();
        parsed.insert(flag.trim_start_matches("--").to_owned(), value);
    }
    parsed
}

fn parse_uuid(raw: &str) -> io::Result<[u8; 16]> {
    let normalized: String = raw.chars().filter(|character| *character != '-').collect();
    let bytes = hex::decode(&normalized)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid UUID {raw}: {error}")))?;
    if bytes.len() != 16 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("invalid UUID {raw}: expected 16 bytes")));
    }
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}

async fn run(config: OriginConfig) -> io::Result<()> {
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
            return Ok(response(StatusCode::NOT_FOUND, Empty::<Bytes>::new().map_err(|never| match never {}).boxed()));
        };
        match *request.method() {
            Method::GET => Ok(self.handle_get(session_id).await),
            Method::POST => Ok(self.handle_post(session_id, request.into_body()).await),
            _ => Ok(response(
                StatusCode::METHOD_NOT_ALLOWED,
                Empty::<Bytes>::new().map_err(|never| match never {}).boxed(),
            )),
        }
    }

    async fn handle_get(&self, session_id: String) -> Response<XhttpBody> {
        let session = self.session_for(session_id.clone()).await;
        let outbound_rx = {
            let mut binding = session.binding.lock().await;
            if binding.get_attached {
                return response(StatusCode::CONFLICT, Empty::<Bytes>::new().map_err(|never| match never {}).boxed());
            }
            binding.get_attached = true;
            binding.outbound_rx.take()
        };
        let Some(outbound_rx) = outbound_rx else {
            return response(StatusCode::CONFLICT, Empty::<Bytes>::new().map_err(|never| match never {}).boxed());
        };
        self.maybe_start_session(session_id, session).await;
        response(StatusCode::OK, ChannelBody::new(outbound_rx).boxed())
    }

    async fn handle_post(&self, session_id: String, body: Incoming) -> Response<XhttpBody> {
        let session = self.session_for(session_id.clone()).await;
        {
            let mut binding = session.binding.lock().await;
            if binding.post_attached {
                return response(StatusCode::CONFLICT, Empty::<Bytes>::new().map_err(|never| match never {}).boxed());
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
        response(StatusCode::OK, Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
    }

    async fn session_for(&self, session_id: String) -> Arc<SessionState> {
        let mut sessions = self.sessions.lock().await;
        sessions
            .entry(session_id)
            .or_insert_with(|| {
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
            })
            .clone()
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
        let sessions = Arc::clone(&self.sessions);
        let outbound_tx = session.outbound_tx.clone();
        tokio::spawn(async move {
            let result = run_session(inbound_rx, outbound_tx, expected_uuid).await;
            if let Err(error) = result {
                emit_structured_error(classify_error(&error), &error);
            }
            sessions.lock().await.remove(&session_id);
        });
    }
}

async fn pump_request_body(mut body: Incoming, inbound_tx: mpsc::Sender<Bytes>) -> io::Result<()> {
    while let Some(frame) = body.frame().await {
        let frame = frame.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("POST body read failed: {error}"))
        })?;
        if let Ok(data) = frame.into_data() {
            if inbound_tx.send(data).await.is_err() {
                break;
            }
        }
    }
    Ok(())
}

async fn run_session(
    mut inbound_rx: mpsc::Receiver<Bytes>,
    outbound_tx: mpsc::Sender<io::Result<Bytes>>,
    expected_uuid: [u8; 16],
) -> io::Result<()> {
    let (decoded, buffered_payload) = read_request_header(&mut inbound_rx).await?;
    if decoded.uuid != expected_uuid {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "VLESS UUID does not match configured tunnel identity",
        ));
    }

    let upstream = TcpStream::connect(decoded.target.as_str())
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("connect {}: {error}", decoded.target)))?;
    upstream.set_nodelay(true)?;
    if outbound_tx.send(Ok(Bytes::from(ripdpi_vless::wire::encode_response(&[])))).await.is_err() {
        return Ok(());
    }

    let (mut upstream_reader, mut upstream_writer) = upstream.into_split();
    let upload = tokio::spawn(async move {
        if !buffered_payload.is_empty() {
            upstream_writer.write_all(&buffered_payload).await?;
        }
        while let Some(chunk) = inbound_rx.recv().await {
            upstream_writer.write_all(&chunk).await?;
        }
        upstream_writer.shutdown().await
    });
    let download = tokio::spawn(async move {
        let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
        loop {
            let read = upstream_reader.read(&mut buffer).await?;
            if read == 0 {
                break;
            }
            if outbound_tx.send(Ok(Bytes::copy_from_slice(&buffer[..read]))).await.is_err() {
                break;
            }
        }
        Ok::<(), io::Error>(())
    });

    upload.await.map_err(join_error_to_io)??;
    download.await.map_err(join_error_to_io)??;
    Ok(())
}

async fn read_request_header(
    inbound_rx: &mut mpsc::Receiver<Bytes>,
) -> io::Result<(ripdpi_vless::wire::DecodedRequestHeader, Vec<u8>)> {
    let mut buffered = Vec::new();
    loop {
        match ripdpi_vless::wire::parse_request_header(&buffered) {
            Ok(decoded) => {
                let remaining = buffered.split_off(decoded.consumed_len);
                return Ok((decoded, remaining));
            }

            Err(ripdpi_vless::wire::ParseRequestError::NeedMoreData) => {
                let Some(chunk) = inbound_rx.recv().await else {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "xHTTP POST stream ended before the VLESS request header completed",
                    ));
                };
                buffered.extend_from_slice(&chunk);
            }

            Err(ripdpi_vless::wire::ParseRequestError::Invalid(message)) => {
                return Err(io::Error::new(io::ErrorKind::InvalidData, message));
            }
        }
    }
}

fn normalize_path(path: &str) -> String {
    let trimmed = path.trim().trim_matches('/');
    if trimmed.is_empty() {
        "/".to_owned()
    } else {
        format!("/{trimmed}")
    }
}

fn extract_session_id(base_path: &str, request_path: &str) -> Option<String> {
    let normalized_base = normalize_path(base_path);
    if normalized_base == "/" {
        let session_id = request_path.trim_matches('/');
        let segments = session_id.split('/').collect::<Vec<_>>();
        return if segments.len() == 1 && !segments[0].is_empty() { Some(segments[0].to_owned()) } else { None };
    }
    let prefix = format!("{normalized_base}/");
    request_path.strip_prefix(&prefix).filter(|value| !value.is_empty() && !value.contains('/')).map(str::to_owned)
}

fn response(status: StatusCode, body: XhttpBody) -> Response<XhttpBody> {
    Response::builder().status(status).body(body).expect("response build")
}

fn join_error_to_io(error: tokio::task::JoinError) -> io::Error {
    io::Error::other(format!("cloudflare origin task join failed: {error}"))
}

fn classify_error(error: &io::Error) -> &'static str {
    let message = error.to_string().to_ascii_lowercase();
    if error.kind() == io::ErrorKind::PermissionDenied || message.contains("uuid") {
        "auth"
    } else if error.kind() == io::ErrorKind::NotFound || message.contains("resolve") || message.contains("dns") {
        "dns"
    } else if message.contains("vless") || message.contains("xhttp") {
        "handshake"
    } else {
        "tcp"
    }
}

fn emit_structured_error(failure_class: &str, error: &io::Error) {
    eprintln!(
        "{STRUCTURED_ERROR_PREFIX}{failure_class}|{}",
        error.to_string().replace('|', "/").replace(['\n', '\r'], " "),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_session_id_matches_root_path() {
        assert_eq!(Some("session123".to_string()), extract_session_id("/", "/session123"));
        assert_eq!(None, extract_session_id("/", "/session123/extra"));
    }

    #[test]
    fn extract_session_id_matches_nested_base_path() {
        assert_eq!(Some("session123".to_string()), extract_session_id("/api/v1/stream", "/api/v1/stream/session123"),);
        assert_eq!(None, extract_session_id("/api/v1/stream", "/api/v1/stream"));
    }

    #[test]
    fn parse_uuid_accepts_dashed_and_compact_forms() {
        assert_eq!(
            parse_uuid("550e8400-e29b-41d4-a716-446655440000").expect("dashed UUID"),
            parse_uuid("550e8400e29b41d4a716446655440000").expect("compact UUID"),
        );
    }
}
