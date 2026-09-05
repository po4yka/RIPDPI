use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use h3::ext::Protocol;
use h3_datagram::datagram_handler::HandleDatagramsExt;
use http::{Method, Request, Response, StatusCode, Version};
use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use rcgen::generate_simple_self_signed;
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;

const SERVER_NAME: &str = "localhost";
const FIXTURE_TARGET: &str = "127.0.0.1:9";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MasqueH3ObservedRequest {
    pub method: String,
    pub version: Version,
    pub scheme: Option<String>,
    pub authority: Option<String>,
    pub path_and_query: Option<String>,
    pub protocol: Option<String>,
    pub capsule_protocol: Option<String>,
    pub accepted: bool,
}

fn lock_observations(
    observed: &Mutex<Vec<MasqueH3ObservedRequest>>,
) -> std::sync::MutexGuard<'_, Vec<MasqueH3ObservedRequest>> {
    observed.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn record_observation(observed: &Mutex<Vec<MasqueH3ObservedRequest>>, request: MasqueH3ObservedRequest) {
    lock_observations(observed).push(request);
}

/// H3-only RFC 9114 classic-CONNECT request-shape oracle. It exposes no TCP
/// listener on the proxy port, so an HTTP/2 fallback cannot make an H3
/// conformance test pass. A conformant request receives `501 Not Implemented`:
/// positive tunneling remains unavailable until the client can encode it.
///
/// Cleanup order: abort the server task first, then close the QUIC endpoint so
/// no task can accept a new connection while the fixture is being dropped.
pub struct MasqueH3ClassicConnectFixture {
    address: SocketAddr,
    certificate: CertificateDer<'static>,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
    accepted_connections: Arc<AtomicUsize>,
    endpoint: quinn::Endpoint,
    server_task: tokio::task::JoinHandle<()>,
}

impl MasqueH3ClassicConnectFixture {
    pub async fn start() -> io::Result<Self> {
        let (server_config, certificate, _certificate_pem) = server_config()?;
        let endpoint = quinn::Endpoint::server(server_config, (Ipv4Addr::LOCALHOST, 0).into())
            .map_err(|error| io::Error::other(format!("create MASQUE H3 fixture endpoint: {error}")))?;
        let address = endpoint.local_addr()?;
        let allowed_target = FIXTURE_TARGET.parse().expect("fixed fixture target");
        let observed = Arc::new(Mutex::new(Vec::new()));
        let accepted_connections = Arc::new(AtomicUsize::new(0));
        let server_task = tokio::spawn(serve_h3(
            endpoint.clone(),
            allowed_target,
            Arc::clone(&observed),
            Arc::clone(&accepted_connections),
        ));
        Ok(Self { address, certificate, observed, accepted_connections, endpoint, server_task })
    }

    pub fn proxy_address(&self) -> SocketAddr {
        self.address
    }

    pub fn masque_url(&self) -> String {
        format!("https://{SERVER_NAME}:{}/", self.address.port())
    }

    pub fn tcp_target(&self) -> &'static str {
        FIXTURE_TARGET
    }

    pub fn observed_requests(&self) -> Vec<MasqueH3ObservedRequest> {
        lock_observations(&self.observed).clone()
    }

    pub fn accepted_connection_count(&self) -> usize {
        // Ordering: this is an independent diagnostic counter and publishes no associated data.
        self.accepted_connections.load(Ordering::Relaxed)
    }

    pub fn client_config(&self) -> io::Result<quinn::ClientConfig> {
        let mut roots = rustls::RootCertStore::empty();
        roots.add(self.certificate.clone()).map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidData, format!("trust fixture certificate: {error}"))
        })?;
        let mut tls = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .map_err(|error| io::Error::other(format!("build fixture client TLS versions: {error}")))?
            .with_root_certificates(roots)
            .with_no_client_auth();
        tls.alpn_protocols = vec![b"h3".to_vec()];
        let quic = QuicClientConfig::try_from(tls)
            .map_err(|error| io::Error::other(format!("build fixture QUIC client config: {error}")))?;
        Ok(quinn::ClientConfig::new(Arc::new(quic)))
    }
}

impl Drop for MasqueH3ClassicConnectFixture {
    fn drop(&mut self) {
        self.server_task.abort();
        self.endpoint.close(0_u32.into(), b"fixture drop");
    }
}

