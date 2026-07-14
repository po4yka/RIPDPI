//! xHTTP-over-Reality loopback fixture.
//!
//! A proxying server for the cross-stack chain tests
//! (`add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`). It
//! emulates the full VLESS-over-xHTTP-over-Reality server stack so the real
//! `ripdpi-xhttp` client (driven through the `ripdpi-relay-core` VLESS-Reality
//! xHTTP backend) can complete a tunnel end to end on loopback:
//!
//! 1. boring `SslAcceptor` Reality TLS handshake (self-signed cover cert, auth
//!    disabled — same rationale as [`crate::VlessRealityLoopback`]; a strict
//!    rustls server rejects the uTLS REALITY ClientHello with `DECODE_ERROR`);
//! 2. an HTTP/2 server (the xHTTP transport is H2 with prior knowledge);
//! 3. the xray-core **stream-up** wire shape — a `GET /<path>/<sid>` whose
//!    response body is the download channel, plus a `POST /<path>/<sid>` whose
//!    request body is the upload channel, correlated by the shared URL path;
//! 4. the VLESS handshake carried inside the upload body (request header) and
//!    download body (response header), after which bytes proxy to the request's
//!    target (the fixture's embedded echo).
//!
//! Only stream-up is implemented — that is what `XhttpProtocolMode::default()`
//! and the relay backend use. This is a dev/test fixture, never a production
//! xHTTP server. See `docs/architecture/protocol-loopback-harness-design.md`.

use std::collections::HashMap;
use std::convert::Infallible;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use boring::pkey::{PKey, Private};
use boring::ssl::{SslAcceptor, SslMethod, SslVerifyMode};
use boring::x509::X509;
use bytes::Bytes;
use http_body_util::combinators::BoxBody;
use http_body_util::{BodyExt, Empty};
use hyper::body::{Body, Frame, Incoming};
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rcgen::generate_simple_self_signed;
use ripdpi_vless::wire::{ParseRequestError, encode_response, parse_request_header};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle as TokioJoinHandle;

const SERVER_NAME: &str = "xhttp.fixture.test";
const MAX_REQUEST_HEADER: usize = 1024;
const DOWNLOAD_CHUNK: usize = 16 * 1024;

type SrvBody = BoxBody<Bytes, io::Error>;

/// One side of a not-yet-correlated stream-up pair, keyed by URL path.
enum SessionHalf {
    /// The GET arrived first; holds the download-body sender.
    Download(mpsc::Sender<Result<Bytes, io::Error>>),
    /// The POST arrived first; holds the upload request body.
    Upload(Incoming),
}

type Sessions = Arc<Mutex<HashMap<String, SessionHalf>>>;

/// A loopback xHTTP-over-Reality server that proxies to the VLESS request target.
// Drop order: shutdown sends before thread join; the Drop body fires the oneshot stop signal before joining the runtime thread, so the server loop observes shutdown and the join does not block.
pub struct XhttpRealityLoopback {
    address: SocketAddr,
    target_address: SocketAddr,
    server_name: &'static str,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl XhttpRealityLoopback {
    /// Start the fixture: an xHTTP/Reality listener plus an embedded TCP echo
    /// upstream (the VLESS request's final target).
    pub async fn start() -> io::Result<Self> {
        let acceptor = Arc::new(build_acceptor()?);
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        let thread = thread::spawn(move || {
            let runtime =
                tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build xhttp fixture runtime");
            runtime.block_on(async move {
                let xhttp_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind xhttp fixture");
                let echo_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind xhttp target echo");
                let xhttp_address = xhttp_listener.local_addr().expect("xhttp fixture local addr");
                let target_address = echo_listener.local_addr().expect("xhttp echo local addr");
                addr_tx.send((xhttp_address, target_address)).ok();
                let echo_task = tokio::spawn(serve_echo(echo_listener));
                tokio::select! {
                    _ = shutdown_rx => {}
                    _ = serve_xhttp(xhttp_listener, acceptor) => {}
                }
                echo_task.abort();
            });
        });

        let (address, target_address) =
            addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))?;
        Ok(Self {
            address,
            target_address,
            server_name: SERVER_NAME,
            shutdown: Some(shutdown_tx),
            thread: Some(thread),
        })
    }

    /// Port of the xHTTP/Reality listener (the backend's `server_port`).
    pub fn port(&self) -> u16 {
        self.address.port()
    }

    /// Port of the embedded TCP echo upstream (the VLESS request's final target).
    pub fn target_port(&self) -> u16 {
        self.target_address.port()
    }

    /// The SNI / `server_name` the backend config should carry (cover cert only).
    pub fn server_name(&self) -> &'static str {
        self.server_name
    }
}

