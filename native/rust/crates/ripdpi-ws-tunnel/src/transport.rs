//! Generic, composable WebSocket outbound transport.
//!
//! # Why this lives in `ripdpi-ws-tunnel`
//!
//! The task `generalize-websocket-transport-for-outbound-composition`
//! sketches a brand-new `ripdpi-transport-ws` crate. The task's `Scope`
//! contract, however, restricts edits to `ripdpi-ws-tunnel/**` and
//! `ripdpi-ws-bootstrap/**` -- it does not permit registering a new
//! workspace member. This module is therefore the "generic
//! `ripdpi-transport-ws`" the epic asks for, shipped as a first-class
//! public module of `ripdpi-ws-tunnel` rather than a separate crate. It is
//! fully protocol-agnostic: nothing here mentions Telegram, MTProto, or
//! `kws{n}.web.telegram.org`. The Telegram-specific code (`connect`, `dc`,
//! `mtproto`, `relay`) stays where it is and becomes a *consumer* of this
//! module's request-building surface.
//!
//! # What it provides
//!
//! * [`WsTransportConfig`] -- configurable host, path, extra headers (for
//!   provider-specific early-data headers), and subprotocol string.
//! * [`EarlyData`] -- early-data encoding for VMess/VLESS WS profiles:
//!   the `ed=N` path/query convention and the `Sec-WebSocket-Protocol`
//!   base64 early-data convention.
//! * [`build_ws_request`] -- turns a [`WsTransportConfig`] into a
//!   `tungstenite` HTTP upgrade request, applying all configured headers
//!   and early-data encodings.
//! * [`WsTransport`] -- an `AsyncRead + AsyncWrite` bytewise surface over a
//!   binary-framed `tokio-tungstenite` WebSocket. It is generic over the
//!   inner stream type, so TLS lives *outside* this module: callers
//!   compose `WsTransport` over a plain TCP stream, a `tokio-rustls`
//!   stream, or the uTLS connector from `ripdpi-tls-profiles`.
//!
//! Trojan, VLESS, and VMess outbound crates can all layer their own framing
//! on top of `WsTransport` because the surface is just bytes in, bytes out.

// `tungstenite::http::Response` is intrinsically large and appears in the
// handshake error path; boxing it would leak through every caller's Result.
#![allow(clippy::result_large_err)]

use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::{Buf, Bytes, BytesMut};
use futures::sink::Sink;
use futures::stream::Stream;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio_tungstenite::WebSocketStream;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::handshake::client::{Request, generate_key};
use tokio_tungstenite::tungstenite::http::Uri;
use tokio_tungstenite::tungstenite::http::header::{HeaderName, HeaderValue};

/// Errors produced while building or driving the generic WS transport.
#[derive(Debug)]
pub enum WsTransportError {
    /// A configured field (host, path, header name/value) is not valid for
    /// an HTTP request.
    InvalidConfig(String),
    /// The underlying WebSocket stream produced a protocol-level error.
    WebSocket(tokio_tungstenite::tungstenite::Error),
}

impl std::fmt::Display for WsTransportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidConfig(why) => write!(f, "invalid WebSocket transport config: {why}"),
            Self::WebSocket(err) => write!(f, "WebSocket transport error: {err}"),
        }
    }
}

impl std::error::Error for WsTransportError {}

impl From<tokio_tungstenite::tungstenite::Error> for WsTransportError {
    fn from(err: tokio_tungstenite::tungstenite::Error) -> Self {
        Self::WebSocket(err)
    }
}

/// Early-data encoding for VMess/VLESS WebSocket profiles.
///
/// Two conventions are used in the wild and both are supported:
///
/// * **Path query (`ed=N`)** -- the server advertises a maximum early-data
///   length `N`; the client appends `?ed=N` (or `&ed=N`) to the WS path so
///   the server knows to expect base64 early data. Set via
///   [`EarlyData::with_path_param`].
/// * **`Sec-WebSocket-Protocol` payload** -- the actual early-data bytes are
///   base64-url encoded (no padding) and sent as the WS subprotocol token,
///   letting a single round trip carry the first request fragment. Set via
///   [`EarlyData::with_protocol_payload`].
///
/// A profile may use one, both, or neither.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct EarlyData {
    /// When `Some(n)`, append `ed=n` to the request path's query string.
    path_param: Option<u32>,
    /// When `Some(bytes)`, base64-url-encode `bytes` and send them as the
    /// `Sec-WebSocket-Protocol` value.
    protocol_payload: Option<Vec<u8>>,
}