/// Real single-flow RFC 9298 CONNECT-UDP fixture over Quinn/H3 DATAGRAM.
///
/// The caller owns the injected UDP socket, which lets PMTUD tests place the
/// production MASQUE client behind an observable black-hole fault without
/// weakening the strict classic-CONNECT oracle above.
// Drop order: cancel signals shutdown before server_task and target_task are
// aborted; this prevents either task from outliving the fixture endpoint.
pub struct MasqueH3ConnectUdpFixture {
    address: SocketAddr,
    target: String,
    certificate_pem: String,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
    echoed_datagrams: Arc<AtomicUsize>,
    cancel: CancellationToken,
    endpoint: quinn::Endpoint,
    server_task: Option<tokio::task::JoinHandle<()>>,
    target_task: Option<tokio::task::JoinHandle<()>>,
}

impl MasqueH3ConnectUdpFixture {
    pub fn start() -> io::Result<Self> {
        let (server_config, _certificate, certificate_pem) = server_config()?;
        let endpoint = quinn::Endpoint::server(server_config, (Ipv4Addr::LOCALHOST, 0).into())
            .map_err(|error| io::Error::other(format!("create MASQUE H3 CONNECT-UDP endpoint: {error}")))?;
        Self::spawn(endpoint, certificate_pem, None)
    }

    pub fn start_with_socket(socket: Arc<dyn quinn::AsyncUdpSocket>) -> io::Result<Self> {
        let (server_config, _certificate, certificate_pem) = server_config()?;
        let endpoint = quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            Some(server_config),
            socket,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(|error| io::Error::other(format!("create MASQUE H3 CONNECT-UDP endpoint: {error}")))?;
        Self::spawn(endpoint, certificate_pem, None)
    }

    pub fn start_with_socket_ipv6_target(socket: Arc<dyn quinn::AsyncUdpSocket>) -> io::Result<Self> {
        let (server_config, _certificate, certificate_pem) = server_config()?;
        let endpoint = quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            Some(server_config),
            socket,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(|error| io::Error::other(format!("create MASQUE H3 CONNECT-UDP endpoint: {error}")))?;
        Self::spawn(endpoint, certificate_pem, Some(IpAddr::V6(Ipv6Addr::LOCALHOST)))
    }

    fn spawn(endpoint: quinn::Endpoint, certificate_pem: String, target_ip: Option<IpAddr>) -> io::Result<Self> {
        let address = endpoint.local_addr()?;
        let (allowed_target, target_task) = if let Some(target_ip) = target_ip {
            let (target, task) = start_udp_echo_target(target_ip)?;
            (target, Some(task))
        } else {
            (SocketAddr::from((Ipv4Addr::LOCALHOST, 9)), None)
        };
        let target = allowed_target.to_string();
        let observed = Arc::new(Mutex::new(Vec::new()));
        let echoed_datagrams = Arc::new(AtomicUsize::new(0));
        let cancel = CancellationToken::new();
        let server_task = tokio::spawn(serve_h3_connect_udp(
            endpoint.clone(),
            allowed_target,
            target_task.is_some(),
            Arc::clone(&observed),
            Arc::clone(&echoed_datagrams),
            cancel.clone(),
        ));
        Ok(Self {
            address,
            target,
            certificate_pem,
            observed,
            echoed_datagrams,
            cancel,
            endpoint,
            server_task: Some(server_task),
            target_task,
        })
    }

    pub fn proxy_address(&self) -> SocketAddr {
        self.address
    }

    pub fn masque_url(&self) -> String {
        format!("https://{SERVER_NAME}:{}/", self.address.port())
    }

    pub fn udp_target(&self) -> &str {
        &self.target
    }

    pub fn certificate_pem(&self) -> &str {
        &self.certificate_pem
    }

    pub fn observed_requests(&self) -> Vec<MasqueH3ObservedRequest> {
        lock_observations(&self.observed).clone()
    }

    pub fn echoed_datagram_count(&self) -> usize {
        self.echoed_datagrams.load(Ordering::Relaxed)
    }

    /// Stop accepting work, abort fixture-owned children, and join both task trees.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: dropping this future can detach an aborting task; callers
    /// must drive shutdown to completion once started.
    pub async fn shutdown(mut self) {
        self.cancel.cancel();
        self.endpoint.close(0_u32.into(), b"fixture shutdown");
        if let Some(task) = self.target_task.take() {
            task.abort();
            let _ = task.await;
        }
        if let Some(task) = self.server_task.take() {
            let _ = task.await;
        }
    }
}

