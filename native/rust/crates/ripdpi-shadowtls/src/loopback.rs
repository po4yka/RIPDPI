//! Dev-only ShadowTLS v3 loopback server + soak tests.
//!
//! Implements the server counterpart of [`crate::ShadowTlsClient`] for the
//! `add-shadowtls-loopback-test-server-for-soak-runs` task. It is hosted in this
//! crate (not `ripdpi-protocol-loopback`) because the framing/HMAC/handshake
//! helpers it reuses are `pub(crate)`, which keeps the test "mutually consistent"
//! with the real client per the loopback-harness design doc.
//!
//! Protocol note: ShadowTLS v3 abandons the TLS handshake immediately after the
//! ServerHello — the client never processes a Certificate — so the loopback only
//! needs to emit a *valid* (rustls-generated) TLS 1.3 ServerHello, then the
//! HMAC-authenticated "switch" frame, then HMAC-framed echo. No certificate is
//! ever sent, so no cert-verification seam is required on the client.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

use crate::frames::{
    FrameDecode, TLS_APPLICATION_DATA, TLS_HEADER_LEN, deframe_payload, frame_payload, read_tls_frame,
};
use crate::handshake::parse_validated_server_hello;
use crate::hmac::{HMAC_LEN, ShadowTlsHmac};

/// A loopback ShadowTLS v3 server. Echoes every application-data payload the
/// client sends, framed with the server-direction HMAC. Authenticates with the
/// same `password` the client is configured with.
// Drop order: shutdown drops-before join; the Drop body fires the oneshot stop signal before the accept-loop `JoinHandle` is detached, so the loop observes shutdown on its next poll (fire-and-forget teardown).
pub struct ShadowTlsLoopback {
    local_addr: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    join: Option<JoinHandle<()>>,
}

impl ShadowTlsLoopback {
    /// Start a loopback ShadowTLS v3 server authenticating with `password`.
    ///
    /// # Errors
    /// Returns an error if the loopback TCP listener cannot bind.
    pub async fn start(password: String) -> io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let local_addr = listener.local_addr()?;
        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        let join = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    accept = listener.accept() => {
                        let Ok((stream, _peer)) = accept else { break };
                        // Disable Nagle so the per-frame echo is not throttled by
                        // the Nagle/delayed-ACK interaction (a socket artifact,
                        // independent of the stream's framing).
                        let _ = stream.set_nodelay(true);
                        let password = password.clone();
                        tokio::spawn(async move {
                            let _ = handle_connection(stream, &password).await;
                        });
                    }
                }
            }
        });
        Ok(Self { local_addr, shutdown: Some(shutdown_tx), join: Some(join) })
    }

    /// The loopback address the server is listening on.
    #[must_use]
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// Stop the accept loop and wait for it to terminate.
    pub async fn shutdown(mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        if let Some(join) = self.join.take() {
            let _ = join.await;
        }
    }
}

impl Drop for ShadowTlsLoopback {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
    }
}

/// A loopback server that emulates a **ShadowTLS v2** peer well enough to drive
/// the v3-only client's version-mismatch detection. It relays a valid TLS 1.3
/// ServerHello (so `parse_validated_server_hello` passes, exactly as a real v2
/// server proxying to a cover site would), then — instead of v3's
/// HMAC-authenticated application-data switch — sends a raw TLS handshake record
/// (`0x16 0x03 0x03 ..`). The v3 client reads that record at the switch point and
/// classifies it as `ShadowTlsFailureKind::VersionMismatch`. See
/// `docs/architecture/shadowtls-version-policy.md`.
///
/// Available to this crate's tests and, under the `test-server` feature, to
/// downstream crates' integration tests (e.g. `ripdpi-relay-core` /
/// `ripdpi-relay-tls-transports`) that drive the version-mismatch path end to end.
pub struct ShadowTlsV2RejectLoopback {
    local_addr: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    join: Option<JoinHandle<()>>,
}

