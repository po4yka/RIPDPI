use std::convert::Infallible;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use bytes::Bytes;
use http_body_util::Empty;
use hyper::body::Incoming;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rcgen::{CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ClientConfig, RootCertStore, ServerConfig};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio_rustls::TlsAcceptor;

const MAX_PADDED_FRAMES: usize = 8;
const MAX_PAYLOAD_SIZE: usize = u16::MAX as usize;
const SERVER_NAME: &str = "naive.fixture.test";

#[derive(Debug, Default, Clone)]
pub struct NaiveH2PaddingObserved {
    pub proxy_authorization: Option<String>,
    pub padding_type_request: Option<String>,
    pub request_padding_len: Option<usize>,
    pub decoded_payload_bytes: usize,
}

// Drop order: shutdown sends before thread join; fixture tasks need the shutdown signal before their runtime thread is joined.
pub struct NaiveH2PaddingFixture {
    address: SocketAddr,
    client_tls_config: Arc<ClientConfig>,
    observed: Arc<Mutex<NaiveH2PaddingObserved>>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl NaiveH2PaddingFixture {
    pub async fn start(username: &str, password: &str) -> io::Result<Self> {
        Self::start_with_options(username, password, false).await
    }

    pub async fn start_closing_first_tunnel_after_payload(username: &str, password: &str) -> io::Result<Self> {
        Self::start_with_options(username, password, true).await
    }

    async fn start_with_options(
        username: &str,
        password: &str,
        close_first_tunnel_after_payload: bool,
    ) -> io::Result<Self> {
        let tls = tls_configs()?;
        let expected_auth = format!("Basic {}", base64_encode(format!("{username}:{password}").as_bytes()));
        let observed = Arc::new(Mutex::new(NaiveH2PaddingObserved::default()));
        let close_first_tunnel_after_payload = Arc::new(AtomicBool::new(close_first_tunnel_after_payload));
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let server_config = Arc::clone(&tls.server);
        let observed_for_thread = Arc::clone(&observed);
        let close_first_tunnel_for_thread = Arc::clone(&close_first_tunnel_after_payload);

        let thread = thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("build naive h2 fixture runtime");
            runtime.block_on(async move {
                let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind naive h2 fixture");
                addr_tx.send(listener.local_addr().expect("fixture local addr")).ok();
                serve(
                    listener,
                    server_config,
                    expected_auth,
                    observed_for_thread,
                    close_first_tunnel_for_thread,
                    shutdown_rx,
                )
                .await;
            });
        });

        let address = addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))?;
        Ok(Self { address, client_tls_config: tls.client, observed, shutdown: Some(shutdown_tx), thread: Some(thread) })
    }

    pub fn port(&self) -> u16 {
        self.address.port()
    }

    pub fn server_name(&self) -> &'static str {
        SERVER_NAME
    }

    pub fn client_tls_config(&self) -> Arc<ClientConfig> {
        Arc::clone(&self.client_tls_config)
    }

    pub fn observed(&self) -> NaiveH2PaddingObserved {
        self.observed.lock().expect("fixture observations").clone()
    }
}

impl Drop for NaiveH2PaddingFixture {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            shutdown.send(()).ok();
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
}

struct TlsConfigs {
    server: Arc<ServerConfig>,
    client: Arc<ClientConfig>,
}

fn tls_configs() -> io::Result<TlsConfigs> {
    let key_pair = KeyPair::generate().map_err(to_io)?;
    let mut params = CertificateParams::new(vec![SERVER_NAME.to_owned(), "127.0.0.1".to_owned()]).map_err(to_io)?;
    params.is_ca = IsCa::NoCa;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, SERVER_NAME);
    params.distinguished_name = dn;
    let cert = params.self_signed(&key_pair).map_err(to_io)?;
    let cert_der = cert.der().clone();
    let key_der = key_pair.serialize_der();

    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let mut server = ServerConfig::builder_with_provider(provider.clone().into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![cert_der.clone()], PrivateKeyDer::from(PrivatePkcs8KeyDer::from(key_der)))
        .map_err(to_io)?;
    server.alpn_protocols = vec![b"h2".to_vec()];

    let mut roots = RootCertStore::empty();
    roots.add(cert_der).map_err(to_io)?;
    let mut client = ClientConfig::builder_with_provider(provider.into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc provider supports default TLS versions")
        .with_root_certificates(roots)
        .with_no_client_auth();
    client.alpn_protocols = vec![b"h2".to_vec()];

    Ok(TlsConfigs { server: Arc::new(server), client: Arc::new(client) })
}

