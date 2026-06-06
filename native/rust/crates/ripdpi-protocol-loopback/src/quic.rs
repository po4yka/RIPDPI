//! `QuicLoopback` — a generic QUIC echo server on loopback for the harness.
//!
//! This is the shared QUIC primitive the design doc calls for
//! (`docs/architecture/protocol-loopback-harness-design.md` → `QuicLoopback::start()`).
//! It speaks plain QUIC (quinn) with a self-signed cert and a fixed ALPN — no
//! Hysteria 2 / TUIC framing — so the QUIC-dependent tasks (port-hopping soak,
//! QUIC PMTU regression, per-transport benches) can drive client + server
//! back-to-back with no network or upstream dependency.
//!
//! The crypto idiom (rcgen self-signed + `ring` provider + `QuicServerConfig`)
//! mirrors the canonical in-tree quinn echo server in
//! `ripdpi-tunnel-core/src/session/udp.rs`.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::oneshot;
use tokio::task::JoinHandle;

use crate::{LoopbackError, ProtocolLoopbackServer};

/// ALPN advertised by both ends of the loopback. Fixed so the client connector
/// and the server always agree without per-test plumbing.
pub const QUIC_LOOPBACK_ALPN: &[u8] = b"ripdpi-loopback";

/// Accepts any server certificate. The loopback presents an rcgen self-signed
/// cert with no chain; this keeps the harness hermetic (no trust store). It is
/// `pub` so consumers that build their own quinn client can reuse it, but it is
/// only ever compiled into the dev-only harness crate.
#[derive(Debug)]
pub struct AcceptAnyServerCert;

impl rustls::client::danger::ServerCertVerifier for AcceptAnyServerCert {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

/// A loopback QUIC echo server.
///
/// Each accepted connection echoes every bi-directional stream: it reads the
/// peer's request (up to `max_bytes_per_stream`, after the peer finishes its
/// send side), writes the same bytes back, and finishes. The accept loop runs
/// until the handle is dropped or [`QuicLoopback::shutdown`] is called.
///
/// Drop fires the shutdown signal (same fire-and-forget discipline as
/// `EchoLoopback`); use [`QuicLoopback::shutdown`] for synchronous teardown.
///
/// Drop order: shutdown drops-before join_handle; the `Drop::drop` body takes
/// `shutdown` via `Option::take` and fires the oneshot (then closes the quinn
/// endpoint) BEFORE the `join_handle` `JoinHandle` drops and detaches the accept
/// loop, which then sees the signal on its next `tokio::select!` poll and exits.
pub struct QuicLoopback {
    local_addr: SocketAddr,
    endpoint: quinn::Endpoint,
    shutdown: Option<oneshot::Sender<()>>,
    join_handle: Option<JoinHandle<()>>,
    max_bytes_per_stream: usize,
}

impl QuicLoopback {
    /// Start a QUIC echo server on `127.0.0.1:0`. `max_bytes_per_stream` bounds
    /// each echoed request so a runaway test cannot allocate without limit.
    ///
    /// # Errors
    /// Returns [`LoopbackError`] if the self-signed cert, the QUIC server crypto,
    /// or the UDP bind fails.
    pub async fn start(max_bytes_per_stream: usize) -> Result<Self, LoopbackError> {
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()])
            .map_err(|e| LoopbackError::Setup(e.to_string()))?;
        let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
        let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());

        let mut server_tls =
            rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .map_err(|e| LoopbackError::Setup(e.to_string()))?
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der.into())
                .map_err(|e| LoopbackError::Setup(e.to_string()))?;
        server_tls.alpn_protocols = vec![QUIC_LOOPBACK_ALPN.to_vec()];

        let quic_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(server_tls)
            .map_err(|e| LoopbackError::Setup(e.to_string()))?;
        let mut server_cfg = quinn::ServerConfig::with_crypto(Arc::new(quic_crypto));
        let mut transport = quinn::TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(10).try_into().expect("valid idle timeout")));
        server_cfg.transport = Arc::new(transport);

        let endpoint = quinn::Endpoint::server(server_cfg, (std::net::Ipv4Addr::LOCALHOST, 0).into())?;
        let local_addr = endpoint.local_addr()?;

        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        let accept_endpoint = endpoint.clone();
        let join_handle = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    incoming = accept_endpoint.accept() => {
                        let Some(incoming) = incoming else { break };
                        tokio::spawn(echo_connection(incoming, max_bytes_per_stream));
                    }
                }
            }
        });

        Ok(Self {
            local_addr,
            endpoint,
            shutdown: Some(shutdown_tx),
            join_handle: Some(join_handle),
            max_bytes_per_stream,
        })
    }

    /// Per-stream echo byte cap.
    #[must_use]
    pub fn max_bytes_per_stream(&self) -> usize {
        self.max_bytes_per_stream
    }

    /// A quinn client config that trusts this loopback's self-signed cert
    /// (via [`AcceptAnyServerCert`]) and negotiates [`QUIC_LOOPBACK_ALPN`].
    ///
    /// Use this when you need to own the client [`quinn::Endpoint`] — e.g. a
    /// port-hopping soak that calls [`quinn::Endpoint::rebind`] to migrate the
    /// connection. For a one-shot connection, prefer [`QuicLoopback::connect`].
    ///
    /// # Errors
    /// Returns [`LoopbackError`] if the rustls/quinn client crypto fails to build.
    pub fn client_config(&self) -> Result<quinn::ClientConfig, LoopbackError> {
        let mut client_tls =
            rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .map_err(|e| LoopbackError::Setup(e.to_string()))?
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(AcceptAnyServerCert))
                .with_no_client_auth();
        client_tls.alpn_protocols = vec![QUIC_LOOPBACK_ALPN.to_vec()];

        let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(client_tls)
            .map_err(|e| LoopbackError::Setup(e.to_string()))?;
        Ok(quinn::ClientConfig::new(Arc::new(quic_crypto)))
    }

    /// Connect a fresh quinn client to this loopback and return the established
    /// connection. The client trusts the self-signed cert via
    /// [`AcceptAnyServerCert`] and negotiates [`QUIC_LOOPBACK_ALPN`].
    ///
    /// # Errors
    /// Returns [`LoopbackError`] if the client crypto, bind, or handshake fails.
    pub async fn connect(&self) -> Result<quinn::Connection, LoopbackError> {
        let mut endpoint = quinn::Endpoint::client((std::net::Ipv4Addr::LOCALHOST, 0).into())?;
        endpoint.set_default_client_config(self.client_config()?);

        let connection = endpoint
            .connect(self.local_addr, "localhost")
            .map_err(|e| LoopbackError::Setup(e.to_string()))?
            .await
            .map_err(|e| LoopbackError::Setup(e.to_string()))?;
        Ok(connection)
    }

    /// Stop the accept loop and wait for it to terminate.
    ///
    /// # Errors
    /// Returns [`LoopbackError::ShutdownDuplicate`] if already shut down.
    pub async fn shutdown(mut self) -> Result<(), LoopbackError> {
        let tx = self.shutdown.take().ok_or(LoopbackError::ShutdownDuplicate)?;
        let _ = tx.send(());
        if let Some(handle) = self.join_handle.take() {
            let _ = handle.await;
        }
        self.endpoint.close(0u32.into(), b"shutdown");
        Ok(())
    }
}