impl Drop for XhttpRealityLoopback {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            shutdown.send(()).ok();
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
}

fn build_acceptor() -> io::Result<SslAcceptor> {
    let certificate = generate_simple_self_signed(vec![SERVER_NAME.to_owned(), "127.0.0.1".to_owned()])
        .map_err(|error| io::Error::other(error.to_string()))?;
    let cert = X509::from_der(certificate.cert.der().as_ref()).map_err(|error| io::Error::other(error.to_string()))?;
    let key: PKey<Private> = PKey::private_key_from_der(&certificate.signing_key.serialize_der())
        .map_err(|error| io::Error::other(error.to_string()))?;
    let mut acceptor =
        SslAcceptor::mozilla_intermediate(SslMethod::tls()).map_err(|error| io::Error::other(error.to_string()))?;
    acceptor.set_certificate(&cert).map_err(|error| io::Error::other(error.to_string()))?;
    acceptor.set_private_key(&key).map_err(|error| io::Error::other(error.to_string()))?;
    acceptor.set_verify(SslVerifyMode::NONE);
    Ok(acceptor.build())
}

async fn serve_echo(listener: TcpListener) {
    loop {
        let Ok((mut socket, _)) = listener.accept().await else {
            break;
        };
        tokio::spawn(async move {
            let (mut reader, mut writer) = socket.split();
            let _ = tokio::io::copy(&mut reader, &mut writer).await;
        });
    }
}

async fn serve_xhttp(listener: TcpListener, acceptor: Arc<SslAcceptor>) {
    loop {
        let Ok((socket, _)) = listener.accept().await else {
            break;
        };
        let _ = socket.set_nodelay(true);
        let acceptor = Arc::clone(&acceptor);
        tokio::spawn(async move {
            let Ok(tls) = tokio_boring::accept(&acceptor, socket).await else {
                return;
            };
            // Each H2 connection gets its own session map; a stream-up pair's GET
            // and POST always arrive on the same connection.
            let sessions: Sessions = Arc::new(Mutex::new(HashMap::new()));
            let service = service_fn(move |request| handle_request(request, Arc::clone(&sessions)));
            let builder = hyper::server::conn::http2::Builder::new(TokioExecutor::new());
            let _ = builder.serve_connection(TokioIo::new(tls), service).await;
        });
    }
}

async fn handle_request(request: Request<Incoming>, sessions: Sessions) -> Result<Response<SrvBody>, Infallible> {
    let path = request.uri().path().to_owned();
    match *request.method() {
        // GET: this is the download channel. Create the body sender; if the POST
        // upload already arrived, pair them and start proxying.
        Method::GET => {
            let (down_tx, down_rx) = mpsc::channel::<Result<Bytes, io::Error>>(64);
            let paired_upload = {
                let mut guard = sessions.lock().expect("xhttp sessions");
                match guard.remove(&path) {
                    Some(SessionHalf::Upload(upload)) => Some(upload),
                    Some(other) => {
                        // Duplicate GET for the path: keep the original, reject this.
                        guard.insert(path.clone(), other);
                        return Ok(status_response(StatusCode::CONFLICT));
                    }
                    None => {
                        guard.insert(path.clone(), SessionHalf::Download(down_tx.clone()));
                        None
                    }
                }
            };
            if let Some(upload) = paired_upload {
                spawn_proxy(upload, down_tx);
            }
            Ok(ok_streaming_response(down_rx))
        }
        // POST: this is the upload channel. If the GET download already arrived,
        // pair them and start proxying; otherwise stash the upload body.
        Method::POST => {
            let upload = request.into_body();
            let paired_download = {
                let mut guard = sessions.lock().expect("xhttp sessions");
                match guard.remove(&path) {
                    Some(SessionHalf::Download(down_tx)) => Some(down_tx),
                    Some(other) => {
                        guard.insert(path.clone(), other);
                        return Ok(status_response(StatusCode::CONFLICT));
                    }
                    None => {
                        guard.insert(path.clone(), SessionHalf::Upload(upload));
                        return Ok(status_response(StatusCode::OK));
                    }
                }
            };
            if let Some(down_tx) = paired_download {
                spawn_proxy(upload, down_tx);
            }
            Ok(status_response(StatusCode::OK))
        }
        _ => Ok(status_response(StatusCode::METHOD_NOT_ALLOWED)),
    }
}

