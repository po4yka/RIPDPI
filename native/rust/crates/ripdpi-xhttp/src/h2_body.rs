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
pub(crate) fn request_uri(host: &str, path: &str) -> String {
    let (path, query) = path.split_once('?').map_or((path, None), |(path, query)| (path, Some(query)));
    // Match Go URL.Path escaping; the raw query is already encoded configuration data.
    let mut encoded = String::with_capacity(path.len());
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    for byte in path.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.~$&+,/:;=@".contains(&byte) {
            encoded.push(char::from(byte));
        } else {
            encoded.push('%');
            encoded.push(char::from(HEX[usize::from(byte >> 4)]));
            encoded.push(char::from(HEX[usize::from(byte & 15)]));
        }
    }
    if let Some(query) = query {
        encoded.push('?');
        encoded.push_str(query);
    }
    // Only a bare IPv6 literal needs brackets; `host:port` overrides go through as given.
    if host.parse::<std::net::Ipv6Addr>().is_ok() {
        return format!("https://[{host}]{encoded}");
    }
    format!("https://{host}{encoded}")
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

    #[test]
    fn configured_paths_match_xray_request_target_vectors() {
        // Fixed URL.Path / RawQuery vectors from Go net/url and Xray's normalized path.
        for (path, expected_one, expected_up) in [
            ("/audit%2Fpath", "/audit%252Fpath/", "/audit%252Fpath/sid"),
            ("/audit//", "/audit//", "/audit//sid"),
            ("/audit?token=a/", "/audit/?token=a/", "/audit/sid?token=a/"),
            ("/", "/", "/sid"),
            ("/p/", "/p/", "/p/sid"),
            ("/p//", "/p//", "/p//sid"),
            ("/100%", "/100%25/", "/100%25/sid"),
            (" /a b/é#?q=x%2Fy/", "/%20/a%20b/%C3%A9%23/?q=x%2Fy/", "/%20/a%20b/%C3%A9%23/sid?q=x%2Fy/"),
            ("/a:@&=+$,;!()'*[]", "/a:@&=+$,;%21%28%29%27%2A%5B%5D/", "/a:@&=+$,;%21%28%29%27%2A%5B%5D/sid"),
        ] {
            let tls = crate::XhttpTlsConfig::from_strings(
                "audit.test",
                443,
                "audit.test",
                "550e8400-e29b-41d4-a716-446655440000",
                path,
                "",
                "chrome_stable",
            )
            .expect("TLS config");
            // REALITY supplies the configured path directly; TLS first passes its constructor.
            for configured in [path, tls.path.as_str()] {
                for (target, expected) in [
                    (crate::relay::stream_one_path(configured), expected_one),
                    (crate::relay::stream_up_path(configured, "sid"), expected_up),
                ] {
                    let referer = crate::relay::referer_padding("audit.test", &target);
                    let request = build_get_request(&target, "audit.test", &referer).expect("request builds");
                    assert_eq!(request.uri().path_and_query().unwrap().as_str(), expected, "configured {path:?}");
                    assert!(
                        referer.starts_with(&format!(
                            "https://audit.test{}?x_padding=",
                            expected.split('?').next().unwrap()
                        ))
                    );
                }
            }
        }
    }
}