async fn serve(
    listener: TcpListener,
    server_config: Arc<ServerConfig>,
    expected_auth: String,
    observed: Arc<Mutex<NaiveH2PaddingObserved>>,
    close_first_tunnel_after_payload: Arc<AtomicBool>,
    mut shutdown: oneshot::Receiver<()>,
) {
    let acceptor = TlsAcceptor::from(server_config);
    loop {
        tokio::select! {
            _ = &mut shutdown => break,
            accepted = listener.accept() => {
                let Ok((socket, _)) = accepted else {
                    break;
                };
                let acceptor = acceptor.clone();
                let expected_auth = expected_auth.clone();
                let observed = Arc::clone(&observed);
                let close_first_tunnel_after_payload = Arc::clone(&close_first_tunnel_after_payload);
                tokio::spawn(async move {
                    let Ok(tls) = acceptor.accept(socket).await else {
                        return;
                    };
                    let service = service_fn(move |request| {
                        handle_request(
                            request,
                            expected_auth.clone(),
                            Arc::clone(&observed),
                            Arc::clone(&close_first_tunnel_after_payload),
                        )
                    });
                    let mut builder = hyper::server::conn::http2::Builder::new(TokioExecutor::new());
                    builder.enable_connect_protocol();
                    let _ = builder.serve_connection(TokioIo::new(tls), service).await;
                });
            }
        }
    }
}

async fn handle_request(
    request: Request<Incoming>,
    expected_auth: String,
    observed: Arc<Mutex<NaiveH2PaddingObserved>>,
    close_first_tunnel_after_payload: Arc<AtomicBool>,
) -> Result<Response<Empty<Bytes>>, Infallible> {
    if request.method() != Method::CONNECT {
        return Ok(response(StatusCode::METHOD_NOT_ALLOWED));
    }

    let proxy_authorization =
        request.headers().get("proxy-authorization").and_then(|value| value.to_str().ok()).map(ToOwned::to_owned);
    let padding_type_request =
        request.headers().get("padding-type-request").and_then(|value| value.to_str().ok()).map(ToOwned::to_owned);
    let request_padding_len = request.headers().get("padding").map(|value| value.as_bytes().len());
    {
        let mut guard = observed.lock().expect("fixture observations");
        guard.proxy_authorization = proxy_authorization.clone();
        guard.padding_type_request = padding_type_request;
        guard.request_padding_len = request_padding_len;
    }

    if proxy_authorization.as_deref() != Some(expected_auth.as_str()) {
        return Ok(response(StatusCode::PROXY_AUTHENTICATION_REQUIRED));
    }
    if !request_padding_len.is_some_and(|len| (16..=32).contains(&len)) {
        return Ok(response(StatusCode::BAD_REQUEST));
    }

    let upgraded = hyper::upgrade::on(request);
    tokio::spawn(async move {
        if let Ok(upgraded) = upgraded.await {
            echo_padded(TokioIo::new(upgraded), observed, close_first_tunnel_after_payload).await;
        }
    });

    Ok(Response::builder()
        .status(StatusCode::OK)
        .header("padding", "~".repeat(30))
        .header("padding-type-reply", "1")
        .body(Empty::<Bytes>::new())
        .expect("valid fixture response"))
}

fn response(status: StatusCode) -> Response<Empty<Bytes>> {
    Response::builder().status(status).body(Empty::<Bytes>::new()).expect("valid fixture response")
}