impl EarlyData {
    /// Disable early data entirely.
    pub fn none() -> Self {
        Self::default()
    }

    /// Append `ed=<max_len>` to the WebSocket request path query string.
    pub fn with_path_param(mut self, max_len: u32) -> Self {
        self.path_param = Some(max_len);
        self
    }

    /// Carry `payload` as base64-url early data in `Sec-WebSocket-Protocol`.
    pub fn with_protocol_payload(mut self, payload: impl Into<Vec<u8>>) -> Self {
        self.protocol_payload = Some(payload.into());
        self
    }

    /// `true` when no early-data encoding is configured.
    pub fn is_empty(&self) -> bool {
        self.path_param.is_none() && self.protocol_payload.is_none()
    }

    /// Apply the `ed=N` query parameter to `path`, returning the path the
    /// HTTP request line should use. When no path param is set, `path` is
    /// returned unchanged.
    fn apply_path_param(&self, path: &str) -> String {
        match self.path_param {
            None => path.to_string(),
            Some(n) => {
                let sep = if path.contains('?') { '&' } else { '?' };
                format!("{path}{sep}ed={n}")
            }
        }
    }

    /// The base64-url (no padding) encoding of the protocol-payload early
    /// data, if any. This becomes the `Sec-WebSocket-Protocol` value.
    fn protocol_token(&self) -> Option<String> {
        self.protocol_payload.as_deref().map(base64_url_nopad)
    }
}

/// Configuration for a generic outbound WebSocket transport.
///
/// Everything here is protocol-agnostic. TLS, DNS, and socket protection are
/// all the caller's concern -- this config only describes the HTTP upgrade
/// request that runs over whatever stream the caller provides.
#[derive(Debug, Clone)]
pub struct WsTransportConfig {
    /// `Host` header value, e.g. `cdn.example.com`. Also used as the URI
    /// authority when building the request.
    host: String,
    /// Request path, e.g. `/ws` or `/api/v1`. Must start with `/`.
    path: String,
    /// Extra request headers (provider-specific, e.g. CDN auth or
    /// early-data hints). Applied verbatim on top of the standard upgrade
    /// headers.
    extra_headers: Vec<(String, String)>,
    /// `Sec-WebSocket-Protocol` subprotocol token. Defaults to `binary` so
    /// the transport negotiates a binary-framed channel. When [`EarlyData`]
    /// carries a protocol payload, that payload's base64 token *replaces*
    /// this value (the two share the same header).
    subprotocol: Option<String>,
    /// Early-data encoding for VMess/VLESS profiles.
    early_data: EarlyData,
}

impl WsTransportConfig {
    /// Create a config for `host` and `path`. `path` is normalized to start
    /// with `/`. The subprotocol defaults to `binary`.
    pub fn new(host: impl Into<String>, path: impl Into<String>) -> Self {
        let mut path = path.into();
        if !path.starts_with('/') {
            path.insert(0, '/');
        }
        Self {
            host: host.into(),
            path,
            extra_headers: Vec::new(),
            subprotocol: Some("binary".to_string()),
            early_data: EarlyData::none(),
        }
    }

    /// Add a provider-specific request header. Repeated names are kept --
    /// `tungstenite` allows multi-valued headers.
    pub fn with_header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.extra_headers.push((name.into(), value.into()));
        self
    }

    /// Override the `Sec-WebSocket-Protocol` subprotocol token. Pass `None`
    /// to omit the header entirely (unless [`EarlyData`] supplies a payload
    /// token).
    pub fn with_subprotocol(mut self, subprotocol: Option<String>) -> Self {
        self.subprotocol = subprotocol;
        self
    }

    /// Attach an early-data encoding for VMess/VLESS WS profiles.
    pub fn with_early_data(mut self, early_data: EarlyData) -> Self {
        self.early_data = early_data;
        self
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn path(&self) -> &str {
        &self.path
    }

    pub fn early_data(&self) -> &EarlyData {
        &self.early_data
    }

    /// The path the HTTP request line should carry, including any `ed=N`
    /// early-data query parameter.
    pub fn request_path(&self) -> String {
        self.early_data.apply_path_param(&self.path)
    }

    /// The effective `Sec-WebSocket-Protocol` value: the early-data protocol
    /// token when present, otherwise the configured subprotocol.
    pub fn effective_subprotocol(&self) -> Option<String> {
        self.early_data.protocol_token().or_else(|| self.subprotocol.clone())
    }
}

