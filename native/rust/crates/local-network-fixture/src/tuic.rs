//! TUIC v5 protocol-server loopback fixture.
//!
//! A quinn-based server that speaks enough of the real TUIC v5 protocol for the
//! throughput bench to drive the real `ripdpi-tuic` client through a 1 MiB
//! round-trip. Unlike `ripdpi-protocol-loopback`'s `QuicLoopback` (a generic
//! QUIC echo), this handles the TUIC handshake the client performs:
//!
//! 1. QUIC + TLS with ALPN `h3` and a self-signed cert. The client trusts it by
//!    pinning the fixture's cert via `Config::root_certificate_pem` (exposed by
//!    [`TuicLoopback::certificate_pem`]) — TLS verification stays ON.
//! 2. Auth: the client opens a unidirectional stream carrying `[0x05, 0x00
//!    (Authenticate), uuid(16), token(32)]` (token = TLS keying-material export)
//!    and finishes it. The client does NOT await a response, so the loopback
//!    just drains the auth stream without validating the token (this is a
//!    throughput fixture, not an auth check).
//! 3. TCP proxy: the client opens a bidirectional stream with `[0x05, 0x01
//!    (Connect), address]` then sends data. The loopback parses + discards the
//!    Connect header and echoes the rest of the stream.
//!
//! Dev/test fixture only — never a production TUIC server.

use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use rcgen::generate_simple_self_signed;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

const TUIC_VERSION: u8 = 0x05;
const COMMAND_CONNECT: u8 = 0x01;
/// EAimTY/tuic v4 wire byte — the synthetic reject marker used by
/// [`TuicLoopback::start_rejecting_version`].
const TUIC_V4_VERSION: u8 = 0x04;

/// How the fixture handles an accepted connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ServerMode {
    /// Speak the real TUIC v5 protocol: drain auth, echo the proxied stream.
    Echo,
    /// Drain the client's auth stream, then reject with an application-close
    /// whose reason begins with the v4 wire byte — a stand-in for a deployed
    /// v4 server rejecting this v5 client. Drives the client's
    /// version-unsupported handshake-failure path.
    RejectVersion,
}

/// A loopback TUIC v5 server. Accepts (and ignores) the client's auth and echoes
/// the proxied TCP stream.
// Drop order: shutdown drops-before join; the Drop body fires the oneshot stop signal (and closes the quinn endpoint) before the accept-loop `JoinHandle` is detached, so the loop observes shutdown on its next poll.
pub struct TuicLoopback {
    local_addr: SocketAddr,
    certificate_pem: String,
    endpoint: quinn::Endpoint,
    shutdown: Option<oneshot::Sender<()>>,
    join: Option<JoinHandle<()>>,
}

impl TuicLoopback {
    /// Start the fixture on `127.0.0.1:0`.
    pub async fn start() -> io::Result<Self> {
        let (endpoint, certificate_pem) = build_server_endpoint()?;
        Self::spawn(endpoint, certificate_pem, ServerMode::Echo)
    }

    /// Start a fixture that completes the QUIC/TLS handshake and drains the
    /// client's auth stream (so [`TuicClient::connect`] succeeds — v5 auth is
    /// fire-and-forget), then rejects with an application-close whose reason
    /// begins with the v4 wire byte. The real client classifies this as a
    /// version mismatch on its next relay-stream open. Synthetic stand-in for a
    /// deployed TUIC v4 server; never a production server.
    ///
    /// [`TuicClient::connect`]: ripdpi_tuic::TuicClient::connect
    pub async fn start_rejecting_version() -> io::Result<Self> {
        let (endpoint, certificate_pem) = build_server_endpoint()?;
        Self::spawn(endpoint, certificate_pem, ServerMode::RejectVersion)
    }

    /// Start the fixture on a caller-supplied abstract UDP socket (e.g. a
    /// `quic-mtu-test-util` `MtuDropSocket` for path-MTU fault injection). The
    /// socket's bound address becomes the server address — read it back from
    /// [`Self::local_addr`] / [`Self::port`]; pin the cert via
    /// [`Self::certificate_pem`] as usual.
    pub async fn start_with_socket(socket: Arc<dyn quinn::AsyncUdpSocket>) -> io::Result<Self> {
        let (server_cfg, certificate_pem) = build_server_config()?;
        let endpoint = quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            Some(server_cfg),
            socket,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(io::Error::other)?;
        Self::spawn(endpoint, certificate_pem, ServerMode::Echo)
    }

    /// Wire a server endpoint into the shutdown-aware accept loop.
    fn spawn(endpoint: quinn::Endpoint, certificate_pem: String, mode: ServerMode) -> io::Result<Self> {
        let local_addr = endpoint.local_addr()?;

        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        let accept_endpoint = endpoint.clone();
        let join = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    incoming = accept_endpoint.accept() => {
                        let Some(incoming) = incoming else { break };
                        tokio::spawn(async move {
                            let _ = handle_connection(incoming, mode).await;
                        });
                    }
                }
            }
        });

        Ok(Self { local_addr, certificate_pem, endpoint, shutdown: Some(shutdown_tx), join: Some(join) })
    }

    /// The loopback address the server is listening on.
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// The UDP port the server is listening on.
    pub fn port(&self) -> u16 {
        self.local_addr.port()
    }

    /// PEM of the fixture's self-signed server certificate, to pin via
    /// `ripdpi_tuic::Config::root_certificate_pem`.
    pub fn certificate_pem(&self) -> &str {
        &self.certificate_pem
    }

    /// Stop the accept loop and close the endpoint.
    pub async fn shutdown(mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        if let Some(join) = self.join.take() {
            let _ = join.await;
        }
        self.endpoint.close(0u32.into(), b"shutdown");
    }
}

