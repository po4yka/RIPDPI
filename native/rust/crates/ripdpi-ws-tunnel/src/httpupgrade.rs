//! Generic, composable HTTPUpgrade outbound transport.
//!
//! # Why this lives in `ripdpi-ws-tunnel`
//!
//! The task `add-httpupgrade-transport-crate` sketches a brand-new
//! `ripdpi-transport-httpupgrade` crate. The task's `Verify` command,
//! however, runs `cargo nextest run -p ripdpi-ws-tunnel`, and the `Scope`
//! contract restricts edits to `ripdpi-ws-tunnel/**` and
//! `ripdpi-ws-bootstrap/**` -- it does not permit registering a new
//! workspace member. This module is therefore the "generic
//! `ripdpi-transport-httpupgrade`" the epic asks for, shipped as a
//! first-class public module of `ripdpi-ws-tunnel` alongside the existing
//! generic [`crate::transport`] WebSocket module, which was added under the
//! same epic for the same reason.
//!
//! # What HTTPUpgrade is
//!
//! HTTPUpgrade (Xray / V2Fly / sing-box `httpupgrade`) is the simplest
//! HTTP-looking carrier in the ecosystem:
//!
//! 1. The client sends an HTTP/1.1 `GET` request with `Connection: Upgrade`
//!    and `Upgrade: <protocol>` (the protocol name defaults to `websocket`
//!    so the request looks like a WebSocket handshake to a DPI box, but no
//!    `Sec-WebSocket-*` headers and no RFC 6455 framing are used).
//! 2. The server replies `101 Switching Protocols`.
//! 3. The socket becomes a raw, unframed bytestream in both directions --
//!    no WebSocket binary framing, no HTTP/2 multiplexing overhead.
//!
//! # What this module provides
//!
//! * [`HttpUpgradeConfig`] -- configurable host, path, extra headers, and
//!   upgrade protocol name.
//! * [`build_upgrade_request`] -- serializes a [`HttpUpgradeConfig`] into the
//!   raw HTTP/1.1 request-bytes a client writes to the inner stream.
//! * [`parse_upgrade_response`] -- parses the server's HTTP/1.1 response
//!   head, validates the `101` status, and returns any leftover bytes that
//!   arrived in the same read as the response (the start of the raw stream).
//! * [`HttpUpgradeTransport`] -- an `AsyncRead + AsyncWrite` byte surface
//!   that performs the upgrade handshake over a caller-provided inner stream
//!   and then exposes the raw post-upgrade bytestream. The inner stream type
//!   `S` is fully generic, so TLS / uTLS lives *outside* this module:
//!   callers compose `HttpUpgradeTransport` over plain TCP, a
//!   `tokio-rustls`/`tokio-boring` stream, or the uTLS connector from
//!   `ripdpi-tls-profiles`.
//!
//! Trojan, VLESS, and VMess outbound crates can layer their own framing on
//! top because the post-upgrade surface is just bytes in, bytes out.

use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::{Buf, BytesMut};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};

/// Default `Upgrade` protocol token. `websocket` is what Xray/sing-box send
/// by default so the handshake is indistinguishable from a WebSocket upgrade
/// on the wire, even though no WebSocket framing follows.
const DEFAULT_UPGRADE_PROTOCOL: &str = "websocket";

/// Upper bound on the buffered HTTP/1.1 response head. A well-behaved server
/// replies with a tiny `101` head; anything past this is a hostile or broken
/// peer and is rejected rather than buffered unbounded.
const MAX_RESPONSE_HEAD_LEN: usize = 8 * 1024;

/// The HTTP/1.1 header terminator (`CRLF CRLF`).
const HEADER_TERMINATOR: &[u8] = b"\r\n\r\n";