impl Drop for MasqueH3ConnectUdpFixture {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(task) = &self.server_task {
            task.abort();
        }
        if let Some(task) = &self.target_task {
            task.abort();
        }
        self.endpoint.close(0_u32.into(), b"fixture drop");
    }
}

fn start_udp_echo_target(ip: IpAddr) -> io::Result<(SocketAddr, tokio::task::JoinHandle<()>)> {
    let socket = std::net::UdpSocket::bind(SocketAddr::new(ip, 0))?;
    socket.set_nonblocking(true)?;
    let address = socket.local_addr()?;
    let socket = tokio::net::UdpSocket::from_std(socket)?;
    Ok((address, tokio::spawn(serve_udp_echo_target(socket))))
}

/// Echo target for the optional real CONNECT-UDP target path.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation after `recv_from` and before `send_to` drops
/// the received datagram without echoing it; fixture shutdown aborts and joins it.
async fn serve_udp_echo_target(socket: tokio::net::UdpSocket) {
    let mut buffer = [0_u8; 65_535];
    while let Ok((read, peer)) = socket.recv_from(&mut buffer).await {
        if socket.send_to(&buffer[..read], peer).await.is_err() {
            break;
        }
    }
}

/// Serve exactly one CONNECT-UDP request per QUIC connection and echo context-0
/// H3 DATAGRAM payloads without retaining their bytes.
///
/// # Cancel safety
///
/// NOT cancel-safe: callers must signal `cancel` and await the server task so
/// its connection `JoinSet` can abort and join every child.
async fn serve_h3_connect_udp(
    endpoint: quinn::Endpoint,
    allowed_target: SocketAddr,
    relay_to_target: bool,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
    echoed_datagrams: Arc<AtomicUsize>,
    cancel: CancellationToken,
) {
    let mut connections = JoinSet::new();
    loop {
        tokio::select! {
            _ = cancel.cancelled() => break,
            completed = connections.join_next(), if !connections.is_empty() => {
                let _ = completed;
            }
            incoming = endpoint.accept() => {
                let Some(incoming) = incoming else { break };
                connections.spawn(handle_h3_connect_udp_connection(
                    incoming,
                    allowed_target,
                    relay_to_target,
                    Arc::clone(&observed),
                    Arc::clone(&echoed_datagrams),
                    cancel.child_token(),
                ));
            }
        }
    }
    connections.abort_all();
    while connections.join_next().await.is_some() {}
}

/// Handle one fixture-owned CONNECT-UDP flow.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation can interrupt a datagram relay transaction;
/// shutdown owns the entire connection and joins this task after aborting it.
async fn handle_h3_connect_udp_connection(
    incoming: quinn::Incoming,
    allowed_target: SocketAddr,
    relay_to_target: bool,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
    echoed_datagrams: Arc<AtomicUsize>,
    cancel: CancellationToken,
) {
    let Ok(connection) = incoming.await else {
        return;
    };
    let mut builder = h3::server::builder();
    builder.enable_extended_connect(true);
    builder.enable_datagram(true);
    let Ok(mut h3_connection) = builder.build(h3_quinn::Connection::new(connection)).await else {
        return;
    };
    let Ok(Some(resolver)) = h3_connection.accept().await else {
        return;
    };
    let Ok((request, mut stream)) = resolver.resolve_request().await else {
        return;
    };
    let accepted = is_valid_connect_udp(&request, allowed_target);
    let request_observation = observation(&request, accepted);
    if !accepted {
        if send_status(&mut stream, StatusCode::BAD_REQUEST).await {
            record_observation(&observed, request_observation);
        }
        return;
    }

    let stream_id = stream.id();
    let response = Response::builder()
        .status(StatusCode::OK)
        .header("capsule-protocol", "?1")
        .body(())
        .expect("valid CONNECT-UDP response");
    if stream.send_response(response).await.is_err() {
        return;
    }
    record_observation(&observed, request_observation);
    let _response_stream = stream;
    let mut datagram_sender = h3_connection.get_datagram_sender(stream_id);
    let mut datagram_reader = h3_connection.get_datagram_reader();
    let relay = if relay_to_target {
        let relay_bind = if allowed_target.is_ipv4() {
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)
        } else {
            SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 0)
        };
        let Ok(relay) = tokio::net::UdpSocket::bind(relay_bind).await else {
            return;
        };
        Some(relay)
    } else {
        None
    };
    loop {
        let datagram = tokio::select! {
            _ = cancel.cancelled() => break,
            result = datagram_reader.read_datagram() => match result {
                Ok(datagram) => datagram,
                Err(_) => break,
            }
        };
        if datagram.stream_id() != stream_id {
            continue;
        }
        let payload = datagram.into_payload();
        let echoed = if let Some(relay) = &relay {
            let Some(echoed) = relay_connect_udp_payload(relay, allowed_target, payload).await else {
                break;
            };
            echoed
        } else {
            payload
        };
        if datagram_sender.send_datagram(echoed).is_err() {
            break;
        }
        echoed_datagrams.fetch_add(1, Ordering::Relaxed);
    }
}