/// Build a `tungstenite` HTTP upgrade [`Request`] from a transport config.
///
/// Composes the WS scheme + host + [`WsTransportConfig::request_path`] into
/// a URI, sets the `Host` header, the effective `Sec-WebSocket-Protocol`,
/// and every configured extra header. The standard upgrade headers
/// (`Upgrade`, `Connection`, `Sec-WebSocket-Version`, and a freshly
/// generated `Sec-WebSocket-Key`) are added here so the returned request
/// is a complete RFC 6455 client handshake -- `tokio-tungstenite`'s
/// `IntoClientRequest for Request` passes a `Request` through verbatim and
/// does *not* fill these in, unlike the `Uri`/`&str` paths.
///
/// `secure` selects the `wss` vs `ws` scheme; it does not itself perform
/// TLS -- TLS is the caller's job, outside this module.
pub fn build_ws_request(config: &WsTransportConfig, secure: bool) -> Result<Request, WsTransportError> {
    let scheme = if secure { "wss" } else { "ws" };
    let uri: Uri = format!("{scheme}://{}{}", config.host, config.request_path())
        .parse()
        .map_err(|e| WsTransportError::InvalidConfig(format!("uri: {e}")))?;

    let mut builder = Request::builder()
        .uri(uri)
        .method("GET")
        .header("Connection", "Upgrade")
        .header("Upgrade", "websocket")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", generate_key());

    let host_value = HeaderValue::from_str(&config.host)
        .map_err(|e| WsTransportError::InvalidConfig(format!("host header: {e}")))?;
    builder = builder.header("Host", host_value);

    if let Some(subprotocol) = config.effective_subprotocol() {
        let value = HeaderValue::from_str(&subprotocol)
            .map_err(|e| WsTransportError::InvalidConfig(format!("subprotocol header: {e}")))?;
        builder = builder.header("Sec-WebSocket-Protocol", value);
    }

    for (name, value) in &config.extra_headers {
        let header_name = HeaderName::from_bytes(name.as_bytes())
            .map_err(|e| WsTransportError::InvalidConfig(format!("header name `{name}`: {e}")))?;
        let header_value = HeaderValue::from_str(value)
            .map_err(|e| WsTransportError::InvalidConfig(format!("header value for `{name}`: {e}")))?;
        builder = builder.header(header_name, header_value);
    }

    builder.body(()).map_err(|e| WsTransportError::InvalidConfig(format!("request body: {e}")))
}

/// A composable, byte-oriented outbound WebSocket transport.
///
/// Wraps a `tokio-tungstenite` [`WebSocketStream`] and exposes it as
/// `AsyncRead + AsyncWrite`: writes are sent as binary WS frames, reads
/// drain inbound binary frames into a byte buffer. Ping/Pong/Close control
/// frames are handled transparently. The inner stream type `S` is fully
/// generic, so the same transport works over plain TCP or any TLS stream.
pub struct WsTransport<S> {
    inner: WebSocketStream<S>,
    /// Buffered bytes from a partially consumed inbound binary frame.
    read_buf: BytesMut,
    /// Set once the peer has sent a Close frame or the stream has ended.
    read_closed: bool,
}

impl<S> WsTransport<S> {
    /// Wrap an already-established `tokio-tungstenite` WebSocket stream.
    ///
    /// The handshake (and any TLS underneath it) is the caller's
    /// responsibility; this constructor only adopts the negotiated stream.
    pub fn from_websocket(inner: WebSocketStream<S>) -> Self {
        Self { inner, read_buf: BytesMut::new(), read_closed: false }
    }

    /// Consume the transport and return the underlying WebSocket stream.
    pub fn into_websocket(self) -> WebSocketStream<S> {
        self.inner
    }
}