/// Errors produced while building, performing, or driving the HTTPUpgrade
/// transport.
#[derive(Debug, thiserror::Error)]
pub enum HttpUpgradeError {
    /// A configured field (host, path, header name/value, protocol token) is
    /// not valid for a raw HTTP/1.1 request line or header.
    #[error("invalid HTTPUpgrade transport config: {0}")]
    InvalidConfig(String),
    /// The server's HTTP/1.1 response head could not be parsed (malformed
    /// status line, missing terminator, or oversized head).
    #[error("malformed HTTPUpgrade server response: {0}")]
    MalformedResponse(String),
    /// The server replied with a status code other than `101 Switching
    /// Protocols`.
    #[error("HTTPUpgrade server rejected upgrade: expected status 101, got {0}")]
    UnexpectedStatus(u16),
    /// The underlying inner stream produced an I/O error during the
    /// handshake.
    #[error("HTTPUpgrade transport I/O error: {0}")]
    Io(#[from] std::io::Error),
}

/// Configuration for a generic outbound HTTPUpgrade transport.
///
/// Everything here is protocol-agnostic. TLS, DNS, and socket protection are
/// all the caller's concern -- this config only describes the HTTP/1.1
/// upgrade request that runs over whatever stream the caller provides.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpUpgradeConfig {
    /// `Host` header value, e.g. `cdn.example.com`.
    host: String,
    /// Request path, e.g. `/upgrade` or `/api/v1`. Normalized to start with
    /// `/`.
    path: String,
    /// `Upgrade` protocol token. Defaults to `websocket` so the handshake
    /// blends in with WebSocket traffic; sing-box also accepts custom
    /// protocol names.
    upgrade_protocol: String,
    /// Extra request headers (provider-specific, e.g. CDN auth or
    /// `X-Real-Host`). Applied verbatim on top of the standard upgrade
    /// headers.
    extra_headers: Vec<(String, String)>,
}

impl HttpUpgradeConfig {
    /// Create a config for `host` and `path`. `path` is normalized to start
    /// with `/`. The upgrade protocol defaults to `websocket`.
    pub fn new(host: impl Into<String>, path: impl Into<String>) -> Self {
        let mut path = path.into();
        if !path.starts_with('/') {
            path.insert(0, '/');
        }
        Self {
            host: host.into(),
            path,
            upgrade_protocol: DEFAULT_UPGRADE_PROTOCOL.to_string(),
            extra_headers: Vec::new(),
        }
    }

    /// Override the `Upgrade` protocol token (default `websocket`).
    pub fn with_upgrade_protocol(mut self, protocol: impl Into<String>) -> Self {
        self.upgrade_protocol = protocol.into();
        self
    }

    /// Add a provider-specific request header. Repeated names are kept --
    /// HTTP/1.1 permits multi-valued headers.
    pub fn with_header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.extra_headers.push((name.into(), value.into()));
        self
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn path(&self) -> &str {
        &self.path
    }

    pub fn upgrade_protocol(&self) -> &str {
        &self.upgrade_protocol
    }

    /// Validate that every configured field is usable in a raw HTTP/1.1
    /// request. A field carrying CR/LF would let a hostile profile inject
    /// extra request lines (request smuggling), so reject those up front.
    fn validate(&self) -> Result<(), HttpUpgradeError> {
        check_no_control_chars("path", &self.path)?;
        check_no_control_chars("host header", &self.host)?;
        check_no_control_chars("upgrade protocol", &self.upgrade_protocol)?;
        if self.upgrade_protocol.is_empty() {
            return Err(HttpUpgradeError::InvalidConfig("upgrade protocol must not be empty".to_string()));
        }
        for (name, value) in &self.extra_headers {
            check_no_control_chars(&format!("header name `{name}`"), name)?;
            check_no_control_chars(&format!("header value for `{name}`"), value)?;
            if name.is_empty() {
                return Err(HttpUpgradeError::InvalidConfig("header name must not be empty".to_string()));
            }
        }
        Ok(())
    }
}