impl Drop for TuicLoopback {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        self.endpoint.close(0u32.into(), b"drop");
    }
}

fn build_server_endpoint() -> io::Result<(quinn::Endpoint, String)> {
    let (server_cfg, certificate_pem) = build_server_config()?;
    let endpoint = quinn::Endpoint::server(server_cfg, (Ipv4Addr::LOCALHOST, 0).into())
        .map_err(|error| io::Error::other(error.to_string()))?;
    Ok((endpoint, certificate_pem))
}

fn build_server_config() -> io::Result<(quinn::ServerConfig, String)> {
    let cert = generate_simple_self_signed(vec!["localhost".to_string()])
        .map_err(|error| io::Error::other(error.to_string()))?;
    let certificate_pem = cert.cert.pem();
    let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
    let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());

    let mut server_tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .map_err(|error| io::Error::other(error.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der.into())
        .map_err(|error| io::Error::other(error.to_string()))?;
    // The TUIC client negotiates ALPN "h3".
    server_tls.alpn_protocols = vec![b"h3".to_vec()];

    let quic_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(server_tls)
        .map_err(|error| io::Error::other(error.to_string()))?;
    let mut server_cfg = quinn::ServerConfig::with_crypto(Arc::new(quic_crypto));
    let mut transport = quinn::TransportConfig::default();
    transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().expect("valid idle timeout")));
    server_cfg.transport = Arc::new(transport);

    Ok((server_cfg, certificate_pem))
}

async fn handle_connection(incoming: quinn::Incoming, mode: ServerMode) -> io::Result<()> {
    let connection = incoming.await.map_err(io::Error::other)?;

    if mode == ServerMode::RejectVersion {
        // Wait for the client's auth uni-stream so `TuicClient::connect`
        // completes (v5 auth is fire-and-forget), then reject: application-close
        // with a reason whose leading byte is the v4 wire byte. The client's
        // `classify_failure_payload` reads this on its next stream open and
        // surfaces `TuicFailureKind::VersionUnsupported`.
        if let Ok(mut uni) = connection.accept_uni().await {
            let _ = uni.read_to_end(4096).await;
        }
        connection.close(u32::from(TUIC_V4_VERSION).into(), &[TUIC_V4_VERSION]);
        return Ok(());
    }

    // Drain the auth unidirectional stream(s). The client does not await a
    // response, so we just consume the Authenticate command and ignore the
    // token (throughput fixture, not an auth check).
    let auth_connection = connection.clone();
    tokio::spawn(async move {
        while let Ok(mut uni) = auth_connection.accept_uni().await {
            let _ = uni.read_to_end(4096).await;
        }
    });

    // Connect bidirectional streams: parse the Connect header, then echo.
    while let Ok((send, recv)) = connection.accept_bi().await {
        tokio::spawn(async move {
            let _ = handle_proxy_stream(send, recv).await;
        });
    }
    Ok(())
}

async fn handle_proxy_stream(mut send: quinn::SendStream, mut recv: quinn::RecvStream) -> io::Result<()> {
    // Connect header: [version, command] then the TUIC address.
    let mut header = [0u8; 2];
    recv.read_exact(&mut header).await.map_err(io::Error::other)?;
    if header[0] != TUIC_VERSION || header[1] != COMMAND_CONNECT {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unexpected TUIC command"));
    }
    skip_tuic_address(&mut recv).await?;

    // Echo the proxied payload back to the client until it closes its send side.
    let _ = tokio::io::copy(&mut recv, &mut send).await;
    Ok(())
}

/// Consume a TUIC address off the stream: a 1-byte kind then a kind-dependent
/// body (domain = len-prefixed host + port; v4/v6 = fixed octets + port).
async fn skip_tuic_address(recv: &mut quinn::RecvStream) -> io::Result<()> {
    let mut kind = [0u8; 1];
    recv.read_exact(&mut kind).await.map_err(io::Error::other)?;
    let body_len = match kind[0] {
        0xff => 0,      // None
        0x01 => 4 + 2,  // IPv4 + port
        0x02 => 16 + 2, // IPv6 + port
        0x00 => {
            let mut len = [0u8; 1]; // domain: 1-byte length + host + 2-byte port
            recv.read_exact(&mut len).await.map_err(io::Error::other)?;
            usize::from(len[0]) + 2
        }
        other => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unsupported TUIC address kind {other:#x}"),
            ));
        }
    };
    if body_len > 0 {
        let mut body = vec![0u8; body_len];
        recv.read_exact(&mut body).await.map_err(io::Error::other)?;
    }
    Ok(())
}