/// Pair an upload body with a download sender: read the VLESS request from the
/// upload, ACK with the VLESS response on the download, then proxy both
/// directions to the request's target.
fn spawn_proxy(upload: Incoming, down_tx: mpsc::Sender<Result<Bytes, io::Error>>) -> TokioJoinHandle<()> {
    tokio::spawn(async move {
        let _ = proxy(upload, down_tx).await;
    })
}

async fn proxy(mut upload: Incoming, down_tx: mpsc::Sender<Result<Bytes, io::Error>>) -> io::Result<()> {
    // 1. Read the VLESS request header from the upload body.
    let mut buf = Vec::with_capacity(64);
    let header = loop {
        match parse_request_header(&buf) {
            Ok(header) => break header,
            Err(ParseRequestError::NeedMoreData) => {}
            Err(error) => return Err(io::Error::new(io::ErrorKind::InvalidData, error)),
        }
        if buf.len() >= MAX_REQUEST_HEADER {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "VLESS request header exceeded bound"));
        }
        match upload.frame().await {
            Some(Ok(frame)) => {
                if let Ok(data) = frame.into_data() {
                    buf.extend_from_slice(&data);
                }
            }
            Some(Err(error)) => return Err(io::Error::other(format!("xHTTP upload body error: {error}"))),
            None => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF before full VLESS request header")),
        }
    };

    // XTLS Vision (and every other VLESS addon) is invalid over an HTTP/2 xHTTP
    // tunnel; xray-core rejects a non-empty flow on a non-raw transport. The
    // request header's addons-length byte (index 17, after version + 16-byte
    // UUID) must therefore be zero. Fail loudly if a regression ships Vision
    // addons over xHTTP so the cross-stack tests catch it on the wire.
    if buf.get(17).copied().unwrap_or(0) != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("xHTTP VLESS request carried {} addon byte(s); flow must be empty over HTTP/2", buf[17]),
        ));
    }

    // 2. Connect to the proxy target, then ACK with the VLESS response header.
    let upstream = TcpStream::connect(header.target.as_str()).await?;
    down_tx
        .send(Ok(Bytes::from(encode_response(&[])?)))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "download channel closed"))?;

    let (mut upstream_read, mut upstream_write) = upstream.into_split();

    // Forward any bytes already buffered past the VLESS header.
    let leftover = buf.split_off(header.consumed_len);
    if !leftover.is_empty() {
        upstream_write.write_all(&leftover).await?;
    }

    // 3a. Upload pump: xHTTP upload body -> upstream.
    let upload_pump = tokio::spawn(async move {
        while let Some(frame) = upload.frame().await {
            let Ok(frame) = frame else { break };
            if let Ok(data) = frame.into_data()
                && upstream_write.write_all(&data).await.is_err()
            {
                break;
            }
        }
        let _ = upstream_write.shutdown().await;
    });

    // 3b. Download pump: upstream -> xHTTP download body.
    let mut chunk = vec![0u8; DOWNLOAD_CHUNK];
    loop {
        let read = match upstream_read.read(&mut chunk).await {
            Ok(0) | Err(_) => break,
            Ok(read) => read,
        };
        if down_tx.send(Ok(Bytes::copy_from_slice(&chunk[..read]))).await.is_err() {
            break;
        }
    }
    upload_pump.abort();
    Ok(())
}

fn ok_streaming_response(down_rx: mpsc::Receiver<Result<Bytes, io::Error>>) -> Response<SrvBody> {
    Response::builder()
        .status(StatusCode::OK)
        .body(ChannelBody { receiver: down_rx }.boxed())
        .expect("valid xhttp streaming response")
}

fn status_response(status: StatusCode) -> Response<SrvBody> {
    Response::builder()
        .status(status)
        .body(Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
        .expect("valid xhttp status response")
}

/// mpsc-backed HTTP body for the streaming download channel (mirrors the client
/// `ChannelBody` in `ripdpi-xhttp`).
struct ChannelBody {
    receiver: mpsc::Receiver<Result<Bytes, io::Error>>,
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