/// Reject any string carrying a control character. CR and LF in particular
/// would terminate the request line or a header early and let a malicious
/// profile smuggle additional HTTP lines onto the wire.
fn check_no_control_chars(field: &str, value: &str) -> Result<(), HttpUpgradeError> {
    if let Some(bad) = value.chars().find(|c| c.is_control()) {
        return Err(HttpUpgradeError::InvalidConfig(format!(
            "{field} contains a control character ({:#04x})",
            bad as u32
        )));
    }
    Ok(())
}

/// Build the raw HTTP/1.1 upgrade request bytes from a transport config.
///
/// Produces a complete request head terminated by `CRLF CRLF`:
///
/// ```text
/// GET <path> HTTP/1.1
/// Host: <host>
/// Connection: Upgrade
/// Upgrade: <protocol>
/// <extra headers...>
///
/// ```
///
/// These bytes are written verbatim to the inner stream; the caller does not
/// need an HTTP client library. Unlike WebSocket, no `Sec-WebSocket-*`
/// headers are emitted -- HTTPUpgrade deliberately skips RFC 6455 framing.
pub fn build_upgrade_request(config: &HttpUpgradeConfig) -> Result<Vec<u8>, HttpUpgradeError> {
    config.validate()?;
    let mut request = String::with_capacity(128 + config.extra_headers.len() * 32);
    request.push_str("GET ");
    request.push_str(&config.path);
    request.push_str(" HTTP/1.1\r\n");
    request.push_str("Host: ");
    request.push_str(&config.host);
    request.push_str("\r\n");
    request.push_str("Connection: Upgrade\r\n");
    request.push_str("Upgrade: ");
    request.push_str(&config.upgrade_protocol);
    request.push_str("\r\n");
    for (name, value) in &config.extra_headers {
        request.push_str(name);
        request.push_str(": ");
        request.push_str(value);
        request.push_str("\r\n");
    }
    request.push_str("\r\n");
    Ok(request.into_bytes())
}

/// A successfully parsed HTTPUpgrade server response.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpgradeResponse {
    /// Bytes that arrived in the same read as the response head but belong
    /// to the raw post-upgrade stream. A server that pipelines its first
    /// payload chunk right after the `101` head leaves these here; the
    /// transport must surface them before reading more from the socket.
    pub leftover: Vec<u8>,
}

/// Parse and validate an HTTP/1.1 upgrade response from `buffer`.
///
/// `buffer` is everything read from the inner stream so far. Returns:
///
/// * `Ok(Some(response))` -- a complete `101` response head was found; any
///   trailing bytes are returned as [`UpgradeResponse::leftover`].
/// * `Ok(None)` -- the head is not yet complete (`CRLF CRLF` not seen);
///   the caller should read more bytes and retry.
/// * `Err(_)` -- the status line is malformed, the status is not `101`, or
///   the buffered head exceeds [`MAX_RESPONSE_HEAD_LEN`].
pub fn parse_upgrade_response(buffer: &[u8]) -> Result<Option<UpgradeResponse>, HttpUpgradeError> {
    let Some(terminator) = find_subsequence(buffer, HEADER_TERMINATOR) else {
        if buffer.len() > MAX_RESPONSE_HEAD_LEN {
            return Err(HttpUpgradeError::MalformedResponse(format!(
                "response head exceeded {MAX_RESPONSE_HEAD_LEN} bytes without a CRLF CRLF terminator"
            )));
        }
        return Ok(None);
    };
    let head = &buffer[..terminator];
    let leftover = buffer[terminator + HEADER_TERMINATOR.len()..].to_vec();

    // The status line is the first CRLF-delimited line of the head.
    let status_line_end = find_subsequence(head, b"\r\n").unwrap_or(head.len());
    let status_line = std::str::from_utf8(&head[..status_line_end])
        .map_err(|_| HttpUpgradeError::MalformedResponse("status line is not valid UTF-8".to_string()))?;

    // Expected shape: `HTTP/1.1 101 Switching Protocols`.
    let mut parts = status_line.split_whitespace();
    let version = parts.next().ok_or_else(|| HttpUpgradeError::MalformedResponse("empty status line".to_string()))?;
    if !version.starts_with("HTTP/1") {
        return Err(HttpUpgradeError::MalformedResponse(format!("unexpected HTTP version `{version}` in status line")));
    }
    let status_code: u16 = parts
        .next()
        .ok_or_else(|| HttpUpgradeError::MalformedResponse("status line missing status code".to_string()))?
        .parse()
        .map_err(|_| HttpUpgradeError::MalformedResponse("status code is not a number".to_string()))?;
    if status_code != 101 {
        return Err(HttpUpgradeError::UnexpectedStatus(status_code));
    }

    Ok(Some(UpgradeResponse { leftover }))
}

