//! Composable HTTP/3 facade over the shared QUIC transport.
//!
//! [`H3Transport`] turns an established `quinn::Connection` into an HTTP/3
//! client connection and exposes a CONNECT-capable surface. This is the "H3
//! facade" the epic asks for: VLESS / VMess / generic outbounds that want
//! HTTP/3 extended CONNECT build one of these instead of re-deriving the
//! `h3`/`h3-quinn` setup that was previously locked inside `ripdpi-masque`.
//!
//! The HTTP/3 *handshake driver* must be polled for the connection to make
//! progress; [`H3Transport::connect`] hands the driver back so the caller can
//! `tokio::spawn` it (matching how `ripdpi-masque` drives its own H3
//! connection today).
//!
//! # Extended CONNECT and the `:protocol` pseudo-header
//!
//! HTTP/3 carries the extended-CONNECT `:protocol` pseudo-header as a
//! first-class field, not as a normal header -- the `http` crate's
//! `HeaderName` parser rejects a literal `":protocol"` name. The `h3` crate
//! models it as [`h3::ext::Protocol`], set on the request's
//! `Extensions`. [`build_connect_request`] does exactly that, so the request
//! it returns is one `h3::client::SendRequest::send_request` accepts
//! verbatim.

use bytes::Bytes;
use http::Request;

use super::stream::QuicTransport;
use crate::error::{HysteriaError, Result};

/// The kind of HTTP/3 extended-CONNECT tunnel to request. Each variant maps
/// to an [`h3::ext::Protocol`] the `h3` crate can express on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum H3ConnectKind {
    /// `connect-udp`: tunnel UDP datagrams over HTTP/3 (RFC 9298).
    ConnectUdp,
    /// `webtransport`: a WebTransport session over HTTP/3.
    WebTransport,
}

impl H3ConnectKind {
    /// The `:protocol` pseudo-header token for this CONNECT kind.
    pub fn protocol_token(self) -> &'static str {
        match self {
            Self::ConnectUdp => "connect-udp",
            Self::WebTransport => "webtransport",
        }
    }

    /// The [`h3::ext::Protocol`] value the `h3` crate uses to carry this
    /// CONNECT kind's `:protocol` pseudo-header.
    pub fn as_h3_protocol(self) -> h3::ext::Protocol {
        match self {
            Self::ConnectUdp => h3::ext::Protocol::CONNECT_UDP,
            Self::WebTransport => h3::ext::Protocol::WEB_TRANSPORT,
        }
    }

    /// Whether this CONNECT kind needs HTTP/3 datagram support negotiated on
    /// the connection. `connect-udp` does; WebTransport sessions do too.
    pub fn needs_datagrams(self) -> bool {
        matches!(self, Self::ConnectUdp | Self::WebTransport)
    }
}

/// The HTTP/3 client halves produced by [`H3Transport::connect`].
///
/// `driver` must be polled to completion (typically via `tokio::spawn`) for
/// the H3 connection to progress; `requests` is the request-sending handle an
/// outbound uses to issue CONNECT requests.
pub struct H3ClientParts {
    /// The H3 connection driver. Spawn it; it resolves when the connection
    /// closes.
    pub driver: h3::client::Connection<h3_quinn::Connection, Bytes>,
    /// The handle for sending HTTP/3 requests over the connection.
    pub requests: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
}

/// A composable HTTP/3 facade over an established QUIC connection.
pub struct H3Transport {
    quic: QuicTransport,
}

impl H3Transport {
    /// Adopt an established QUIC transport as the substrate for HTTP/3.
    pub fn new(quic: QuicTransport) -> Self {
        Self { quic }
    }

