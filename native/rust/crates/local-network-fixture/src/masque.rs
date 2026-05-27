use std::convert::Infallible;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use bytes::Bytes;
use http_body_util::Empty;
use hyper::body::Incoming;
use hyper::ext::Protocol;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rcgen::{CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::oneshot;
use tokio_rustls::TlsAcceptor;

use crate::util::percent_decode;

const CONNECT_UDP_PROTOCOL: &str = "connect-udp";
const CAPSULE_PROTOCOL: &str = "?1";
const DATAGRAM_CAPSULE_TYPE: u64 = 0x00;
const MASQUE_PATH_PREFIX: &str = "/.well-known/masque/udp/";
const SERVER_NAME: &str = "localhost";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MasqueObservedRequest {
    pub method: String,
    pub path: String,
    pub target: Option<String>,
    pub protocol: Option<String>,
    pub capsule_protocol: Option<String>,
}

pub struct MasqueH2ConnectUdpFixture {
    address: SocketAddr,
    udp_echo_address: SocketAddr,
    observed: Arc<Mutex<Vec<MasqueObservedRequest>>>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl MasqueH2ConnectUdpFixture {
    pub async fn start() -> io::Result<Self> {
        let tls = server_tls_config()?;
        let observed = Arc::new(Mutex::new(Vec::new()));
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let observed_for_thread = Arc::clone(&observed);

        let thread = thread::spawn(move || {
            let runtime =
                tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build MASQUE fixture runtime");
            runtime.block_on(async move {
                let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind MASQUE H2 fixture");
                let udp_echo = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind MASQUE UDP echo fixture");
                let tcp_address = listener.local_addr().expect("MASQUE fixture local TCP address");
                let udp_address = udp_echo.local_addr().expect("MASQUE fixture local UDP address");
                addr_tx.send((tcp_address, udp_address)).ok();
                tokio::spawn(echo_udp(udp_echo));
                serve(listener, tls, observed_for_thread, shutdown_rx).await;
            });
        });

        let (address, udp_echo_address) =
            addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))?;
        Ok(Self { address, udp_echo_address, observed, shutdown: Some(shutdown_tx), thread: Some(thread) })
    }

    pub fn masque_url(&self) -> String {
        format!("https://127.0.0.1:{}/.well-known/masque/ip", self.address.port())
    }

    pub fn udp_echo_target(&self) -> String {
        self.udp_echo_address.to_string()
    }

    pub fn observed_requests(&self) -> Vec<MasqueObservedRequest> {
        self.observed.lock().expect("MASQUE fixture observations").clone()
    }
}

impl Drop for MasqueH2ConnectUdpFixture {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            shutdown.send(()).ok();
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
}

fn server_tls_config() -> io::Result<Arc<ServerConfig>> {
    let key_pair = KeyPair::generate().map_err(to_io)?;
    let mut params = CertificateParams::new(vec![SERVER_NAME.to_owned(), "127.0.0.1".to_owned()]).map_err(to_io)?;
    params.is_ca = IsCa::NoCa;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, SERVER_NAME);
    params.distinguished_name = dn;
    let cert = params.self_signed(&key_pair).map_err(to_io)?;

    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let mut server = ServerConfig::builder_with_provider(provider.into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![cert.der().clone()],
            PrivateKeyDer::from(PrivatePkcs8KeyDer::from(key_pair.serialize_der())),
        )
        .map_err(to_io)?;
    server.alpn_protocols = vec![b"h2".to_vec()];
    Ok(Arc::new(server))
}