/// Relay one context-zero DATAGRAM through the actual UDP echo target.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation after the target send can leave its echo queued
/// on the fixture-owned relay socket; connection shutdown owns and drops it.
async fn relay_connect_udp_payload(relay: &tokio::net::UdpSocket, target: SocketAddr, payload: Bytes) -> Option<Bytes> {
    let user_payload = payload.strip_prefix(&[0])?;
    relay.send_to(user_payload, target).await.ok()?;
    let mut echoed = vec![0_u8; user_payload.len()];
    let (read, peer) = relay.recv_from(&mut echoed).await.ok()?;
    if peer != target || read != user_payload.len() || echoed[..read] != *user_payload {
        return None;
    }
    echoed.truncate(read);
    let mut encoded = Vec::with_capacity(read + 1);
    encoded.push(0);
    encoded.extend_from_slice(&echoed);
    Some(Bytes::from(encoded))
}

fn is_valid_connect_udp(request: &Request<()>, allowed_target: SocketAddr) -> bool {
    let encoded_host = allowed_target.ip().to_string().replace(':', "%3A");
    let expected_path = format!("/.well-known/masque/udp/{encoded_host}/{}/", allowed_target.port());
    request.version() == Version::HTTP_3
        && request.method() == Method::CONNECT
        && request.uri().scheme_str() == Some("https")
        && request.uri().authority().is_some()
        && request.uri().path() == expected_path
        && request.extensions().get::<Protocol>() == Some(&Protocol::CONNECT_UDP)
        && request.headers().get("capsule-protocol").is_some_and(|value| value == "?1")
}

pub(crate) fn server_config() -> io::Result<(quinn::ServerConfig, CertificateDer<'static>, String)> {
    let certified = generate_simple_self_signed(vec![SERVER_NAME.to_string()])
        .map_err(|error| io::Error::other(format!("generate MASQUE H3 fixture certificate: {error}")))?;
    let certificate_pem = certified.cert.pem();
    let certificate = CertificateDer::from(certified.cert.der().to_vec());
    let private_key = PrivatePkcs8KeyDer::from(certified.signing_key.serialize_der());
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .map_err(|error| io::Error::other(format!("build fixture server TLS versions: {error}")))?
        .with_no_client_auth()
        .with_single_cert(vec![certificate.clone()], private_key.into())
        .map_err(|error| io::Error::other(format!("build fixture server certificate: {error}")))?;
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let quic = QuicServerConfig::try_from(tls)
        .map_err(|error| io::Error::other(format!("build fixture QUIC server config: {error}")))?;
    Ok((quinn::ServerConfig::with_crypto(Arc::new(quic)), certificate, certificate_pem))
}

async fn serve_h3(
    endpoint: quinn::Endpoint,
    allowed_target: SocketAddr,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
    accepted_connections: Arc<AtomicUsize>,
) {
    while let Some(incoming) = endpoint.accept().await {
        // Ordering: this is an independent diagnostic counter and publishes no associated data.
        accepted_connections.fetch_add(1, Ordering::Relaxed);
        let observed = Arc::clone(&observed);
        tokio::spawn(async move {
            let Ok(connection) = incoming.await else {
                return;
            };
            let Ok(mut h3_connection) = h3::server::Connection::new(h3_quinn::Connection::new(connection)).await else {
                return;
            };
            loop {
                let Ok(Some(resolver)) = h3_connection.accept().await else {
                    break;
                };
                let observed = Arc::clone(&observed);
                tokio::spawn(async move {
                    let Ok((request, stream)) = resolver.resolve_request().await else {
                        return;
                    };
                    handle_request(request, stream, allowed_target, observed).await;
                });
            }
        });
    }
}