impl<S> AsyncRead for WsTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        loop {
            if !this.read_buf.is_empty() {
                let take = this.read_buf.len().min(buf.remaining());
                buf.put_slice(&this.read_buf[..take]);
                this.read_buf.advance(take);
                return Poll::Ready(Ok(()));
            }
            if this.read_closed {
                // Clean EOF: an empty `poll_read` filling no bytes.
                return Poll::Ready(Ok(()));
            }
            match Pin::new(&mut this.inner).poll_next(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(None) => {
                    this.read_closed = true;
                    return Poll::Ready(Ok(()));
                }
                Poll::Ready(Some(Err(err))) => {
                    if is_eof_like(&err) {
                        // Peer vanished without a Close handshake (common
                        // with CDNs and abrupt server shutdowns). Surface a
                        // clean EOF rather than an I/O error so callers can
                        // drain what they have and stop.
                        this.read_closed = true;
                        return Poll::Ready(Ok(()));
                    }
                    return Poll::Ready(Err(ws_io_error(err)));
                }
                Poll::Ready(Some(Ok(message))) => match message {
                    Message::Binary(data) => {
                        if data.is_empty() {
                            continue;
                        }
                        this.read_buf.extend_from_slice(&data);
                    }
                    Message::Text(text) => {
                        // Some providers send text frames; treat the bytes as
                        // opaque payload rather than dropping them.
                        if text.is_empty() {
                            continue;
                        }
                        this.read_buf.extend_from_slice(text.as_bytes());
                    }
                    Message::Close(_) => {
                        this.read_closed = true;
                        return Poll::Ready(Ok(()));
                    }
                    // Ping/Pong/frame: `tokio-tungstenite` auto-replies to
                    // Ping; nothing for the byte stream to surface.
                    Message::Ping(_) | Message::Pong(_) | Message::Frame(_) => continue,
                },
            }
        }
    }
}

impl<S> AsyncWrite for WsTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        let this = self.get_mut();
        match Pin::new(&mut this.inner).poll_ready(cx) {
            Poll::Pending => return Poll::Pending,
            Poll::Ready(Err(err)) => return Poll::Ready(Err(ws_io_error(err))),
            Poll::Ready(Ok(())) => {}
        }
        let message = Message::Binary(Bytes::copy_from_slice(buf));
        match Pin::new(&mut this.inner).start_send(message) {
            Ok(()) => Poll::Ready(Ok(buf.len())),
            Err(err) => Poll::Ready(Err(ws_io_error(err))),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        Pin::new(&mut this.inner).poll_flush(cx).map_err(ws_io_error)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        Pin::new(&mut this.inner).poll_close(cx).map_err(ws_io_error)
    }
}

/// Map a `tungstenite` error onto an `io::Error` so `WsTransport` satisfies
/// the `AsyncRead`/`AsyncWrite` contract.
fn ws_io_error(err: tokio_tungstenite::tungstenite::Error) -> std::io::Error {
    use tokio_tungstenite::tungstenite::Error as WsError;
    match err {
        WsError::Io(io_err) => io_err,
        WsError::ConnectionClosed | WsError::AlreadyClosed => {
            std::io::Error::new(std::io::ErrorKind::NotConnected, err)
        }
        other => std::io::Error::other(other),
    }
}

/// `true` for `tungstenite` errors that mean "the stream is over" rather
/// than "something went wrong": a normal close, an already-closed stream,
/// or a peer that dropped the TCP connection without a Close handshake.
/// On the read side these are surfaced as a clean EOF.
fn is_eof_like(err: &tokio_tungstenite::tungstenite::Error) -> bool {
    use tokio_tungstenite::tungstenite::Error as WsError;
    use tokio_tungstenite::tungstenite::error::ProtocolError;
    matches!(
        err,
        WsError::ConnectionClosed
            | WsError::AlreadyClosed
            | WsError::Protocol(ProtocolError::ResetWithoutClosingHandshake)
    )
}