impl ProtocolLoopbackServer for QuicLoopback {
    fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    fn protocol_id(&self) -> &'static str {
        "quic-echo"
    }
}

impl Drop for QuicLoopback {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        self.endpoint.close(0u32.into(), b"drop");
    }
}

/// Echo every bi-directional stream on one accepted connection until it closes.
async fn echo_connection(incoming: quinn::Incoming, max_bytes_per_stream: usize) {
    let Ok(connection) = incoming.await else { return };
    // `accept_bi()` errors (ApplicationClosed / ConnectionClosed / reset) mean
    // the peer is done, which ends the loop.
    while let Ok((mut send, mut recv)) = connection.accept_bi().await {
        let Ok(request) = recv.read_to_end(max_bytes_per_stream).await else { continue };
        if send.write_all(&request).await.is_err() {
            continue;
        }
        let _ = send.finish();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn quic_loopback_advertises_loopback_address_and_protocol_id() {
        let server = QuicLoopback::start(64 * 1024).await.expect("start");
        assert!(server.local_addr().ip().is_loopback(), "must bind loopback only");
        assert_ne!(server.local_addr().port(), 0, "must report a concrete port");
        assert_eq!(server.protocol_id(), "quic-echo");
        assert_eq!(server.max_bytes_per_stream(), 64 * 1024);
        server.shutdown().await.expect("clean shutdown");
    }

    #[tokio::test]
    async fn quic_loopback_round_trips_a_bidirectional_stream() {
        let server = QuicLoopback::start(64 * 1024).await.expect("start");
        let connection = server.connect().await.expect("client connects");

        let payload = b"quic-loopback-roundtrip";
        let (mut send, mut recv) = connection.open_bi().await.expect("open bi-stream");
        send.write_all(payload).await.expect("client write");
        send.finish().expect("client finish");

        let echoed = recv.read_to_end(payload.len()).await.expect("client read echo");
        assert_eq!(echoed, payload, "server must echo the payload byte-for-byte");

        drop(connection);
        server.shutdown().await.expect("clean shutdown");
    }

    #[tokio::test]
    async fn quic_loopback_serves_multiple_sequential_streams() {
        let server = QuicLoopback::start(64 * 1024).await.expect("start");
        let connection = server.connect().await.expect("client connects");

        for round in 0u32..4 {
            let payload = format!("round-{round}").into_bytes();
            let (mut send, mut recv) = connection.open_bi().await.expect("open bi-stream");
            send.write_all(&payload).await.expect("write");
            send.finish().expect("finish");
            let echoed = recv.read_to_end(payload.len()).await.expect("read echo");
            assert_eq!(echoed, payload, "stream {round} must echo");
        }

        drop(connection);
        server.shutdown().await.expect("clean shutdown");
    }
}