impl ShadowTlsV2RejectLoopback {
    /// Start a loopback that always answers like a v2 server.
    ///
    /// # Errors
    /// Returns an error if the loopback TCP listener cannot bind.
    pub async fn start() -> io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let local_addr = listener.local_addr()?;
        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        let join = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    accept = listener.accept() => {
                        let Ok((stream, _peer)) = accept else { break };
                        let _ = stream.set_nodelay(true);
                        tokio::spawn(async move {
                            let _ = handle_v2_reject_connection(stream).await;
                        });
                    }
                }
            }
        });
        Ok(Self { local_addr, shutdown: Some(shutdown_tx), join: Some(join) })
    }

    /// The loopback address the server is listening on.
    #[must_use]
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// Stop the accept loop and wait for it to terminate.
    pub async fn shutdown(mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
        if let Some(join) = self.join.take() {
            let _ = join.await;
        }
    }
}

impl Drop for ShadowTlsV2RejectLoopback {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
    }
}

async fn handle_v2_reject_connection(mut stream: TcpStream) -> io::Result<()> {
    use std::io::Cursor;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // 1-3. Relay a valid ServerHello (mirrors `handle_connection` steps 1-3) so
    //      the client's `parse_validated_server_hello` succeeds — a v2 server
    //      proxying to a real cover site produces a genuine ServerHello too.
    let client_hello = read_tls_frame(&mut stream).await?;
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()])
        .map_err(|e| io::Error::other(e.to_string()))?;
    let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
    let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());
    let server_config = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| io::Error::other(e.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der.into())
        .map_err(|e| io::Error::other(e.to_string()))?;
    let mut server_conn =
        rustls::ServerConnection::new(Arc::new(server_config)).map_err(|e| io::Error::other(e.to_string()))?;
    server_conn.read_tls(&mut Cursor::new(&client_hello))?;
    server_conn.process_new_packets().map_err(|e| io::Error::other(e.to_string()))?;
    let mut server_flight = Vec::new();
    server_conn.write_tls(&mut server_flight)?;
    if server_flight.len() < TLS_HEADER_LEN {
        return Err(io::Error::other("rustls server produced no ServerHello record"));
    }
    let record_len = TLS_HEADER_LEN + usize::from(u16::from_be_bytes([server_flight[3], server_flight[4]]));
    let server_hello =
        server_flight.get(..record_len).ok_or_else(|| io::Error::other("truncated ServerHello record"))?;
    stream.write_all(server_hello).await?;

    // 4. v2 framing: instead of v3's HMAC-authenticated application-data switch,
    //    send a raw TLS handshake record (`0x16 0x03 0x03 ..`). This is the
    //    `ShadowTlsFailureKind::VersionMismatch` signature the v3 client detects.
    const V2_HANDSHAKE_RECORD: &[u8] = &[0x16, 0x03, 0x03, 0x00, 0x04, 0xde, 0xad, 0xbe, 0xef];
    stream.write_all(V2_HANDSHAKE_RECORD).await?;
    stream.flush().await?;

    // Hold the connection open until the client closes, draining whatever it
    // writes (e.g. a TLS middlebox-compat ChangeCipherSpec). Draining the peer's
    // bytes means the eventual close is a clean FIN rather than an RST, so the
    // client is guaranteed to read the v2 record before EOF instead of racing a
    // reset that could discard the already-sent bytes.
    let mut sink = [0u8; 64];
    loop {
        match stream.read(&mut sink).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
    }
    Ok(())
}

