use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
use http::Request;
use http::header::{CONTENT_TYPE, REFERER};
use http_body_util::{BodyExt, Empty};
use hyper::body::{Body, Frame};
use tokio::sync::mpsc;

pub(crate) type XhttpBody = http_body_util::combinators::BoxBody<Bytes, io::Error>;

pub(crate) struct ChannelBody {
    receiver: mpsc::Receiver<io::Result<Bytes>>,
}

impl ChannelBody {
    pub(crate) fn new(receiver: mpsc::Receiver<io::Result<Bytes>>) -> Self {
        Self { receiver }
    }
}

impl Body for ChannelBody {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        match self.receiver.poll_recv(cx) {
            Poll::Ready(Some(Ok(bytes))) => Poll::Ready(Some(Ok(Frame::data(bytes)))),
            Poll::Ready(Some(Err(error))) => Poll::Ready(Some(Err(error))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}

pub(crate) fn build_get_request(path: &str, host_header: &str, referer: &str) -> io::Result<Request<XhttpBody>> {
    let request = Request::builder()
        .method("GET")
        .uri(request_uri(host_header, path))
        .header(REFERER, referer)
        .body(Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP GET request build: {error}")))?;
    Ok(request)
}

pub(crate) fn build_post_request(
    path: &str,
    host_header: &str,
    referer: &str,
    body: ChannelBody,
) -> io::Result<Request<XhttpBody>> {
    let request = Request::builder()
        .method("POST")
        .uri(request_uri(host_header, path))
        .header(REFERER, referer)
        .header(CONTENT_TYPE, "application/grpc")
        .body(body.boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP POST request build: {error}")))?;
    Ok(request)
}

/// Absolute-form target, so h2 sends `:scheme https` and `:authority` like a
/// browser instead of `:scheme http` plus a plain Host header.
fn request_uri(host: &str, path: &str) -> String {
    // Only a bare IPv6 literal needs brackets; `host:port` overrides go through as given.
    if host.parse::<std::net::Ipv6Addr>().is_ok() {
        return format!("https://[{host}]{path}");
    }
    format!("https://{host}{path}")
}

#[cfg(test)]
mod tests {
    use super::build_get_request;

    fn authority_of(host: &str) -> String {
        let request = build_get_request("/p", host, "https://ref.example/").expect("request builds");
        let uri = request.uri();
        assert_eq!(uri.scheme_str(), Some("https"));
        assert_eq!(uri.path(), "/p");
        uri.authority().expect("absolute-form URI has an authority").as_str().to_owned()
    }

    #[test]
    fn host_override_with_port_is_kept_as_the_authority() {
        assert_eq!(authority_of("cdn.example.com:8443"), "cdn.example.com:8443");
    }

    #[test]
    fn bare_ipv6_host_is_bracketed() {
        assert_eq!(authority_of("2001:db8::1"), "[2001:db8::1]");
    }

    #[test]
    fn bracketed_ipv6_host_with_port_is_unchanged() {
        assert_eq!(authority_of("[2001:db8::1]:8443"), "[2001:db8::1]:8443");
    }
}