/// Find the first index of `needle` within `haystack`, if any.
fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|window| window == needle)
}

/// A composable, byte-oriented outbound HTTPUpgrade transport.
///
/// Wraps a caller-provided inner stream `S`. [`HttpUpgradeTransport::connect`]
/// writes the HTTP/1.1 upgrade request, reads and validates the `101`
/// response, and then the value exposes the raw post-upgrade bytestream as
/// `AsyncRead + AsyncWrite`: writes go straight to the inner stream, reads
/// drain the inner stream (after first yielding any bytes the server
/// pipelined behind its response head).
///
/// The inner stream type `S` is fully generic, so the same transport works
/// over plain TCP or any TLS stream -- TLS lives outside this module.
pub struct HttpUpgradeTransport<S> {
    inner: S,
    /// Bytes the server pipelined behind its `101` head, not yet handed to a
    /// `poll_read` caller. Drained before reading more from `inner`.
    leftover: BytesMut,
}

impl<S> HttpUpgradeTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    /// Perform the HTTPUpgrade handshake over `inner` and return a transport
    /// exposing the raw post-upgrade bytestream.
    ///
    /// `inner` is consumed: it must already be connected (and, for `wss`-like
    /// profiles, already wrapped in TLS) -- this method only runs the
    /// HTTP/1.1 upgrade exchange on top of it.
    pub async fn connect(mut inner: S, config: &HttpUpgradeConfig) -> Result<Self, HttpUpgradeError> {
        let request = build_upgrade_request(config)?;
        inner.write_all(&request).await?;
        inner.flush().await?;

        let mut buffer = BytesMut::with_capacity(1024);
        let mut chunk = [0u8; 1024];
        loop {
            match parse_upgrade_response(&buffer)? {
                Some(response) => {
                    return Ok(Self { inner, leftover: BytesMut::from(&response.leftover[..]) });
                }
                None => {
                    let read = inner.read(&mut chunk).await?;
                    if read == 0 {
                        return Err(HttpUpgradeError::MalformedResponse(
                            "inner stream closed before a complete HTTPUpgrade response head arrived".to_string(),
                        ));
                    }
                    buffer.extend_from_slice(&chunk[..read]);
                }
            }
        }
    }

    /// Wrap an inner stream whose HTTPUpgrade handshake has already been
    /// performed elsewhere, optionally carrying `leftover` bytes that were
    /// read past the response head.
    ///
    /// Use this when the handshake response was parsed by a different layer
    /// (for example a diagnostics probe) and the raw stream plus its
    /// pipelined remainder need to be adopted as a transport.
    pub fn from_upgraded(inner: S, leftover: impl Into<Vec<u8>>) -> Self {
        Self { inner, leftover: BytesMut::from(&leftover.into()[..]) }
    }

    /// Consume the transport and return the underlying inner stream.
    ///
    /// Any not-yet-read pipelined bytes are dropped; call this only once the
    /// transport's reader has been fully drained.
    pub fn into_inner(self) -> S {
        self.inner
    }
}

impl<S> AsyncRead for HttpUpgradeTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        if !this.leftover.is_empty() {
            let take = this.leftover.len().min(buf.remaining());
            buf.put_slice(&this.leftover[..take]);
            this.leftover.advance(take);
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut this.inner).poll_read(cx, buf)
    }
}

