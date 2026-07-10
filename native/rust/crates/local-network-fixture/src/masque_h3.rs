use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use h3::ext::Protocol;
use http::{Method, Request, Response, StatusCode, Version};
use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use rcgen::generate_simple_self_signed;
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};

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
        let (server_config, certificate) = server_config()?;
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
        self.observed.lock().expect("MASQUE H3 fixture observations").clone()
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

fn server_config() -> io::Result<(quinn::ServerConfig, CertificateDer<'static>)> {
    let certified = generate_simple_self_signed(vec![SERVER_NAME.to_string()])
        .map_err(|error| io::Error::other(format!("generate MASQUE H3 fixture certificate: {error}")))?;
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
    Ok((quinn::ServerConfig::with_crypto(Arc::new(quic)), certificate))
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

async fn handle_request(
    request: Request<()>,
    mut stream: h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>,
    allowed_target: SocketAddr,
    observed: Arc<Mutex<Vec<MasqueH3ObservedRequest>>>,
) {
    let accepted = is_valid_classic_connect(&request, allowed_target);
    observed.lock().expect("MASQUE H3 fixture observations").push(observation(&request, accepted));
    if !accepted {
        send_status(&mut stream, StatusCode::BAD_REQUEST).await;
        return;
    }

    send_status(&mut stream, StatusCode::NOT_IMPLEMENTED).await;
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

async fn send_status(stream: &mut h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>, status: StatusCode) {
    let response = Response::builder().status(status).body(()).expect("valid H3 fixture response");
    if stream.send_response(response).await.is_ok() {
        let _ = stream.finish().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
