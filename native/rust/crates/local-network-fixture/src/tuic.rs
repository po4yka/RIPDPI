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
                            let _ = handle_connection(incoming).await;
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

    let endpoint = quinn::Endpoint::server(server_cfg, (Ipv4Addr::LOCALHOST, 0).into())
        .map_err(|error| io::Error::other(error.to_string()))?;
    Ok((endpoint, certificate_pem))
}

async fn handle_connection(incoming: quinn::Incoming) -> io::Result<()> {
    let connection = incoming.await.map_err(io::Error::other)?;

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
