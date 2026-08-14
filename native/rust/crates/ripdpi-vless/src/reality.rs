//! Reality TLS handshake driver.
//!
//! The session_id sealing crypto lives in [`crate::reality_seal`]
//! (audit findings C1 + C2). The bridge between BoringSSL's
//! ClientHello assembly and that crypto primitive lives in
//! [`crate::reality_hook`] (audit finding H1 — the vendored
//! BoringSSL patch). This module just composes those two halves into
//! a TLS connect entry point.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, ready};

use boring::ssl::SslVerifyMode;
use foreign_types_shared::ForeignType;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio_boring::SslStream;

use crate::config::VlessRealityConfig;
use crate::reality_hook::{SslHandle, install_reality_client_hello_hook};
use crate::vision::{XtlsDirectRead, XtlsDirectWrite};

/// Owns a resource and the guard that keeps the resource's callback state
/// alive. Rust drops fields in declaration order, so `resource` is always
/// destroyed before `guard`.
struct DropOrdered<R, G> {
    resource: R,
    guard: G,
}

const TLS_RECORD_HEADER_LEN: usize = 5;
const RECORD_READ_SCRATCH_LEN: usize = 8192;

/// Prevents BoringSSL from reading bytes beyond one complete outer TLS record.
///
/// XTLS `Direct` places raw inner-TLS bytes immediately after the final
/// Reality record. Without this boundary, BoringSSL may read those raw bytes
/// into its BIO before `VisionStream` can switch to the underlying transport,
/// permanently stranding them inside the discarded outer TLS state.
struct RealityRecordStream<S> {
    inner: S,
    header: [u8; TLS_RECORD_HEADER_LEN],
    header_filled: usize,
    record_remaining: usize,
    yield_after_record: bool,
}

impl<S> RealityRecordStream<S> {
    fn new(inner: S) -> Self {
        Self {
            inner,
            header: [0; TLS_RECORD_HEADER_LEN],
            header_filled: 0,
            record_remaining: 0,
            yield_after_record: false,
        }
    }
}

impl<S: AsyncRead + Unpin> RealityRecordStream<S> {
    fn poll_read_raw(mut self: Pin<&mut Self>, cx: &mut Context<'_>, output: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        self.yield_after_record = false;
        Pin::new(&mut self.inner).poll_read(cx, output)
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for RealityRecordStream<S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, output: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if output.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        if self.yield_after_record {
            self.yield_after_record = false;
            cx.waker().wake_by_ref();
            return Poll::Pending;
        }

        let reading_header = self.header_filled < TLS_RECORD_HEADER_LEN;
        let boundary_remaining =
            if reading_header { TLS_RECORD_HEADER_LEN - self.header_filled } else { self.record_remaining };
        let limit = boundary_remaining.min(output.remaining()).min(RECORD_READ_SCRATCH_LEN);
        let this = self.as_mut().get_mut();
        let mut scratch_bytes = [0_u8; RECORD_READ_SCRATCH_LEN];
        let mut scratch = ReadBuf::new(&mut scratch_bytes[..limit]);
        ready!(Pin::new(&mut this.inner).poll_read(cx, &mut scratch))?;
        let bytes = scratch.filled();
        if bytes.is_empty() {
            return Poll::Ready(Ok(()));
        }

        output.put_slice(bytes);
        if reading_header {
            let end = this.header_filled + bytes.len();
            this.header[this.header_filled..end].copy_from_slice(bytes);
            this.header_filled = end;
            if this.header_filled == TLS_RECORD_HEADER_LEN {
                this.record_remaining = usize::from(u16::from_be_bytes([this.header[3], this.header[4]]));
                if this.record_remaining == 0 {
                    this.header_filled = 0;
                    this.yield_after_record = true;
                }
            }
        } else {
            this.record_remaining -= bytes.len();
            if this.record_remaining == 0 {
                this.header_filled = 0;
                this.yield_after_record = true;
            }
        }
        Poll::Ready(Ok(()))
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for RealityRecordStream<S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

/// A Reality TLS stream that keeps its BoringSSL ClientHello callback state
/// alive for the complete lifetime of the underlying `SSL` object.
pub struct RealityTlsStream<S> {
    owned: DropOrdered<SslStream<RealityRecordStream<S>>, crate::reality_hook::RealityHookGuard>,
}

impl<S> AsyncRead for RealityTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.owned.resource).poll_read(cx, buf)
    }
}