impl<S> AsyncWrite for HttpUpgradeTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        let this = self.get_mut();
        Pin::new(&mut this.inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        Pin::new(&mut this.inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use tokio::io::duplex;

    // --- HttpUpgradeConfig --------------------------------------------------

    #[test]
    fn config_normalizes_path_to_leading_slash() {
        let cfg = HttpUpgradeConfig::new("h.example", "upgrade");
        assert_eq!(cfg.path(), "/upgrade");
        let cfg2 = HttpUpgradeConfig::new("h.example", "/already");
        assert_eq!(cfg2.path(), "/already");
    }

    #[test]
    fn config_defaults_to_websocket_upgrade_protocol() {
        let cfg = HttpUpgradeConfig::new("h.example", "/u");
        assert_eq!(cfg.upgrade_protocol(), "websocket");
    }

    #[test]
    fn config_upgrade_protocol_can_be_overridden() {
        let cfg = HttpUpgradeConfig::new("h.example", "/u").with_upgrade_protocol("ripdpi");
        assert_eq!(cfg.upgrade_protocol(), "ripdpi");
    }

    // --- build_upgrade_request ----------------------------------------------

    #[test]
    fn build_request_emits_http11_upgrade_head() {
        let cfg = HttpUpgradeConfig::new("cdn.example.com", "/api/u");
        let bytes = build_upgrade_request(&cfg).expect("build request");
        let text = String::from_utf8(bytes).expect("utf8");
        assert!(text.starts_with("GET /api/u HTTP/1.1\r\n"), "request line: {text:?}");
        assert!(text.contains("Host: cdn.example.com\r\n"));
        assert!(text.contains("Connection: Upgrade\r\n"));
        assert!(text.contains("Upgrade: websocket\r\n"));
        assert!(text.ends_with("\r\n\r\n"), "head must end with CRLF CRLF");
    }

    #[test]
    fn build_request_omits_websocket_framing_headers() {
        // HTTPUpgrade deliberately skips RFC 6455: no Sec-WebSocket-* headers.
        let cfg = HttpUpgradeConfig::new("h.example", "/u");
        let text = String::from_utf8(build_upgrade_request(&cfg).expect("build")).expect("utf8");
        assert!(!text.contains("Sec-WebSocket"), "must not emit Sec-WebSocket-* headers: {text:?}");
    }

    #[test]
    fn build_request_applies_extra_headers_in_order() {
        let cfg = HttpUpgradeConfig::new("cdn.example", "/u")
            .with_header("X-Real-Host", "edge.example.com")
            .with_header("User-Agent", "ripdpi/1.0");
        let text = String::from_utf8(build_upgrade_request(&cfg).expect("build")).expect("utf8");
        assert!(text.contains("X-Real-Host: edge.example.com\r\n"));
        assert!(text.contains("User-Agent: ripdpi/1.0\r\n"));
    }

    #[test]
    fn build_request_uses_custom_upgrade_protocol() {
        let cfg = HttpUpgradeConfig::new("h.example", "/u").with_upgrade_protocol("custom-proto");
        let text = String::from_utf8(build_upgrade_request(&cfg).expect("build")).expect("utf8");
        assert!(text.contains("Upgrade: custom-proto\r\n"));
    }

    #[test]
    fn build_request_rejects_crlf_injection_in_path() {
        let cfg = HttpUpgradeConfig::new("h.example", "/u\r\nX-Evil: 1");
        let err = build_upgrade_request(&cfg).expect_err("crlf in path must be rejected");
        assert!(matches!(err, HttpUpgradeError::InvalidConfig(_)));
    }

    #[test]
    fn build_request_rejects_crlf_injection_in_header_value() {
        let cfg = HttpUpgradeConfig::new("h.example", "/u").with_header("X-A", "ok\r\nX-Evil: 1");
        let err = build_upgrade_request(&cfg).expect_err("crlf in header value must be rejected");
        assert!(matches!(err, HttpUpgradeError::InvalidConfig(_)));
    }

    #[test]
    fn build_request_rejects_empty_upgrade_protocol() {
        let cfg = HttpUpgradeConfig::new("h.example", "/u").with_upgrade_protocol("");
        let err = build_upgrade_request(&cfg).expect_err("empty protocol must be rejected");
        assert!(matches!(err, HttpUpgradeError::InvalidConfig(_)));
    }

    // --- parse_upgrade_response ---------------------------------------------

    #[test]
    fn parse_response_accepts_101_switching_protocols() {
        let raw = b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n";
        let parsed = parse_upgrade_response(raw).expect("parse ok").expect("complete");
        assert!(parsed.leftover.is_empty());
    }

    #[test]
    fn parse_response_returns_leftover_pipelined_bytes() {
        // A server that pipelines its first payload chunk right after the head.
        let raw = b"HTTP/1.1 101 Switching Protocols\r\n\r\nFIRST-PAYLOAD";
        let parsed = parse_upgrade_response(raw).expect("parse ok").expect("complete");
        assert_eq!(parsed.leftover, b"FIRST-PAYLOAD");
    }

    #[test]
    fn parse_response_waits_for_incomplete_head() {
        let partial = b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websoc";
        assert!(parse_upgrade_response(partial).expect("parse ok").is_none());
    }

    #[test]
    fn parse_response_rejects_non_101_status() {
        let raw = b"HTTP/1.1 200 OK\r\n\r\n";
        let err = parse_upgrade_response(raw).expect_err("non-101 must be rejected");
        assert!(matches!(err, HttpUpgradeError::UnexpectedStatus(200)));
    }

    #[test]
    fn parse_response_rejects_403_forbidden() {
        let raw = b"HTTP/1.1 403 Forbidden\r\n\r\n";
        let err = parse_upgrade_response(raw).expect_err("403 must be rejected");
        assert!(matches!(err, HttpUpgradeError::UnexpectedStatus(403)));
    }

    #[test]
    fn parse_response_rejects_malformed_status_line() {
        let raw = b"NOT-HTTP garbage\r\n\r\n";
        let err = parse_upgrade_response(raw).expect_err("garbage must be rejected");
        assert!(matches!(err, HttpUpgradeError::MalformedResponse(_)));
    }

    #[test]
    fn parse_response_rejects_oversized_head_without_terminator() {
        let huge = vec![b'x'; MAX_RESPONSE_HEAD_LEN + 1];
        let err = parse_upgrade_response(&huge).expect_err("oversized head must be rejected");
        assert!(matches!(err, HttpUpgradeError::MalformedResponse(_)));
    }

    #[test]
    fn parse_response_accepts_http10_version() {
        // sing-box servers occasionally answer the upgrade with HTTP/1.0.
        let raw = b"HTTP/1.0 101 Switching Protocols\r\n\r\n";
        let parsed = parse_upgrade_response(raw).expect("parse ok").expect("complete");
        assert!(parsed.leftover.is_empty());
    }

    // --- HttpUpgradeTransport handshake + AsyncRead/AsyncWrite --------------

    /// Drive the server side of an HTTPUpgrade handshake over a duplex pair:
    /// read the client's request head, reply `101`, then echo every byte.
    async fn serve_echo_after_upgrade(mut server: tokio::io::DuplexStream) {
        let mut head = BytesMut::new();
        let mut chunk = [0u8; 256];
        loop {
            let read = server.read(&mut chunk).await.expect("server read head");
            assert_ne!(read, 0, "client closed before sending request head");
            head.extend_from_slice(&chunk[..read]);
            if find_subsequence(&head, HEADER_TERMINATOR).is_some() {
                break;
            }
        }
        server
            .write_all(b"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n\r\n")
            .await
            .expect("server write 101");
        server.flush().await.expect("server flush");
        // Echo loop.
        loop {
            match server.read(&mut chunk).await {
                Ok(0) | Err(_) => break,
                Ok(read) => {
                    if server.write_all(&chunk[..read]).await.is_err() {
                        break;
                    }
                    let _ = server.flush().await;
                }
            }
        }
    }

    #[tokio::test]
    async fn transport_round_trips_bytes_after_upgrade() {
        let (client, server) = duplex(8 * 1024);
        let server_task = tokio::spawn(serve_echo_after_upgrade(server));

        let cfg = HttpUpgradeConfig::new("cdn.example", "/u");
        let mut transport = HttpUpgradeTransport::connect(client, &cfg).await.expect("upgrade handshake");

        let payload = b"hello httpupgrade transport";
        transport.write_all(payload).await.expect("write");
        transport.flush().await.expect("flush");

        let mut got = vec![0u8; payload.len()];
        transport.read_exact(&mut got).await.expect("read echo");
        assert_eq!(&got, payload);

        transport.shutdown().await.expect("shutdown");
        server_task.await.expect("server task");
    }

    #[tokio::test]
    async fn transport_surfaces_pipelined_bytes_before_socket_reads() {
        // Server replies 101 AND its first payload chunk in a single write.
        let (client, mut server) = duplex(8 * 1024);
        // A oneshot keeps the server's `server` half alive (and thus the
        // duplex open) until the client has finished reading the pipelined
        // bytes -- without pulling in tokio's `time` feature for a sleep.
        let (release_tx, release_rx) = tokio::sync::oneshot::channel::<()>();
        let server_task = tokio::spawn(async move {
            let mut head = BytesMut::new();
            let mut chunk = [0u8; 256];
            loop {
                let read = server.read(&mut chunk).await.expect("read head");
                head.extend_from_slice(&chunk[..read]);
                if find_subsequence(&head, HEADER_TERMINATOR).is_some() {
                    break;
                }
            }
            server.write_all(b"HTTP/1.1 101 Switching Protocols\r\n\r\nPIPELINED").await.expect("write 101 + payload");
            server.flush().await.expect("flush");
            // Hold the stream open until the client signals it is done.
            let _ = release_rx.await;
        });

        let cfg = HttpUpgradeConfig::new("cdn.example", "/u");
        let mut transport = HttpUpgradeTransport::connect(client, &cfg).await.expect("handshake");

        let mut got = vec![0u8; b"PIPELINED".len()];
        transport.read_exact(&mut got).await.expect("read pipelined");
        assert_eq!(&got, b"PIPELINED");
        let _ = release_tx.send(());
        server_task.await.expect("server task");
    }

    #[tokio::test]
    async fn transport_rejects_non_101_response() {
        let (client, mut server) = duplex(8 * 1024);
        let server_task = tokio::spawn(async move {
            let mut chunk = [0u8; 256];
            let _ = server.read(&mut chunk).await;
            let _ = server.write_all(b"HTTP/1.1 502 Bad Gateway\r\n\r\n").await;
            let _ = server.flush().await;
        });

        let cfg = HttpUpgradeConfig::new("cdn.example", "/u");
        // `HttpUpgradeTransport` is not `Debug` (its inner stream need not be),
        // so match the result rather than using `expect_err`.
        match HttpUpgradeTransport::connect(client, &cfg).await {
            Ok(_) => panic!("502 must fail the handshake"),
            Err(err) => assert!(matches!(err, HttpUpgradeError::UnexpectedStatus(502))),
        }
        server_task.await.expect("server task");
    }

    #[tokio::test]
    async fn transport_rejects_early_eof_during_handshake() {
        let (client, mut server) = duplex(8 * 1024);
        // Server consumes the request head, then drops without ever sending a
        // response -> the client's read side hits a clean EOF mid-handshake.
        let server_task = tokio::spawn(async move {
            let mut head = BytesMut::new();
            let mut chunk = [0u8; 256];
            loop {
                match server.read(&mut chunk).await {
                    Ok(0) | Err(_) => break,
                    Ok(read) => {
                        head.extend_from_slice(&chunk[..read]);
                        if find_subsequence(&head, HEADER_TERMINATOR).is_some() {
                            break;
                        }
                    }
                }
            }
            // Drop `server` here without replying.
        });

        let cfg = HttpUpgradeConfig::new("cdn.example", "/u");
        match HttpUpgradeTransport::connect(client, &cfg).await {
            Ok(_) => panic!("early eof must fail the handshake"),
            Err(err) => assert!(matches!(err, HttpUpgradeError::MalformedResponse(_))),
        }
        server_task.await.expect("server task");
    }

    #[tokio::test]
    async fn from_upgraded_adopts_stream_and_leftover() {
        let (client, server) = duplex(8 * 1024);
        let server_task = tokio::spawn(async move {
            let mut server = server;
            let mut chunk = [0u8; 256];
            // Echo whatever the client writes.
            loop {
                match server.read(&mut chunk).await {
                    Ok(0) | Err(_) => break,
                    Ok(read) => {
                        if server.write_all(&chunk[..read]).await.is_err() {
                            break;
                        }
                        let _ = server.flush().await;
                    }
                }
            }
        });

        // No handshake performed here: adopt the raw stream with a leftover.
        let mut transport = HttpUpgradeTransport::from_upgraded(client, b"LEFT".to_vec());
        let mut leftover = vec![0u8; 4];
        transport.read_exact(&mut leftover).await.expect("read leftover");
        assert_eq!(&leftover, b"LEFT");

        transport.write_all(b"PING").await.expect("write");
        transport.flush().await.expect("flush");
        let mut echoed = vec![0u8; 4];
        transport.read_exact(&mut echoed).await.expect("read echo");
        assert_eq!(&echoed, b"PING");

        transport.shutdown().await.expect("shutdown");
        server_task.await.expect("server task");
    }

    /// Guard: the transport is built on the async I/O traits, so it composes
    /// over any `AsyncRead + AsyncWrite + Unpin` stream -- TLS can sit
    /// underneath without this module naming a TLS type.
    #[test]
    fn transport_requires_async_inner_stream() {
        fn _assert_async_bound<S: AsyncRead + AsyncWrite + Unpin>() {}
        _assert_async_bound::<tokio::io::DuplexStream>();
        _assert_async_bound::<tokio::net::TcpStream>();
    }

    /// Smoke test mirroring how a Trojan/VLESS/VMess outbound crate composes
    /// the transport: build config from profile-shaped fields, run the
    /// handshake over a caller-owned stream, then layer protocol bytes on top
    /// of the raw `AsyncRead + AsyncWrite` surface.
    #[tokio::test]
    async fn outbound_crate_smoke_test_layers_protocol_bytes() {
        let (client, server) = duplex(8 * 1024);
        let server_task = tokio::spawn(serve_echo_after_upgrade(server));

        // A VLESS-style profile: custom path, host header, custom protocol.
        let cfg = HttpUpgradeConfig::new("edge.example.com", "vless-hu")
            .with_header("X-Real-Host", "origin.example.com")
            .with_upgrade_protocol("websocket");
        assert_eq!(cfg.path(), "/vless-hu");

        let mut transport = HttpUpgradeTransport::connect(client, &cfg).await.expect("handshake");

        // "Protocol framing" an outbound would write: a length-prefixed blob.
        let payload = b"\x00\x04vle\x00";
        let mut framed = (payload.len() as u16).to_be_bytes().to_vec();
        framed.extend_from_slice(payload);
        transport.write_all(&framed).await.expect("write framed");
        transport.flush().await.expect("flush");

        let mut echoed = vec![0u8; framed.len()];
        transport.read_exact(&mut echoed).await.expect("read framed echo");
        assert_eq!(echoed, framed);

        transport.shutdown().await.expect("shutdown");
        server_task.await.expect("server task");
    }
}
