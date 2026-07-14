use std::io;
use std::time::Duration;

use bytes::Bytes;
use http::Method;
use http_body_util::BodyExt;
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use tokio::net::TcpStream;
use tokio::time::timeout;
use url::Url;

use crate::dto::{NativeOwnedTlsHttpRequest, RawHttpResponse};
use crate::request_builder::build_request;
use crate::socket_protection::connect_transport;
use crate::tls_profile::connect_tls;
use crate::url_endpoint::{UrlEndpoint, parse_url_endpoint};

pub(crate) const MAX_RESPONSE_BODY_BYTES: usize = 8 * 1024 * 1024;

pub(crate) async fn execute_once(
    method: &Method,
    url: &Url,
    request: &NativeOwnedTlsHttpRequest,
) -> io::Result<RawHttpResponse> {
    let https = match url.scheme() {
        "https" => true,
        "http" => false,
        scheme => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported scheme for native TLS fetch: {scheme}"),
            ));
        }
    };
    let endpoint = parse_url_endpoint(url)?;
    let tcp = connect_transport(&endpoint.host, endpoint.port, request.connect_timeout_ms).await?;
    tcp.set_nodelay(true)?;

    if https {
        execute_once_https(method, &endpoint, request, tcp).await
    } else {
        send_request(method, &endpoint.target_path, &endpoint.host, endpoint.port, false, request, TokioIo::new(tcp))
            .await
    }
}

#[inline(never)]
async fn execute_once_https(
    method: &Method,
    endpoint: &UrlEndpoint,
    request: &NativeOwnedTlsHttpRequest,
    tcp: TcpStream,
) -> io::Result<RawHttpResponse> {
    let tls = connect_tls(&endpoint.host, tcp, request.connect_timeout_ms, &request.tls_profile_id).await?;
    send_request(method, &endpoint.target_path, &endpoint.host, endpoint.port, true, request, TokioIo::new(tls)).await
}

async fn send_request<T>(
    method: &Method,
    target_path: &str,
    host: &str,
    port: u16,
    https: bool,
    request: &NativeOwnedTlsHttpRequest,
    io: TokioIo<T>,
) -> io::Result<RawHttpResponse>
where
    T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (mut sender, connection) = timeout(Duration::from_millis(request.read_timeout_ms), http1::handshake(io))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP handshake timed out"))?
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("HTTP handshake failed: {error}")))?;
    tokio::spawn(async move {
        let _ = connection.await;
    });

    let http_request = build_request(method, target_path, host, port, https, &request.headers)?;
    let response = timeout(Duration::from_millis(request.read_timeout_ms), sender.send_request(http_request))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP request timed out"))?
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("request failed: {error}")))?;
    let status_code = response.status();
    let headers = response.headers().clone();
    let body = timeout(
        Duration::from_millis(request.read_timeout_ms),
        collect_response_body(response.into_body(), MAX_RESPONSE_BODY_BYTES),
    )
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "response body timed out"))??;
    Ok(RawHttpResponse { status_code, headers, body })
}

async fn collect_response_body<B>(mut body: B, max_bytes: usize) -> io::Result<Bytes>
where
    B: hyper::body::Body<Data = Bytes> + Unpin,
    B::Error: std::fmt::Display,
{
    let max_bytes_u64 = u64::try_from(max_bytes).unwrap_or(u64::MAX);
    let size_hint = body.size_hint();
    if size_hint.lower() > max_bytes_u64 || size_hint.upper().is_some_and(|upper| upper > max_bytes_u64) {
        return Err(response_body_too_large(max_bytes));
    }

    let initial_capacity = size_hint.upper().and_then(|upper| usize::try_from(upper).ok()).unwrap_or_default();
    let mut bytes = Vec::with_capacity(initial_capacity);
    while let Some(frame) = body.frame().await {
        let frame = frame.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("response body failed: {error}"))
        })?;
        let Ok(data) = frame.into_data() else {
            continue;
        };
        let new_len = bytes.len().checked_add(data.len()).ok_or_else(|| response_body_too_large(max_bytes))?;
        if new_len > max_bytes {
            return Err(response_body_too_large(max_bytes));
        }
        bytes.extend_from_slice(&data);
    }
    Ok(Bytes::from(bytes))
}

fn response_body_too_large(max_bytes: usize) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("native TLS fetch response body exceeds {max_bytes} bytes"))
}

#[cfg(test)]
mod body_limit_tests {
    use std::convert::Infallible;
    use std::pin::Pin;
    use std::task::{Context, Poll};

    use bytes::Bytes;
    use hyper::body::{Body, Frame, SizeHint};

    struct UnknownLengthBody(Option<Bytes>);

    impl Body for UnknownLengthBody {
        type Data = Bytes;
        type Error = Infallible;

        fn poll_frame(
            mut self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<Option<Result<Frame<Bytes>, Infallible>>> {
            Poll::Ready(self.0.take().map(|data| Ok(Frame::data(data))))
        }

        fn size_hint(&self) -> SizeHint {
            SizeHint::default()
        }
    }

    #[tokio::test]
    async fn unknown_length_body_is_stopped_at_byte_limit() {
        let error = super::collect_response_body(UnknownLengthBody(Some(Bytes::from_static(b"12345"))), 4)
            .await
            .expect_err("body over limit");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("exceeds 4 bytes"));
    }
}