impl<S> AsyncWrite for RealityTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.owned.resource).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.owned.resource).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.owned.resource).poll_shutdown(cx)
    }

    fn poll_write_vectored(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.owned.resource).poll_write_vectored(cx, bufs)
    }

    fn is_write_vectored(&self) -> bool {
        self.owned.resource.is_write_vectored()
    }
}

impl<S> XtlsDirectRead for RealityTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read_direct(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.owned.resource.get_mut()).poll_read_raw(cx, buf)
    }
}

impl<S> XtlsDirectWrite for RealityTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write_direct(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(self.owned.resource.get_mut()).poll_write(cx, buf)
    }

    fn poll_flush_direct(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.owned.resource.get_mut()).poll_flush(cx)
    }

    fn poll_shutdown_direct(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.owned.resource.get_mut()).poll_shutdown(cx)
    }
}

/// Connect to a VLESS+Reality server over TCP, performing the
/// Reality TLS handshake. Cert verification is disabled because
/// Reality uses its own auth model on top of TLS 1.3; the
/// authentication is the sealed session_id that the patched
/// BoringSSL callback writes into the ClientHello.
pub async fn connect_reality_tls(
    tcp: TcpStream,
    config: &VlessRealityConfig,
) -> io::Result<RealityTlsStream<TcpStream>> {
    connect_reality_tls_inner(tcp, config).await
}

/// Connect Reality TLS over an arbitrary async transport. Used for
/// chain-relay (VLESS-over-VLESS).
pub async fn connect_reality_tls_over<S>(transport: S, config: &VlessRealityConfig) -> io::Result<RealityTlsStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    connect_reality_tls_inner(transport, config).await
}