    /// Negotiate HTTP/3 over the QUIC connection.
    ///
    /// `enable_datagram` turns on HTTP/3 datagram support (needed for
    /// `connect-udp` and WebTransport). The returned
    /// [`H3ClientParts::driver`] must be spawned by the caller.
    pub async fn connect(&self, enable_datagram: bool) -> Result<H3ClientParts> {
        let mut builder = h3::client::builder();
        builder.enable_extended_connect(true);
        builder.enable_datagram(enable_datagram);
        let h3_conn = h3_quinn::Connection::new(self.quic.connection().clone());
        let (driver, requests) = builder.build(h3_conn).await.map_err(|error| {
            HysteriaError::Io(std::io::Error::new(
                std::io::ErrorKind::ConnectionRefused,
                format!("failed to negotiate HTTP/3 over QUIC: {error}"),
            ))
        })?;
        Ok(H3ClientParts { driver, requests })
    }

    /// Borrow the underlying QUIC transport.
    pub fn quic(&self) -> &QuicTransport {
        &self.quic
    }
}

/// Build an HTTP/3 extended-CONNECT request for `authority` (e.g.
/// `"example.com:443"`).
///
/// `request_uri` is the proxy origin's request URI; `kind` selects the
/// extended-CONNECT `:protocol`, which is attached as an
/// [`h3::ext::Protocol`] request extension (HTTP/3 carries `:protocol` as a
/// pseudo-header, not a normal header). The returned request carries no auth
/// headers -- the caller layers those on, exactly as `ripdpi-masque` does.
pub fn build_connect_request(request_uri: http::Uri, authority: &str, kind: H3ConnectKind) -> Result<Request<()>> {
    let mut request =
        Request::builder().method("CONNECT").uri(request_uri).header(http::header::HOST, authority).body(()).map_err(
            |error| {
                HysteriaError::Io(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!("invalid HTTP/3 CONNECT request: {error}"),
                ))
            },
        )?;
    // The extended-CONNECT `:protocol` pseudo-header rides in the request's
    // extensions -- `h3` reads it from there when it serializes the request.
    request.extensions_mut().insert(kind.as_h3_protocol());
    Ok(request)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connect_kind_maps_to_protocol_token() {
        assert_eq!(H3ConnectKind::ConnectUdp.protocol_token(), "connect-udp");
        assert_eq!(H3ConnectKind::WebTransport.protocol_token(), "webtransport");
    }

    #[test]
    fn connect_kind_maps_to_h3_protocol_extension() {
        assert_eq!(H3ConnectKind::ConnectUdp.as_h3_protocol(), h3::ext::Protocol::CONNECT_UDP);
        assert_eq!(H3ConnectKind::WebTransport.as_h3_protocol(), h3::ext::Protocol::WEB_TRANSPORT);
    }

    #[test]
    fn extended_connect_kinds_need_datagrams() {
        assert!(H3ConnectKind::ConnectUdp.needs_datagrams());
        assert!(H3ConnectKind::WebTransport.needs_datagrams());
    }

    #[test]
    fn build_connect_request_sets_method_and_protocol_extension() {
        let uri: http::Uri = "https://proxy.example/".parse().expect("uri");
        let request =
            build_connect_request(uri, "target.example:443", H3ConnectKind::ConnectUdp).expect("build request");
        assert_eq!(request.method(), "CONNECT");
        assert_eq!(request.headers().get(http::header::HOST).and_then(|v| v.to_str().ok()), Some("target.example:443"));
        // The `:protocol` pseudo-header rides in the extensions, not headers.
        assert_eq!(request.extensions().get::<h3::ext::Protocol>(), Some(&h3::ext::Protocol::CONNECT_UDP));
    }

    #[test]
    fn build_connect_request_supports_webtransport() {
        let uri: http::Uri = "https://proxy.example/wt".parse().expect("uri");
        let request = build_connect_request(uri, "8.8.8.8:53", H3ConnectKind::WebTransport).expect("build request");
        assert_eq!(request.extensions().get::<h3::ext::Protocol>(), Some(&h3::ext::Protocol::WEB_TRANSPORT));
    }
}
