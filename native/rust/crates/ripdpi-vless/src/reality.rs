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
use std::task::{Context, Poll};

use boring::ssl::SslVerifyMode;
use foreign_types_shared::ForeignType;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio_boring::SslStream;

use crate::config::VlessRealityConfig;
use crate::reality_hook::{SslHandle, install_reality_client_hello_hook};

/// Owns a resource and the guard that keeps the resource's callback state
/// alive. Rust drops fields in declaration order, so `resource` is always
/// destroyed before `guard`.
struct DropOrdered<R, G> {
    resource: R,
    guard: G,
}

/// A Reality TLS stream that keeps its BoringSSL ClientHello callback state
/// alive for the complete lifetime of the underlying `SSL` object.
pub struct RealityTlsStream<S> {
    owned: DropOrdered<SslStream<S>, crate::reality_hook::RealityHookGuard>,
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
    let stream_builder = tokio_boring::SslStreamBuilder::new(ssl, stream);
    let mut handshake = DropOrdered { resource: Box::pin(stream_builder.connect()), guard: hook_guard };
    let tls_stream =
        handshake.resource.as_mut().await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("Reality TLS handshake: {error}"))
        })?;

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
    use super::DropOrdered;
    use std::sync::{Arc, Mutex};

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
}