// NOT cancel-safe: if this future is dropped mid-handshake the partial TLS
// handshake state and the in-flight `tls_stream` are discarded; the caller must
// restart the connection from scratch rather than resume. (Telemetry only fires
// after a fully-completed handshake, so a cancelled handshake never miscounts.)
async fn connect_reality_tls_inner<S>(stream: S, config: &VlessRealityConfig) -> io::Result<RealityTlsStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // 1. Build a fresh SSL connector for this connection. Each
    //    Reality connect gets its own SSL_CTX so the
    //    `client_hello_cb` slot on the CTX is single-use.
    //
    //    Resolve the fingerprint profile ONCE per connection: when the configured
    //    profile is the `rotating` marker, this draws a fresh uTLS fingerprint
    //    from the rotation pool (per-connection JA3/JA4 rotation); otherwise it is
    //    the configured profile. The same resolved name MUST feed both the
    //    connector build and the ECH-parity decision below, so a rotated
    //    connection's ClientHello is internally consistent.
    let profile_name =
        ripdpi_tls_profiles::resolve_connection_profile(&config.tls_fingerprint_profile, &config.server_name);
    let mut builder = ripdpi_tls_profiles::configure_builder(profile_name)
        .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;

    // REALITY's authenticated server flight uses an ephemeral Ed25519
    // certificate and hard-codes Ed25519 for CertificateVerify. Some of the
    // browser profile templates intentionally prune that signature scheme, so
    // restore it only on the REALITY path without widening ordinary TLS/xHTTP.
    let profile_sigalgs = ripdpi_tls_profiles::selected_profile_config(profile_name).sigalgs;
    let reality_sigalgs = format!("{profile_sigalgs}:ed25519");
    builder
        .set_sigalgs_list(&reality_sigalgs)
        .map_err(|error| io::Error::other(format!("Reality TLS signature algorithms: {error}")))?;

    // Optional post-quantum KEM group override: replace the profile's static
    // curve list with the configured ordered group list (applied AFTER profile
    // resolution, BEFORE `.build()`).
    if let Some(kem_groups) = config.kem_groups.as_deref() {
        ripdpi_tls_profiles::apply_kem_groups(&mut builder, kem_groups)
            .map_err(|error| io::Error::other(format!("TLS KEM groups: {error}")))?;
    }

    // Reality uses its own auth model — disable standard cert
    // verification; the server will present a fake cert that we
    // intentionally do not validate.
    builder.set_verify(SslVerifyMode::NONE);

    let connector = builder.build();
    let config_ssl =
        connector.configure().map_err(|error| io::Error::other(format!("boring SSL configure: {error}")))?;

    // REALITY ECH parity (ADR 0001). REALITY never emits *real* ECH — it
    // authenticates with the visible cover `server_name` plus the sealed
    // SessionID — but may emit ECH GREASE for outer-ClientHello fingerprint
    // parity when the selected profile is ECH-capable AND the cover population
    // is known to carry ECH. No per-cover evidence table exists yet (future
    // profile-catalog data work, ADR 0001 § Consequences), so we pass
    // `Unknown`, which resolves to `Off` and preserves the documented no-ECH
    // baseline. The real-ECH facade (`configure_ech` / `resolve_outbound_ech`
    // / `prepare_ech_retry`) is intentionally NOT used on the REALITY path.
    match ripdpi_tls_profiles::reality_ech_parity(
        ripdpi_tls_profiles::selected_profile_config(profile_name),
        &config.server_name,
        ripdpi_tls_profiles::CoverEchEvidence::Unknown,
    ) {
        ripdpi_tls_profiles::RealityEchParity::Grease => config_ssl.set_enable_ech_grease(true),
        ripdpi_tls_profiles::RealityEchParity::Off => {}
    }

    let ssl = config_ssl
        .into_ssl(&config.server_name)
        .map_err(|error| io::Error::other(format!("SSL configure: {error}")))?;

    // 2. Install the Reality `client_hello_cb` hook on the SSL_CTX
    //    backing `ssl`. The callback fires once per
    //    `ssl_add_client_hello` invocation (typically once; twice
    //    if the server triggers HelloRetryRequest) and patches the
    //    32-byte session_id slot with the AES-256-GCM seal computed
    //    by [`reality_seal::seal_session_id`]. The guard's lifetime
    //    is tied to the returned RealityTlsStream; the box it owns is
    //    reclaimed only after the SSL object is destroyed.
    //
    let ssl_handle = ssl.as_ptr().cast::<SslHandle>();
    // SAFETY: `ssl_handle` is produced by `ssl.as_ptr()` and stays
    // valid for the SSL object's lifetime. The guard and every owner of
    // that SSL object are immediately paired in DropOrdered containers,
    // which destroy the SSL owner before the guard on success, failure,
    // and cancellation. `install_reality_client_hello_hook` is `unsafe fn`
    // because the caller must uphold that lifetime relationship.
    let hook_guard = unsafe {
        install_reality_client_hello_hook(ssl_handle, config.reality_public_key, config.reality_short_id.clone())
    };

    // 3. Perform the TLS handshake. The callback runs inside this
    //    await point during `ssl_add_client_hello`.
    let stream_builder = tokio_boring::SslStreamBuilder::new(ssl, RealityRecordStream::new(stream));
    let mut handshake = DropOrdered { resource: Box::pin(stream_builder.connect()), guard: hook_guard };
    let tls_stream = match handshake.resource.as_mut().await {
        Ok(stream) => stream,
        Err(_) if !handshake.guard.was_successful() => {
            return Err(io::Error::other("Reality client_hello_cb failed before the TLS flight was sent"));
        }
        Err(error) => {
            let state = error.ssl().map_or("unknown", boring::ssl::SslRef::state_string_long);
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("Reality TLS handshake failed in {state}: {error}"),
            ));
        }
    };

    // 4. Verify the callback actually ran successfully. A failure
    //    here would normally surface as a TLS handshake error
    //    above, but we double-check the latched flag so a
    //    misconfigured TLS profile (no X25519 key share offered,
    //    etc.) is surfaced with a clean diagnostic.
    if !handshake.guard.was_successful() {
        return Err(io::Error::other("Reality client_hello_cb reported failure (no X25519 key share or seal error)"));
    }

    // PQ-KEM negotiation telemetry: increments `tls.pq_kem_negotiated` iff the
    // negotiated group is the hybrid X25519MLKEM768. Privacy-safe (no authority
    // / SNI / IP in the event).
    ripdpi_tls_profiles::note_pq_kem_negotiation(tls_stream.ssl().curve());

    let DropOrdered { resource: handshake_future, guard } = handshake;
    drop(handshake_future);

    tracing::debug!("Reality TLS handshake completed");
    Ok(RealityTlsStream { owned: DropOrdered { resource: tls_stream, guard } })
}

#[cfg(test)]
mod tests {
    use super::{DropOrdered, RealityRecordStream, connect_reality_tls};
    use base64::Engine;
    use boring::ssl::{Ssl, SslContext, SslMethod, SslVersion};
    use std::future::poll_fn;
    use std::io::{Cursor, Write};
    use std::net::TcpListener;
    use std::pin::Pin;
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::Duration;
    use tokio::io::{AsyncReadExt, ReadBuf};
    use x25519_dalek::{PublicKey, StaticSecret};

    use crate::config::VlessRealityConfig;

    struct DropProbe {
        name: &'static str,
        events: Arc<Mutex<Vec<&'static str>>>,
    }

