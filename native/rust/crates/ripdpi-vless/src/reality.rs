//! Reality TLS handshake driver.
//!
//! The session_id sealing crypto lives in [`crate::reality_seal`]
//! (audit findings C1 + C2). The bridge between BoringSSL's
//! ClientHello assembly and that crypto primitive lives in
//! [`crate::reality_hook`] (audit finding H1 — the vendored
//! BoringSSL patch). This module just composes those two halves into
//! a TLS connect entry point.

use std::io;

use boring::ssl::SslVerifyMode;
use foreign_types_shared::ForeignType;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_boring::SslStream;

use crate::config::VlessRealityConfig;
use crate::reality_hook::{install_reality_client_hello_hook, SslHandle};

/// Connect to a VLESS+Reality server over TCP, performing the
/// Reality TLS handshake. Cert verification is disabled because
/// Reality uses its own auth model on top of TLS 1.3; the
/// authentication is the sealed session_id that the patched
/// BoringSSL callback writes into the ClientHello.
pub async fn connect_reality_tls(tcp: TcpStream, config: &VlessRealityConfig) -> io::Result<SslStream<TcpStream>> {
    connect_reality_tls_inner(tcp, config).await
}

/// Connect Reality TLS over an arbitrary async transport. Used for
/// chain-relay (VLESS-over-VLESS).
pub async fn connect_reality_tls_over<S>(transport: S, config: &VlessRealityConfig) -> io::Result<SslStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    connect_reality_tls_inner(transport, config).await
}

async fn connect_reality_tls_inner<S>(stream: S, config: &VlessRealityConfig) -> io::Result<SslStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // 1. Build a fresh SSL connector for this connection. Each
    //    Reality connect gets its own SSL_CTX so the
    //    `client_hello_cb` slot on the CTX is single-use.
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;

    // Reality uses its own auth model — disable standard cert
    // verification; the server will present a fake cert that we
    // intentionally do not validate.
    builder.set_verify(SslVerifyMode::NONE);

    let connector = builder.build();
    let config_ssl =
        connector.configure().map_err(|error| io::Error::other(format!("boring SSL configure: {error}")))?;
    let ssl = config_ssl
        .into_ssl(&config.server_name)
        .map_err(|error| io::Error::other(format!("SSL configure: {error}")))?;

    // 2. Install the Reality `client_hello_cb` hook on the SSL_CTX
    //    backing `ssl`. The callback fires once per
    //    `ssl_add_client_hello` invocation (typically once; twice
    //    if the server triggers HelloRetryRequest) and patches the
    //    32-byte session_id slot with the AES-256-GCM seal computed
    //    by [`reality_seal::seal_session_id`]. The guard's lifetime
    //    is the connect future; the box it owns is reclaimed
    //    deterministically after the handshake completes.
    //
    // SAFETY: `ssl_handle` is produced by `ssl.as_ptr()` and stays
    // valid for the SSL object's lifetime; the guard returned here
    // outlives the call to `connect().await` below, which is the
    // only place the callback can fire.
    let ssl_handle = ssl.as_ptr().cast::<SslHandle>();
    let hook_guard = unsafe {
        install_reality_client_hello_hook(ssl_handle, config.reality_public_key, config.reality_short_id.clone())
    };

    // 3. Perform the TLS handshake. The callback runs inside this
    //    await point during `ssl_add_client_hello`.
    let stream_builder = tokio_boring::SslStreamBuilder::new(ssl, stream);
    let tls_stream = stream_builder
        .connect()
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("Reality TLS handshake: {error}")))?;

    // 4. Verify the callback actually ran successfully. A failure
    //    here would normally surface as a TLS handshake error
    //    above, but we double-check the latched flag so a
    //    misconfigured TLS profile (no X25519 key share offered,
    //    etc.) is surfaced with a clean diagnostic.
    if !hook_guard.was_successful() {
        return Err(io::Error::other("Reality client_hello_cb reported failure (no X25519 key share or seal error)"));
    }
    drop(hook_guard);

    tracing::debug!("Reality TLS handshake completed to {}", config.server_name);
    Ok(tls_stream)
}
