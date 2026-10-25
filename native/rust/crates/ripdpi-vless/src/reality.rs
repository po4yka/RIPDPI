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
/// Reality TLS handshake. X.509 chain validation is off because the
/// server presents a temporary certificate. Authentication runs in both
/// directions: the sealed session_id that the patched BoringSSL callback
/// writes into the ClientHello authenticates the client, and the
/// post-handshake check in `reality_server_auth` authenticates the
/// server (Ed25519 leaf signed with `HMAC-SHA512(AuthKey, pubkey)`).
///
/// # Errors
///
/// Returns [`io::ErrorKind::InvalidData`] when the server does not prove
/// that it holds the REALITY private key, and other errors when the TCP or
/// TLS handshake fails.
///
/// # Cancel safety
///
/// conditionally cancel-safe: cancellation may leave a partial TLS handshake
/// at the peer, but this future exclusively owns and drops the incomplete TCP
/// stream, which is never resumed or pooled.
pub async fn connect_reality_tls(
    tcp: TcpStream,
    config: &VlessRealityConfig,
) -> io::Result<RealityTlsStream<TcpStream>> {
    connect_reality_tls_inner(tcp, config).await
}

/// Connect Reality TLS over an arbitrary async transport. Used for
/// chain-relay (VLESS-over-VLESS) and xHTTP. The server is authenticated
/// as in [`connect_reality_tls`].
///
/// # Errors
///
/// Same as [`connect_reality_tls`].
///
/// # Cancel safety
///
/// conditionally cancel-safe: cancellation may leave partial TLS bytes on the
/// supplied transport; the caller must discard it and must not resume or pool
/// it.
pub async fn connect_reality_tls_over<S>(transport: S, config: &VlessRealityConfig) -> io::Result<RealityTlsStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    connect_reality_tls_inner(transport, config).await
}

/// Puts `X25519MLKEM768:X25519` first in a BoringSSL curve list and keeps the
/// other groups in order. XTLS/REALITY (since 2026-09-08, Xray v26.9.9) sends
/// a ClientHello without an X25519MLKEM768 share ahead of the X25519 share to
/// the cover site. BoringSSL sends key shares for the first group and the
/// next group of the other kind, so this list yields exactly those two shares;
/// the separate X25519 share is the one the REALITY seal is derived from.
fn reality_curves_list(curves: &str) -> String {
    let rest = curves.split(':').filter(|group| !matches!(*group, "X25519MLKEM768" | "X25519"));
    ["X25519MLKEM768", "X25519"].into_iter().chain(rest).collect::<Vec<_>>().join(":")
}