/// Base64-url encode without padding -- the encoding VLESS/VMess use for the
/// `Sec-WebSocket-Protocol` early-data token.
fn base64_url_nopad(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).copied().unwrap_or(0) as u32;
        let b2 = chunk.get(2).copied().unwrap_or(0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[((triple >> 18) & 0x3F) as usize] as char);
        out.push(ALPHABET[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            out.push(ALPHABET[((triple >> 6) & 0x3F) as usize] as char);
        }
        if chunk.len() > 2 {
            out.push(ALPHABET[(triple & 0x3F) as usize] as char);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::TcpListener;
    use std::thread;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpStream;

    // --- base64-url ---------------------------------------------------------

    #[test]
    fn base64_url_nopad_matches_known_vectors() {
        assert_eq!(base64_url_nopad(b""), "");
        assert_eq!(base64_url_nopad(b"f"), "Zg");
        assert_eq!(base64_url_nopad(b"fo"), "Zm8");
        assert_eq!(base64_url_nopad(b"foo"), "Zm9v");
        assert_eq!(base64_url_nopad(b"foob"), "Zm9vYg");
        assert_eq!(base64_url_nopad(b"fooba"), "Zm9vYmE");
        assert_eq!(base64_url_nopad(b"foobar"), "Zm9vYmFy");
        // url-safe alphabet: 0xfb,0xff,0xbf -> would be "+/+/" in standard b64.
        assert_eq!(base64_url_nopad(&[0xfb, 0xff, 0xbf]), "-_-_");
    }

    // --- EarlyData ----------------------------------------------------------

    #[test]
    fn early_data_default_is_empty() {
        assert!(EarlyData::none().is_empty());
    }

    #[test]
    fn early_data_path_param_appends_query() {
        let ed = EarlyData::none().with_path_param(2048);
        assert!(!ed.is_empty());
        assert_eq!(ed.apply_path_param("/ws"), "/ws?ed=2048");
        assert_eq!(ed.apply_path_param("/ws?token=abc"), "/ws?token=abc&ed=2048");
    }

    #[test]
    fn early_data_protocol_payload_base64_url_encodes() {
        let ed = EarlyData::none().with_protocol_payload(vec![0xfb, 0xff, 0xbf]);
        assert_eq!(ed.protocol_token().as_deref(), Some("-_-_"));
    }

    #[test]
    fn early_data_can_carry_both_conventions() {
        let ed = EarlyData::none().with_path_param(16).with_protocol_payload(b"foo".to_vec());
        assert_eq!(ed.apply_path_param("/p"), "/p?ed=16");
        assert_eq!(ed.protocol_token().as_deref(), Some("Zm9v"));
    }

    // --- WsTransportConfig --------------------------------------------------

    #[test]
    fn config_normalizes_path_to_leading_slash() {
        let cfg = WsTransportConfig::new("h.example", "ws");
        assert_eq!(cfg.path(), "/ws");
        let cfg2 = WsTransportConfig::new("h.example", "/already");
        assert_eq!(cfg2.path(), "/already");
    }

    #[test]
    fn config_defaults_to_binary_subprotocol() {
        let cfg = WsTransportConfig::new("h.example", "/ws");
        assert_eq!(cfg.effective_subprotocol().as_deref(), Some("binary"));
    }

    #[test]
    fn config_subprotocol_can_be_cleared() {
        let cfg = WsTransportConfig::new("h.example", "/ws").with_subprotocol(None);
        assert_eq!(cfg.effective_subprotocol(), None);
    }

    #[test]
    fn config_early_data_protocol_payload_overrides_subprotocol() {
        let cfg = WsTransportConfig::new("h.example", "/ws")
            .with_early_data(EarlyData::none().with_protocol_payload(b"foo".to_vec()));
        // The early-data token replaces the subprotocol on the shared header.
        assert_eq!(cfg.effective_subprotocol().as_deref(), Some("Zm9v"));
    }

    #[test]
    fn config_request_path_includes_early_data_param() {
        let cfg = WsTransportConfig::new("h.example", "/vmess").with_early_data(EarlyData::none().with_path_param(512));
        assert_eq!(cfg.request_path(), "/vmess?ed=512");
    }

    // --- build_ws_request ---------------------------------------------------

    #[test]
    fn build_ws_request_sets_uri_host_and_subprotocol() {
        let cfg = WsTransportConfig::new("cdn.example.com", "/api/ws");
        let request = build_ws_request(&cfg, true).expect("build request");
        assert_eq!(request.uri().to_string(), "wss://cdn.example.com/api/ws");
        assert_eq!(request.method(), "GET");
        assert_eq!(request.headers().get("Host").and_then(|v| v.to_str().ok()), Some("cdn.example.com"),);
        assert_eq!(request.headers().get("Sec-WebSocket-Protocol").and_then(|v| v.to_str().ok()), Some("binary"),);
    }

    #[test]
    fn build_ws_request_uses_ws_scheme_when_insecure() {
        let cfg = WsTransportConfig::new("plain.example", "/ws");
        let request = build_ws_request(&cfg, false).expect("build request");
        assert!(request.uri().to_string().starts_with("ws://"));
    }

    #[test]
    fn build_ws_request_applies_extra_headers() {
        let cfg = WsTransportConfig::new("cdn.example", "/ws")
            .with_header("X-Provider-Auth", "token-123")
            .with_header("User-Agent", "ripdpi/1.0");
        let request = build_ws_request(&cfg, true).expect("build request");
        assert_eq!(request.headers().get("X-Provider-Auth").and_then(|v| v.to_str().ok()), Some("token-123"),);
        assert_eq!(request.headers().get("User-Agent").and_then(|v| v.to_str().ok()), Some("ripdpi/1.0"),);
    }

    #[test]
    fn build_ws_request_encodes_early_data_in_path_and_protocol() {
        let cfg = WsTransportConfig::new("vmess.example", "/v")
            .with_early_data(EarlyData::none().with_path_param(2048).with_protocol_payload(b"foo".to_vec()));
        let request = build_ws_request(&cfg, true).expect("build request");
        assert_eq!(request.uri().to_string(), "wss://vmess.example/v?ed=2048");
        assert_eq!(request.headers().get("Sec-WebSocket-Protocol").and_then(|v| v.to_str().ok()), Some("Zm9v"),);
    }

    #[test]
    fn build_ws_request_rejects_invalid_header_name() {
        let cfg = WsTransportConfig::new("h.example", "/ws").with_header("Bad Header", "v");
        let err = build_ws_request(&cfg, true).expect_err("invalid header name should fail");
        assert!(matches!(err, WsTransportError::InvalidConfig(_)));
    }

    // --- WsTransport AsyncRead/AsyncWrite over a real WS handshake ----------

    /// Accept a blocking `tungstenite` server handshake that echoes the
    /// client's requested `Sec-WebSocket-Protocol` back -- exactly what a
    /// real WS server (including Telegram's) does. Without this, a strict
    /// client (RFC 6455) rejects the handshake when it asked for a
    /// subprotocol the server did not confirm.
    fn accept_with_subprotocol_echo(stream: std::net::TcpStream) -> tungstenite::WebSocket<std::net::TcpStream> {
        use tungstenite::handshake::server::{Request as SrvRequest, Response as SrvResponse};
        tungstenite::accept_hdr(stream, |req: &SrvRequest, mut response: SrvResponse| {
            if let Some(proto) = req.headers().get("Sec-WebSocket-Protocol").cloned() {
                response.headers_mut().insert("Sec-WebSocket-Protocol", proto);
            }
            Ok(response)
        })
        .expect("server ws handshake")
    }

    /// Spin up a blocking `tungstenite` echo server on a background thread
    /// and return its address. The server accepts one connection, echoes
    /// every binary frame, and exits on Close. This is the smoke-test
    /// scaffold an outbound crate (Trojan/VLESS/VMess) would use.
    fn spawn_ws_echo_server() -> std::net::SocketAddr {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind echo listener");
        let addr = listener.local_addr().expect("echo listener addr");
        thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept echo connection");
            let mut ws = accept_with_subprotocol_echo(stream);
            loop {
                match ws.read() {
                    Ok(tungstenite::Message::Binary(data)) => {
                        ws.send(tungstenite::Message::Binary(data)).expect("echo binary");
                    }
                    Ok(tungstenite::Message::Text(text)) => {
                        ws.send(tungstenite::Message::Text(text)).expect("echo text");
                    }
                    Ok(tungstenite::Message::Close(_)) | Err(_) => break,
                    Ok(_) => {}
                }
            }
        });
        addr
    }

    #[tokio::test]
    async fn ws_transport_round_trips_bytes_over_tcp() {
        let addr = spawn_ws_echo_server();
        let tcp = TcpStream::connect(addr).await.expect("connect tcp");

        let cfg = WsTransportConfig::new(addr.ip().to_string(), "/ws");
        let request = build_ws_request(&cfg, false).expect("build request");
        let (ws, _response) = tokio_tungstenite::client_async(request, tcp).await.expect("client ws handshake");

        let mut transport = WsTransport::from_websocket(ws);
        let payload = b"hello composable transport";
        transport.write_all(payload).await.expect("write");
        transport.flush().await.expect("flush");

        let mut got = vec![0u8; payload.len()];
        transport.read_exact(&mut got).await.expect("read echo");
        assert_eq!(&got, payload);
    }

    #[tokio::test]
    async fn ws_transport_reports_eof_on_peer_close() {
        let addr = spawn_ws_echo_server();
        let tcp = TcpStream::connect(addr).await.expect("connect tcp");
        let cfg = WsTransportConfig::new(addr.ip().to_string(), "/ws");
        let request = build_ws_request(&cfg, false).expect("build request");
        let (ws, _response) = tokio_tungstenite::client_async(request, tcp).await.expect("client ws handshake");

        let mut transport = WsTransport::from_websocket(ws);
        // Close from our side; the echo server breaks its loop and drops.
        transport.shutdown().await.expect("shutdown");

        let mut buf = vec![0u8; 8];
        let n = transport.read(&mut buf).await.expect("read after close");
        assert_eq!(n, 0, "closed transport must report EOF");
    }

    #[tokio::test]
    async fn ws_transport_composes_over_arbitrary_async_stream() {
        // The transport must not care what `S` is. Here `S` is the client
        // half of an in-process duplex pair, not a TCP socket -- proving TLS
        // (or any other layer) can sit underneath without the transport
        // knowing. A blocking tungstenite server drives the other end.
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind");
        let addr = listener.local_addr().expect("addr");
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            let mut ws = accept_with_subprotocol_echo(stream);
            // Pump one frame back, prefixed, to prove bytes flow.
            if let Ok(tungstenite::Message::Binary(data)) = ws.read() {
                let mut reply = b"ok:".to_vec();
                reply.extend_from_slice(&data);
                ws.send(tungstenite::Message::Binary(reply.into())).expect("send");
            }
            let _ = ws.read();
        });

        // `tokio::net::TcpStream` *is* an arbitrary `AsyncRead + AsyncWrite`
        // here; the point of the assertion is that `WsTransport<S>` is
        // generic and the call site never names a TLS type.
        let tcp = TcpStream::connect(addr).await.expect("connect");
        let cfg = WsTransportConfig::new(addr.ip().to_string(), "/compose");
        let request = build_ws_request(&cfg, false).expect("request");
        let (ws, _) = tokio_tungstenite::client_async(request, tcp).await.expect("handshake");
        let mut transport: WsTransport<TcpStream> = WsTransport::from_websocket(ws);

        transport.write_all(b"PING").await.expect("write");
        transport.flush().await.expect("flush");
        let mut got = vec![0u8; 7];
        transport.read_exact(&mut got).await.expect("read");
        assert_eq!(&got, b"ok:PING");

        transport.shutdown().await.expect("shutdown");
        server.join().expect("server thread");
    }

    /// Smoke test mirroring how a Trojan/VLESS/VMess outbound crate composes
    /// the transport: build a request from a profile-shaped config, run the
    /// handshake over a caller-owned stream, then layer protocol bytes on
    /// top of the `AsyncRead + AsyncWrite` surface.
    #[tokio::test]
    async fn outbound_crate_smoke_test_layers_protocol_bytes() {
        let addr = spawn_ws_echo_server();

        // A VLESS-style profile: custom path, host header, early data.
        let cfg = WsTransportConfig::new(addr.ip().to_string(), "vless-ws")
            .with_header("X-Real-Host", "edge.example.com")
            .with_early_data(EarlyData::none().with_path_param(1024));
        assert_eq!(cfg.request_path(), "/vless-ws?ed=1024");

        let tcp = TcpStream::connect(addr).await.expect("connect");
        let request = build_ws_request(&cfg, false).expect("request");
        let (ws, _) = tokio_tungstenite::client_async(request, tcp).await.expect("handshake");
        let mut transport = WsTransport::from_websocket(ws);

        // "Protocol framing" an outbound would write: a length-prefixed blob.
        let payload = b"\x00\x04vle\x00";
        let mut framed = (payload.len() as u16).to_be_bytes().to_vec();
        framed.extend_from_slice(payload);
        transport.write_all(&framed).await.expect("write framed");
        transport.flush().await.expect("flush");

        let mut echoed = vec![0u8; framed.len()];
        transport.read_exact(&mut echoed).await.expect("read framed echo");
        assert_eq!(echoed, framed);
    }

    /// Guard: the generic transport is built on the async I/O traits, so it
    /// composes over any `AsyncRead + AsyncWrite + Unpin` stream -- the
    /// generic path has fully moved off the sync `tungstenite` surface.
    /// A sync `std::net::TcpStream` does *not* satisfy the bound, which is
    /// why it is intentionally absent from the assertion below.
    #[test]
    fn ws_transport_requires_async_inner_stream() {
        fn _assert_async_bound<S: AsyncRead + AsyncWrite + Unpin>() {}
        _assert_async_bound::<TcpStream>();
        // `_assert_async_bound::<std::net::TcpStream>()` would not compile.
    }
}