    impl Drop for DropProbe {
        fn drop(&mut self) {
            self.events.lock().expect("drop probe events mutex poisoned").push(self.name);
        }
    }

    #[test]
    fn owned_tls_stream_drops_ssl_before_callback_state() {
        let events = Arc::new(Mutex::new(Vec::new()));
        let owned = DropOrdered {
            resource: DropProbe { name: "ssl", events: Arc::clone(&events) },
            guard: DropProbe { name: "callback_state", events: Arc::clone(&events) },
        };

        drop(owned);

        assert_eq!(*events.lock().expect("drop probe events mutex poisoned"), ["ssl", "callback_state"]);
    }

    #[tokio::test]
    async fn reality_tls_accepts_required_ed25519_certificate_verify() {
        const ED25519_PRIVATE_KEY_DER: &str = concat!(
            "302e020100300506032b6570042204207c8c6497f9960d5595d7815f550569e5",
            "f77764ac97e63e339aaa68cc1512b683"
        );
        let key_der = hex::decode(ED25519_PRIVATE_KEY_DER).expect("decode Ed25519 fixture key");
        let signing_key = rcgen::KeyPair::try_from(key_der.as_slice()).expect("rcgen Ed25519 fixture key");
        let params = rcgen::CertificateParams::new(vec!["reality.test".to_string()]).expect("fixture cert params");
        let certificate = params.self_signed(&signing_key).expect("self-signed Ed25519 fixture cert");
        let cert = boring::x509::X509::from_der(certificate.der()).expect("BoringSSL fixture cert");
        let key = boring::pkey::PKey::private_key_from_der(&key_der).expect("BoringSSL fixture key");
        let mut context = SslContext::builder(SslMethod::tls()).expect("fixture context");
        context.set_min_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 minimum");
        context.set_max_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 maximum");
        context.set_curves_list("X25519").expect("fixture X25519 curve");
        context.set_certificate(&cert).expect("fixture certificate");
        context.set_private_key(&key).expect("fixture private key");
        let context = context.build();

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind Reality TLS fixture");
        let address = listener.local_addr().expect("Reality TLS fixture address");
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept Reality TLS client");
            stream.set_read_timeout(Some(Duration::from_secs(5))).expect("fixture read timeout");
            stream.set_write_timeout(Some(Duration::from_secs(5))).expect("fixture write timeout");
            let ssl = Ssl::new(&context).expect("Reality TLS fixture SSL");
            let mut tls = ssl.accept(stream).expect("Reality TLS fixture handshake");
            tls.write_all(b"ok").expect("write Reality TLS fixture response");
        });

        let reality_secret = StaticSecret::from([0x42; 32]);
        let reality_public = PublicKey::from(&reality_secret);
        let public_key = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(reality_public.as_bytes());
        let config = VlessRealityConfig::from_strings(
            "127.0.0.1",
            i32::from(address.port()),
            "550e8400-e29b-41d4-a716-446655440000",
            "reality.test",
            &public_key,
            "abcd1234",
            "chrome_stable",
        )
        .expect("Reality TLS fixture config")
        .with_kem_groups(vec!["X25519".to_string()]);

        let tcp = tokio::net::TcpStream::connect(address).await.expect("connect Reality TLS fixture");
        let mut tls =
            connect_reality_tls(tcp, &config).await.expect("Reality TLS must accept Ed25519 CertificateVerify");
        let mut response = [0_u8; 2];
        tls.read_exact(&mut response).await.expect("read Reality TLS fixture response");
        assert_eq!(response, *b"ok");
        server.join().expect("Reality TLS fixture thread");
    }

    #[tokio::test]
    async fn reality_record_reader_preserves_raw_bytes_after_direct_boundary() {
        let mut wire = vec![0x17, 0x03, 0x03, 0x00, 0x03];
        wire.extend_from_slice(b"tls");
        wire.extend_from_slice(b"raw");
        let mut stream = RealityRecordStream::new(Cursor::new(wire));

        let mut record = [0_u8; 8];
        stream.read_exact(&mut record).await.expect("read exactly one outer TLS record");
        assert_eq!(record, *b"\x17\x03\x03\x00\x03tls");

        let mut raw = [0_u8; 3];
        let mut raw_buf = ReadBuf::new(&mut raw);
        poll_fn(|cx| Pin::new(&mut stream).poll_read_raw(cx, &mut raw_buf))
            .await
            .expect("read raw bytes beneath Reality TLS");
        assert_eq!(raw_buf.filled(), b"raw", "record-bounded reads must not consume post-Direct raw bytes");
    }
}