/// # Cancel safety
///
/// conditionally cancel-safe: this function exclusively owns the in-progress
/// TLS stream. Dropping it discards that stream, and a retry starts from a
/// fresh transport; no shared TLS state is published before completion.
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

    // Curve list: the optional KEM group override, else the profile's static
    // list, with the hybrid prefix REALITY requires (applied AFTER profile
    // resolution, BEFORE `.build()`).
    let base_curves = match config.kem_groups.as_deref() {
        Some(kem_groups) => ripdpi_tls_profiles::normalize_kem_groups(kem_groups)
            .map_err(|error| io::Error::other(format!("TLS KEM groups: {error}")))?,
        None => ripdpi_tls_profiles::selected_profile_config(profile_name).curves.to_owned(),
    };
    builder
        .set_curves_list(&reality_curves_list(&base_curves))
        .map_err(|error| io::Error::other(format!("Reality TLS curves: {error}")))?;

    // REALITY servers present a temporary certificate, not the cover
    // site's chain, so X.509 chain validation is off here. The server is
    // authenticated after the handshake instead: see step 5 and
    // `reality_server_auth`.
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
    //    `ssl_add_client_hello` invocation (once in practice: a
    //    HelloRetryRequest cannot ask for a new X25519 share, so a
    //    second invocation fails) and patches the 32-byte session_id
    //    slot with the AES-256-GCM seal computed by
    //    [`reality_seal::seal_session_id_with_auth_key`]. The guard's lifetime
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
    }
    .map_err(|error| io::Error::other(format!("Reality TLS setup: {error}")))?;

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

    // 5. Authenticate the server before any VLESS byte is written. Only a
    //    server that holds the REALITY private key can derive the AuthKey
    //    and sign its temporary certificate with it. A TLS interceptor or
    //    the plain cover site fails here and never sees the UUID.
    let auth_key = handshake
        .guard
        .take_auth_key()
        .ok_or_else(|| io::Error::other("Reality AuthKey is unavailable after a successful client_hello_cb"))?;
    crate::reality_server_auth::verify_reality_server_certificate(
        tls_stream.ssl().peer_certificate().as_deref(),
        &auth_key,
    )?;

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
    use crate::reality_seal::derive_auth_key;
    use base64::Engine;
    use boring::ssl::{ClientHello, ExtensionType, SelectCertError, Ssl, SslContext, SslMethod, SslVersion};
    use ring::hmac;
    use std::future::poll_fn;
    use std::io::{self, Cursor, Write};
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

    const ED25519_PRIVATE_KEY_DER: &str =
        concat!("302e020100300506032b6570042204207c8c6497f9960d5595d7815f550569e5", "f77764ac97e63e339aaa68cc1512b683");
    const REALITY_SERVER_SECRET: [u8; 32] = [0x42; 32];

    /// How the fixture server picks its certificate.
    #[derive(Clone, Copy)]
    enum FixtureCertificate {
        /// An ordinary self-signed Ed25519 certificate, as a TLS interceptor or
        /// the cover site would present.
        PlainSelfSigned,
        /// A temporary Ed25519 certificate signed with the REALITY AuthKey that
        /// this X25519 private key derives, as a REALITY server presents.
        RealityAuth([u8; 32]),
    }

    fn fixture_context(certificate: FixtureCertificate) -> SslContext {
        let key_der = hex::decode(ED25519_PRIVATE_KEY_DER).expect("decode Ed25519 fixture key");
        let signing_key = rcgen::KeyPair::try_from(key_der.as_slice()).expect("rcgen Ed25519 fixture key");
        let params = rcgen::CertificateParams::new(vec!["reality.test".to_string()]).expect("fixture cert params");
        let template = params.self_signed(&signing_key).expect("self-signed Ed25519 fixture cert").der().to_vec();
        let key = boring::pkey::PKey::private_key_from_der(&key_der).expect("BoringSSL fixture key");
        let mut context = SslContext::builder(SslMethod::tls()).expect("fixture context");
        context.set_min_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 minimum");
        context.set_max_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 maximum");
        context.set_curves_list("X25519").expect("fixture X25519 curve");
        match certificate {
            FixtureCertificate::PlainSelfSigned => {
                let cert = boring::x509::X509::from_der(&template).expect("BoringSSL fixture cert");
                context.set_certificate(&cert).expect("fixture certificate");
                context.set_private_key(&key).expect("fixture private key");
            }
            FixtureCertificate::RealityAuth(server_secret) => {
                let public_key = signing_key.public_key_raw().to_vec();
                context.set_select_certificate_callback(move |mut hello: ClientHello<'_>| {
                    let auth_key = server_auth_key(&server_secret, &hello).ok_or(SelectCertError::ERROR)?;
                    let mut der = template.clone();
                    let signature_start = der.len() - 64;
                    let signature = hmac::sign(&hmac::Key::new(hmac::HMAC_SHA512, auth_key.as_slice()), &public_key);
                    der[signature_start..].copy_from_slice(signature.as_ref());
                    let cert = boring::x509::X509::from_der(&der).map_err(|_| SelectCertError::ERROR)?;
                    hello.ssl_mut().set_certificate(&cert).map_err(|_| SelectCertError::ERROR)?;
                    hello.ssl_mut().set_private_key(&key).map_err(|_| SelectCertError::ERROR)?;
                    Ok(())
                });
            }
        }
        context.build()
    }

    /// Server-side AuthKey: ECDH(server secret, client X25519 key share),
    /// HKDF salt = client_random[..20]. X25519 is symmetric, so the client
    /// derivation computes it with the roles swapped. This reuses the
    /// client's `derive_auth_key`, so it is not an independent oracle; the
    /// `local-network-fixture` REALITY server and the frozen Go seal vector
    /// cover the derivation independently.
    ///
    /// Like XTLS/REALITY tls.go since 2026-09-08, a ClientHello without an
    /// X25519MLKEM768 share before the X25519 share is not a REALITY client.
    fn server_auth_key(server_secret: &[u8; 32], hello: &ClientHello<'_>) -> Option<zeroize::Zeroizing<[u8; 32]>> {
        const MLKEM768_HYBRID_SHARE_LEN: usize = 1184 + 32;
        let client_random: [u8; 32] = hello.random().try_into().ok()?;
        let extension = hello.get_extension(ExtensionType::KEY_SHARE)?;
        let mut entries = extension.get(2..)?;
        let mut saw_hybrid = false;
        while entries.len() >= 4 {
            let group = u16::from_be_bytes([entries[0], entries[1]]);
            let length = usize::from(u16::from_be_bytes([entries[2], entries[3]]));
            let key = entries.get(4..4 + length)?;
            if group == 0x11ec && length == MLKEM768_HYBRID_SHARE_LEN {
                saw_hybrid = true;
            }
            if group == 0x001d {
                if !saw_hybrid {
                    return None;
                }
                let client_share: [u8; 32] = key.try_into().ok()?;
                return derive_auth_key(server_secret, &client_share, &client_random).ok();
            }
            entries = &entries[4 + length..];
        }
        None
    }

    /// Runs one REALITY handshake against a fixture server and reads its
    /// two-byte reply.
    async fn connect_to_fixture(certificate: FixtureCertificate) -> io::Result<[u8; 2]> {
        connect_to_fixture_with(certificate, "chrome_stable", Some(vec!["X25519".to_string()])).await
    }

    async fn connect_to_fixture_with(
        certificate: FixtureCertificate,
        profile: &str,
        kem_groups: Option<Vec<String>>,
    ) -> io::Result<[u8; 2]> {
        let context = fixture_context(certificate);
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind Reality TLS fixture");
        let address = listener.local_addr().expect("Reality TLS fixture address");
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept Reality TLS client");
            stream.set_read_timeout(Some(Duration::from_secs(5))).expect("fixture read timeout");
            stream.set_write_timeout(Some(Duration::from_secs(5))).expect("fixture write timeout");
            let ssl = Ssl::new(&context).expect("Reality TLS fixture SSL");
            // A client that rejects the server closes the connection after the
            // handshake, so the reply write may fail.
            if let Ok(mut tls) = ssl.accept(stream) {
                let _ = tls.write_all(b"ok");
            }
        });

        let reality_public = PublicKey::from(&StaticSecret::from(REALITY_SERVER_SECRET));
        let public_key = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(reality_public.as_bytes());
        let config = VlessRealityConfig::from_strings(
            "127.0.0.1",
            i32::from(address.port()),
            "550e8400-e29b-41d4-a716-446655440000",
            "reality.test",
            &public_key,
            "abcd1234",
            profile,
        )
        .expect("Reality TLS fixture config");
        let config = match kem_groups {
            Some(groups) => config.with_kem_groups(groups),
            None => config,
        };

        let tcp = tokio::net::TcpStream::connect(address).await.expect("connect Reality TLS fixture");
        let result = match connect_reality_tls(tcp, &config).await {
            Ok(mut tls) => {
                let mut response = [0_u8; 2];
                tls.read_exact(&mut response).await.map(|_| response)
            }
            Err(error) => Err(error),
        };
        server.join().expect("Reality TLS fixture thread");
        result
    }

    #[tokio::test]
    async fn reality_tls_accepts_server_that_proves_the_auth_key() {
        let response = connect_to_fixture(FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET))
            .await
            .expect("a REALITY server with the configured key must be accepted");
        assert_eq!(response, *b"ok");
    }

    #[tokio::test]
    async fn reality_tls_offers_hybrid_key_share_for_every_profile() {
        // firefox_stable, firefox_ech_stable and safari_stable list only
        // classical curves; current REALITY servers reject such a ClientHello.
        for profile in ["chrome_stable", "firefox_stable", "firefox_ech_stable", "safari_stable"] {
            let response =
                connect_to_fixture_with(FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET), profile, None)
                    .await
                    .unwrap_or_else(|error| panic!("{profile}: REALITY handshake failed: {error}"));
            assert_eq!(response, *b"ok", "{profile}");
        }
    }

    #[tokio::test]
    async fn reality_tls_rejects_server_with_plain_certificate() {
        let error = connect_to_fixture(FixtureCertificate::PlainSelfSigned)
            .await
            .expect_err("a TLS server without the REALITY key must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData, "{error}");
    }

    #[tokio::test]
    async fn reality_tls_rejects_server_with_another_reality_key() {
        let error = connect_to_fixture(FixtureCertificate::RealityAuth([0x24; 32]))
            .await
            .expect_err("a REALITY server with a different private key must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData, "{error}");
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