/// Handle one classic-CONNECT request.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation can interrupt the response stream; the owning
/// classic fixture closes the QUIC endpoint during teardown.
async fn handle_request(
    request: Request<()>,
    mut stream: h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>,
    allowed_target: SocketAddr,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
) {
    let accepted = is_valid_classic_connect(&request, allowed_target);
    let request_observation = observation(&request, accepted);
    if !accepted {
        if send_status(&mut stream, StatusCode::BAD_REQUEST).await {
            record_observation(&observed, request_observation);
        }
        return;
    }

    if send_status(&mut stream, StatusCode::NOT_IMPLEMENTED).await {
        record_observation(&observed, request_observation);
    }
}

fn is_valid_classic_connect(request: &Request<()>, allowed_target: SocketAddr) -> bool {
    request.version() == Version::HTTP_3
        && request.method() == Method::CONNECT
        && request.uri().scheme().is_none()
        && request.uri().path_and_query().is_none()
        && request.uri().authority().is_some_and(|authority| authority.as_str() == allowed_target.to_string())
        && request.extensions().get::<Protocol>().is_none()
        && !request.headers().contains_key("capsule-protocol")
}

fn observation(request: &Request<()>, accepted: bool) -> MasqueH3ObservedRequest {
    MasqueH3ObservedRequest {
        method: request.method().to_string(),
        version: request.version(),
        scheme: request.uri().scheme_str().map(ToOwned::to_owned),
        authority: request.uri().authority().map(|authority| authority.as_str().to_string()),
        path_and_query: request.uri().path_and_query().map(|path| path.as_str().to_string()),
        protocol: request.extensions().get::<Protocol>().map(|protocol| protocol.as_str().to_string()),
        capsule_protocol: request
            .headers()
            .get("capsule-protocol")
            .and_then(|value| value.to_str().ok())
            .map(ToOwned::to_owned),
        accepted,
    }
}

/// Send and finish one fixture response.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancellation after response headers are sent can prevent
/// stream finish; the owning fixture connection is closed during teardown.
async fn send_status(
    stream: &mut h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>,
    status: StatusCode,
) -> bool {
    let response = Response::builder().status(status).body(()).expect("valid H3 fixture response");
    if stream.send_response(response).await.is_err() {
        return false;
    }
    stream.finish().await.is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn observation_recording_recovers_a_poisoned_mutex() {
        let observed = Arc::new(Mutex::new(Vec::new()));
        let poisoned = Arc::clone(&observed);
        let panicked = std::thread::spawn(move || {
            let _guard = poisoned.lock().expect("observation test lock");
            panic!("poison observation mutex");
        })
        .join();
        assert!(panicked.is_err());

        record_observation(
            &observed,
            MasqueH3ObservedRequest {
                method: "CONNECT".to_owned(),
                version: Version::HTTP_3,
                scheme: None,
                authority: Some("127.0.0.1:443".to_owned()),
                path_and_query: None,
                protocol: None,
                capsule_protocol: None,
                accepted: true,
            },
        );

        let recorded = lock_observations(&observed);
        assert_eq!(recorded.len(), 1);
        assert!(recorded[0].accepted);
    }

    #[test]
    fn classic_connect_validator_requires_rfc_9114_request_shape() {
        let target: SocketAddr = "127.0.0.1:443".parse().expect("target");
        let valid = Request::builder()
            .version(Version::HTTP_3)
            .method(Method::CONNECT)
            .uri(target.to_string())
            .body(())
            .expect("classic CONNECT request");
        let invalid = Request::builder()
            .version(Version::HTTP_3)
            .method(Method::CONNECT)
            .uri(format!("https://{target}/"))
            .body(())
            .expect("nonconforming CONNECT request");

        assert!(is_valid_classic_connect(&valid, target));
        assert!(!is_valid_classic_connect(&invalid, target));
    }

    #[test]
    fn connect_udp_validator_accepts_percent_encoded_ipv6_target() {
        let target: SocketAddr = "[::1]:443".parse().expect("IPv6 target");
        let mut request = Request::builder()
            .version(Version::HTTP_3)
            .method(Method::CONNECT)
            .uri("https://localhost/.well-known/masque/udp/%3A%3A1/443/")
            .header("capsule-protocol", "?1")
            .body(())
            .expect("CONNECT-UDP request");
        request.extensions_mut().insert(Protocol::CONNECT_UDP);

        assert!(is_valid_connect_udp(&request, target));
    }
}