async fn serve(
    listener: TcpListener,
    server_config: Arc<ServerConfig>,
    observed: Arc<Mutex<Vec<MasqueObservedRequest>>>,
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
                let observed = Arc::clone(&observed);
                tokio::spawn(async move {
                    let Ok(tls) = acceptor.accept(socket).await else {
                        return;
                    };
                    let service = service_fn(move |request| handle_request(request, Arc::clone(&observed)));
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
    observed: Arc<Mutex<Vec<MasqueObservedRequest>>>,
) -> Result<Response<Empty<Bytes>>, Infallible> {
    let protocol = request.extensions().get::<Protocol>().map(|protocol| protocol.as_str().to_string());
    let capsule_protocol =
        request.headers().get("capsule-protocol").and_then(|value| value.to_str().ok()).map(ToOwned::to_owned);
    let path = request.uri().path().to_string();
    let target = parse_connect_udp_target(&path);
    observed.lock().expect("MASQUE fixture observations").push(MasqueObservedRequest {
        method: request.method().to_string(),
        path,
        target: target.as_ref().map(ToString::to_string),
        protocol: protocol.clone(),
        capsule_protocol: capsule_protocol.clone(),
    });

    if request.method() != Method::CONNECT {
        return Ok(response(StatusCode::METHOD_NOT_ALLOWED));
    }
    if protocol.as_deref() != Some(CONNECT_UDP_PROTOCOL) {
        return Ok(response(StatusCode::BAD_REQUEST));
    }
    if capsule_protocol.as_deref() != Some(CAPSULE_PROTOCOL) {
        return Ok(response(StatusCode::BAD_REQUEST));
    }
    let Some(target) = target else {
        return Ok(response(StatusCode::BAD_REQUEST));
    };

    let upgraded = hyper::upgrade::on(request);
    tokio::spawn(async move {
        if let Ok(upgraded) = upgraded.await {
            relay_udp_capsules(TokioIo::new(upgraded), target).await;
        }
    });

    Ok(Response::builder()
        .status(StatusCode::OK)
        .header("capsule-protocol", CAPSULE_PROTOCOL)
        .body(Empty::<Bytes>::new())
        .expect("valid MASQUE fixture response"))
}

fn response(status: StatusCode) -> Response<Empty<Bytes>> {
    Response::builder().status(status).body(Empty::<Bytes>::new()).expect("valid MASQUE fixture response")
}

async fn relay_udp_capsules(mut io: TokioIo<hyper::upgrade::Upgraded>, target: SocketAddr) {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        let read = match io.read(&mut chunk).await {
            Ok(0) => break,
            Ok(read) => read,
            Err(_) => break,
        };
        buffer.extend_from_slice(&chunk[..read]);
        match decode_datagram_capsules(&buffer) {
            Ok(capsules) => {
                buffer.clear();
                for capsule in capsules {
                    let Some(payload) = decode_connect_udp_context_zero(&capsule) else {
                        continue;
                    };
                    if let Ok(reply) = send_udp_and_receive(target, payload).await {
                        let mut datagram_payload = encode_quic_varint(0);
                        datagram_payload.extend_from_slice(&reply);
                        let capsule = encode_datagram_capsule(&datagram_payload);
                        if io.write_all(&capsule).await.is_err() {
                            return;
                        }
                    }
                }
            }
            Err(DecodeError::Truncated) => {}
            Err(DecodeError::Malformed) => break,
        }
    }
}

async fn send_udp_and_receive(target: SocketAddr, payload: &[u8]) -> io::Result<Vec<u8>> {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    socket.send_to(payload, target).await?;
    let mut buffer = [0u8; 65_535];
    let (read, _) = socket.recv_from(&mut buffer).await?;
    Ok(buffer[..read].to_vec())
}

async fn echo_udp(socket: UdpSocket) {
    let mut buffer = [0u8; 65_535];
    while let Ok((read, peer)) = socket.recv_from(&mut buffer).await {
        if socket.send_to(&buffer[..read], peer).await.is_err() {
            break;
        }
    }
}

fn parse_connect_udp_target(path: &str) -> Option<SocketAddr> {
    let suffix = path.strip_prefix(MASQUE_PATH_PREFIX)?.strip_suffix('/')?;
    let (host, port) = suffix.rsplit_once('/')?;
    let host = percent_decode(host);
    let port = port.parse::<u16>().ok()?;
    format!("{host}:{port}").parse().ok()
}

fn encode_datagram_capsule(payload: &[u8]) -> Vec<u8> {
    let mut encoded = encode_quic_varint(DATAGRAM_CAPSULE_TYPE);
    encoded.extend_from_slice(&encode_quic_varint(payload.len() as u64));
    encoded.extend_from_slice(payload);
    encoded
}

fn decode_datagram_capsules(input: &[u8]) -> Result<Vec<Vec<u8>>, DecodeError> {
    let mut cursor = 0;
    let mut payloads = Vec::new();
    while cursor < input.len() {
        let (capsule_type, type_len) = decode_quic_varint(&input[cursor..])?;
        cursor += type_len;
        let (payload_len, len_len) = decode_quic_varint(&input[cursor..])?;
        cursor += len_len;
        let payload_len = usize::try_from(payload_len).map_err(|_| DecodeError::Malformed)?;
        let payload_end = cursor.checked_add(payload_len).ok_or(DecodeError::Malformed)?;
        if payload_end > input.len() {
            return Err(DecodeError::Truncated);
        }
        if capsule_type == DATAGRAM_CAPSULE_TYPE {
            payloads.push(input[cursor..payload_end].to_vec());
        }
        cursor = payload_end;
    }
    Ok(payloads)
}

fn encode_quic_varint(value: u64) -> Vec<u8> {
    if value <= 63 {
        vec![value as u8]
    } else if value < (1 << 14) {
        let mut bytes = (value as u16).to_be_bytes();
        bytes[0] |= 0x40;
        bytes.to_vec()
    } else if value < (1 << 30) {
        let mut bytes = (value as u32).to_be_bytes();
        bytes[0] |= 0x80;
        bytes.to_vec()
    } else {
        let mut bytes = value.to_be_bytes();
        bytes[0] |= 0xc0;
        bytes.to_vec()
    }
}

fn decode_quic_varint(input: &[u8]) -> Result<(u64, usize), DecodeError> {
    let first = *input.first().ok_or(DecodeError::Truncated)?;
    let width = 1_usize << usize::from(first >> 6);
    if input.len() < width {
        return Err(DecodeError::Truncated);
    }
    let value = match width {
        1 => u64::from(first & 0x3f),
        2 => u64::from(u16::from_be_bytes([first & 0x3f, input[1]])),
        4 => u64::from(u32::from_be_bytes([first & 0x3f, input[1], input[2], input[3]])),
        8 => u64::from_be_bytes([first & 0x3f, input[1], input[2], input[3], input[4], input[5], input[6], input[7]]),
        _ => unreachable!("QUIC varint width is 1, 2, 4, or 8 bytes"),
    };
    Ok((value, width))
}

fn decode_connect_udp_context_zero(input: &[u8]) -> Option<&[u8]> {
    let (context_id, context_len) = decode_quic_varint(input).ok()?;
    (context_id == 0).then_some(&input[context_len..])
}

fn to_io<E>(error: E) -> io::Error
where
    E: ToString,
{
    io::Error::other(error.to_string())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DecodeError {
    Truncated,
    Malformed,
}