async fn echo_padded(
    mut io: TokioIo<hyper::upgrade::Upgraded>,
    observed: Arc<Mutex<NaiveH2PaddingObserved>>,
    close_first_tunnel_after_payload: Arc<AtomicBool>,
) {
    let mut decoder = FixturePaddingDecoder::default();
    let mut encoder = FixturePaddingEncoder::default();
    let mut buffer = [0u8; 4096];
    while let Ok(read) = io.read(&mut buffer).await {
        if read == 0 {
            break;
        }
        let mut decoded = Vec::new();
        decoder.decode(&buffer[..read], &mut decoded);
        if decoded.is_empty() {
            continue;
        }
        observed.lock().expect("fixture observations").decoded_payload_bytes += decoded.len();
        if close_first_tunnel_after_payload.swap(false, Ordering::AcqRel) {
            break;
        }
        let mut encoded = Vec::new();
        let mut remaining = decoded.as_slice();
        while !remaining.is_empty() {
            let consumed = encoder.encode(remaining, &mut encoded);
            remaining = &remaining[consumed..];
        }
        if io.write_all(&encoded).await.is_err() {
            break;
        }
        if io.flush().await.is_err() {
            break;
        }
    }
}

#[derive(Default)]
struct FixturePaddingEncoder {
    written_frames: usize,
}

impl FixturePaddingEncoder {
    fn encode(&mut self, payload: &[u8], out: &mut Vec<u8>) -> usize {
        if self.written_frames >= MAX_PADDED_FRAMES {
            out.extend_from_slice(payload);
            return payload.len();
        }
        let consumed = payload.len().min(MAX_PAYLOAD_SIZE);
        let len = u16::try_from(consumed).expect("fixture payload length is capped");
        out.extend_from_slice(&len.to_be_bytes());
        out.push(0);
        out.extend_from_slice(&payload[..consumed]);
        self.written_frames += 1;
        consumed
    }
}

#[derive(Default)]
struct FixturePaddingDecoder {
    state: DecodeState,
    read_frames: usize,
    payload_len: usize,
    padding_len: usize,
}

impl FixturePaddingDecoder {
    fn decode(&mut self, mut input: &[u8], out: &mut Vec<u8>) {
        while !input.is_empty() {
            if self.read_frames >= MAX_PADDED_FRAMES && matches!(self.state, DecodeState::PayloadLength1) {
                out.extend_from_slice(input);
                break;
            }
            match self.state {
                DecodeState::PayloadLength1 => {
                    self.payload_len = usize::from(input[0]) << 8;
                    input = &input[1..];
                    self.state = DecodeState::PayloadLength2;
                }
                DecodeState::PayloadLength2 => {
                    self.payload_len += usize::from(input[0]);
                    input = &input[1..];
                    self.state = DecodeState::PaddingLength;
                }
                DecodeState::PaddingLength => {
                    self.padding_len = usize::from(input[0]);
                    input = &input[1..];
                    self.state = DecodeState::Payload;
                }
                DecodeState::Payload => {
                    let copy_len = self.payload_len.min(input.len());
                    out.extend_from_slice(&input[..copy_len]);
                    self.payload_len -= copy_len;
                    input = &input[copy_len..];
                    if self.payload_len == 0 {
                        self.state = DecodeState::Padding;
                    }
                }
                DecodeState::Padding => {
                    let skip_len = self.padding_len.min(input.len());
                    self.padding_len -= skip_len;
                    input = &input[skip_len..];
                    if self.padding_len == 0 {
                        self.read_frames += 1;
                        self.state = DecodeState::PayloadLength1;
                    }
                }
            }
        }
    }
}

#[derive(Default)]
enum DecodeState {
    #[default]
    PayloadLength1,
    PayloadLength2,
    PaddingLength,
    Payload,
    Padding,
}

fn base64_encode(value: &[u8]) -> String {
    use base64::prelude::{Engine as _, BASE64_STANDARD};
    BASE64_STANDARD.encode(value)
}

fn to_io(error: impl std::error::Error + Send + Sync + 'static) -> io::Error {
    io::Error::other(error)
}