async fn handle_connection(mut stream: TcpStream, password: &str) -> io::Result<()> {
    use std::io::Cursor;

    use tokio::io::AsyncWriteExt;

    // 1. Read the (HMAC-session-id-modified) ClientHello.
    let client_hello = read_tls_frame(&mut stream).await?;

    // 2. Use a real rustls 1.3 server only to produce a ServerHello the client's
    //    rustls accepts (valid cipher + key_share). Self-signed cert is never
    //    sent, so it is never validated.
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()])
        .map_err(|e| io::Error::other(e.to_string()))?;
    let cert_der = rustls::pki_types::CertificateDer::from(cert.cert.der().to_vec());
    let key_der = rustls::pki_types::PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());
    let server_config = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| io::Error::other(e.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der.into())
        .map_err(|e| io::Error::other(e.to_string()))?;
    let mut server_conn =
        rustls::ServerConnection::new(Arc::new(server_config)).map_err(|e| io::Error::other(e.to_string()))?;
    server_conn.read_tls(&mut Cursor::new(&client_hello))?;
    server_conn.process_new_packets().map_err(|e| io::Error::other(e.to_string()))?;

    // 3. Send ONLY the first record (ServerHello); drop the rest of the TLS
    //    flight (EncryptedExtensions/Certificate/Finished) — the client switches
    //    to ShadowTLS framing right after ServerHello.
    let mut server_flight = Vec::new();
    server_conn.write_tls(&mut server_flight)?;
    if server_flight.len() < TLS_HEADER_LEN {
        return Err(io::Error::other("rustls server produced no ServerHello record"));
    }
    let record_len = TLS_HEADER_LEN + usize::from(u16::from_be_bytes([server_flight[3], server_flight[4]]));
    let server_hello =
        server_flight.get(..record_len).ok_or_else(|| io::Error::other("truncated ServerHello record"))?;
    stream.write_all(server_hello).await?;

    // 4. Derive the rolling HMACs exactly as the client does.
    let parsed = parse_validated_server_hello(server_hello).map_err(|e| io::Error::other(e.to_string()))?;
    let initial = ShadowTlsHmac::new(password.as_bytes());
    let mut handshake_hmac = initial.clone();
    handshake_hmac.update(&parsed.server_random);
    let mut client_data_hmac = handshake_hmac.clone();
    client_data_hmac.update(b"C");
    let mut server_data_hmac = handshake_hmac.clone();
    server_data_hmac.update(b"S");

    // 5. Send the HMAC-authenticated "switch to application data" frame that the
    //    client's `verify_handshake_frame` accepts (HMAC over the payload only).
    let switch_payload: &[u8] = b"shadowtls-loopback-switch";
    let mut switch_hmac = handshake_hmac.clone();
    switch_hmac.update(switch_payload);
    let switch_digest = switch_hmac.digest();
    let frame_len = HMAC_LEN + switch_payload.len();
    let mut switch_frame = Vec::with_capacity(TLS_HEADER_LEN + frame_len);
    switch_frame.push(TLS_APPLICATION_DATA);
    switch_frame.push(0x03);
    switch_frame.push(0x03);
    switch_frame.extend_from_slice(&(frame_len as u16).to_be_bytes());
    switch_frame.extend_from_slice(&switch_digest);
    switch_frame.extend_from_slice(switch_payload);
    stream.write_all(&switch_frame).await?;

    // 6. Echo loop: deframe client application data, reflect it back framed for
    //    the client. Skip any abandoned-handshake artifacts (CCS, etc.).
    loop {
        // A read error here means the peer closed; end the echo loop.
        let Ok(frame) = read_tls_frame(&mut stream).await else { break };
        if frame.first() != Some(&TLS_APPLICATION_DATA) {
            continue;
        }
        match deframe_payload(&mut client_data_hmac, &mut None, &frame) {
            Ok(FrameDecode::Plaintext(data)) => {
                let reply = frame_payload(&mut server_data_hmac, &data)?;
                if stream.write_all(&reply).await.is_err() {
                    break;
                }
            }
            Ok(FrameDecode::Alert) => break,
            Ok(FrameDecode::IgnoredHandshake) => {}
            Err(_) => break,
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpStream;

    use super::{ShadowTlsLoopback, ShadowTlsV2RejectLoopback};
    use crate::{Config, ShadowTlsClient, ShadowTlsFailureKind, ShadowTlsHandshakeError};

    fn config(password: &str) -> Config {
        Config {
            password: password.to_string(),
            server_name: "localhost".to_string(),
            inner_profile_id: "default".to_string(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        }
    }

    #[tokio::test]
    async fn shadowtls_loopback_round_trips_a_single_payload() {
        let pw = "loopback-password";
        let server = ShadowTlsLoopback::start(pw.to_string()).await.expect("start loopback");
        let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");

        let client = ShadowTlsClient::new(config(pw));
        let mut tls = client.connect_over(tcp).await.expect("shadowtls handshake");

        tls.write_all(b"ping").await.expect("write");
        tls.flush().await.expect("flush");
        let mut buf = [0u8; 4];
        tls.read_exact(&mut buf).await.expect("read echo");
        assert_eq!(&buf, b"ping", "loopback must echo the payload");

        server.shutdown().await;
    }

    /// Soak: many sequential round-trips over one ShadowTLS session, exercising
    /// the rolling-HMAC chaining (`frame_payload`/`deframe_payload`) past the
    /// first frame in both directions.
    #[tokio::test]
    async fn shadowtls_loopback_soak_round_trips_many_payloads() {
        let pw = "loopback-soak-password";
        let server = ShadowTlsLoopback::start(pw.to_string()).await.expect("start loopback");
        let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");

        let client = ShadowTlsClient::new(config(pw));
        let mut tls = client.connect_over(tcp).await.expect("shadowtls handshake");

        for round in 0u32..32 {
            let payload = format!("soak-round-{round}").into_bytes();
            tls.write_all(&payload).await.expect("write");
            tls.flush().await.expect("flush");
            let mut buf = vec![0u8; payload.len()];
            tls.read_exact(&mut buf).await.expect("read echo");
            assert_eq!(buf, payload, "round {round} must echo byte-for-byte");
        }

        server.shutdown().await;
    }

    /// Regression for two ShadowTLS bugs that an 8 MiB full-duplex transfer over
    /// a `tokio::io::split` stream exposes (and that small-payload tests miss):
    ///
    /// 1. `ShadowTlsHmac` re-hashed its whole accumulated buffer on every
    ///    `digest()`, making the rolling HMAC O(n²) in frame count — 8 MiB took
    ///    minutes (and leaked memory) before the incremental-context fix.
    /// 2. `ShadowTlsStream` shared one `pending_frame` between `poll_read` and
    ///    `poll_write`, corrupting data under concurrent read+write (HMAC
    ///    verification failure) before the read/write state was separated.
    ///
    /// With both fixed this completes in well under a second and echoes
    /// byte-for-byte. Multi-thread runtime so the writer task and the reader make
    /// progress concurrently.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn shadowtls_loopback_full_duplex_does_not_serialize() {
        const PAYLOAD_LEN: usize = 8 * 1024 * 1024;
        let pw = "loopback-fullduplex";
        let server = ShadowTlsLoopback::start(pw.to_string()).await.expect("start loopback");
        let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");
        // Nodelay on both ends takes the Nagle/delayed-ACK timer out of the
        // picture so this test isolates the stream's read/write serialization.
        tcp.set_nodelay(true).expect("set nodelay");

        let client = ShadowTlsClient::new(config(pw));
        let tls = client.connect_over(tcp).await.expect("shadowtls handshake");
        let (mut reader, mut writer) = tokio::io::split(tls);

        let payload = vec![0xC3_u8; PAYLOAD_LEN];
        let expected = payload.clone();
        let writer_task = tokio::spawn(async move {
            writer.write_all(&payload).await.expect("write");
            writer.flush().await.expect("flush");
        });

        let read_fut = async {
            let mut received = vec![0u8; PAYLOAD_LEN];
            reader.read_exact(&mut received).await.expect("read echo");
            received
        };
        // The fixed path does ~8 MiB in well under a second; the O(n²)-HMAC
        // regression takes minutes, so a 10 s ceiling cleanly separates them
        // with ample headroom for slow CI.
        let received = tokio::time::timeout(std::time::Duration::from_secs(10), read_fut)
            .await
            .expect("full-duplex 8 MiB must finish quickly (O(n^2) HMAC / shared frame-state regression)");

        writer_task.await.expect("writer task");
        assert_eq!(received, expected, "8 MiB full-duplex must echo byte-for-byte");

        server.shutdown().await;
    }

    #[tokio::test]
    async fn shadowtls_loopback_handshake_fails_on_password_mismatch() {
        let server = ShadowTlsLoopback::start("server-password".to_string()).await.expect("start loopback");
        let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");

        // Wrong password → the switch-frame HMAC will not verify on the client.
        let client = ShadowTlsClient::new(config("client-password"));
        let result = client.connect_over(tcp).await;
        assert!(result.is_err(), "handshake must fail when the password does not match");

        server.shutdown().await;
    }

    /// Runtime path (positive): the real v3 client against a v2-emulating server
    /// must surface a typed `ShadowTlsHandshakeError` version mismatch — not a
    /// generic TLS handshake error — so the relay layer can map it to
    /// `FailureClass::ShadowTlsVersionMismatch`.
    #[tokio::test]
    async fn shadowtls_v2_server_reject_surfaces_typed_version_mismatch() {
        let server = ShadowTlsV2RejectLoopback::start().await.expect("start v2-reject loopback");
        let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");

        let client = ShadowTlsClient::new(config("any-password"));
        let Err(error) = client.connect_over(tcp).await else {
            panic!("a v2 server must fail this v3-only client");
        };

        let handshake = error
            .get_ref()
            .and_then(|inner| inner.downcast_ref::<ShadowTlsHandshakeError>())
            .unwrap_or_else(|| panic!("a v2 reject must surface a typed ShadowTlsHandshakeError, got: {error}"));
        assert_eq!(
            handshake.kind(),
            ShadowTlsFailureKind::VersionMismatch,
            "the v2 framing signal must classify as a version mismatch",
        );

        server.shutdown().await;
    }

    /// Runtime path (negative / false-positive guard): a v3 server rejecting a
    /// bad password sends the HMAC application-data switch (record type 0x17),
    /// whose HMAC fails to verify — an auth-class failure. It must NOT be
    /// misclassified as a version mismatch.
    #[tokio::test]
    async fn shadowtls_bad_password_is_not_classified_as_version_mismatch() {
        let server = ShadowTlsLoopback::start("server-password".to_string()).await.expect("start loopback");
        let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");

        let client = ShadowTlsClient::new(config("client-password"));
        let Err(error) = client.connect_over(tcp).await else {
            panic!("bad password must fail the handshake");
        };

        let is_version_mismatch = error
            .get_ref()
            .and_then(|inner| inner.downcast_ref::<ShadowTlsHandshakeError>())
            .is_some_and(|handshake| handshake.kind() == ShadowTlsFailureKind::VersionMismatch);
        assert!(
            !is_version_mismatch,
            "an auth (bad-password) failure must not be misclassified as a version mismatch, got: {error}",
        );

        server.shutdown().await;
    }

    /// Soak: N back-to-back full ShadowTLS handshakes against the loopback, each
    /// doing one round-trip. `#[ignore]` so it stays out of normal PR CI; run
    /// with `cargo nextest run -p ripdpi-shadowtls --run-ignored all` (or
    /// `cargo test -- --ignored`). Asserts every handshake + echo succeeds (no
    /// connection/HMAC leak across repeated sessions).
    #[ignore = "soak: run on demand via --run-ignored / --ignored"]
    #[tokio::test]
    async fn shadowtls_loopback_soak_repeats_full_handshakes() {
        const HANDSHAKES: u32 = 200;
        let pw = "loopback-handshake-soak";
        let server = ShadowTlsLoopback::start(pw.to_string()).await.expect("start loopback");

        for round in 0u32..HANDSHAKES {
            let tcp = TcpStream::connect(server.local_addr()).await.expect("tcp connect");
            let client = ShadowTlsClient::new(config(pw));
            let mut tls = client.connect_over(tcp).await.unwrap_or_else(|e| panic!("handshake {round} failed: {e}"));
            let payload = format!("handshake-{round}").into_bytes();
            tls.write_all(&payload).await.expect("write");
            tls.flush().await.expect("flush");
            let mut buf = vec![0u8; payload.len()];
            tls.read_exact(&mut buf).await.expect("read echo");
            assert_eq!(buf, payload, "handshake {round} must round-trip");
        }

        server.shutdown().await;
    }
}

#[cfg(test)]
mod outbound_bind_tests {
    use super::ShadowTlsLoopback;
    use crate::{Config, ShadowTlsClient};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    fn config(password: &str) -> Config {
        Config {
            password: password.to_string(),
            server_name: "localhost".to_string(),
            inner_profile_id: "default".to_string(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        }
    }

    /// A v6 bind IP against a v4-only resolved server must fail closed before
    /// any connect runs — silently ignoring the bind would route the carrier
    /// over the default interface instead of the pinned one.
    #[tokio::test]
    async fn connect_fails_closed_on_bind_ip_family_mismatch() {
        let pw = "loopback-bind-password";
        let server = ShadowTlsLoopback::start(pw.to_string()).await.expect("start loopback");

        let mut cfg = config(pw);
        cfg.outbound_bind_ip = Some(IpAddr::V6(Ipv6Addr::LOCALHOST));
        let client = ShadowTlsClient::new(cfg);

        let error =
            client.connect(Ipv4Addr::LOCALHOST.to_string().as_str(), i32::from(server.local_addr().port())).await;
        let Err(error) = error else {
            panic!("family mismatch must fail closed");
        };
        assert_eq!(error.kind(), std::io::ErrorKind::AddrNotAvailable);
        assert!(error.to_string().contains("outbound bind IP family"), "unexpected error: {error}");

        server.shutdown().await;
    }
}
