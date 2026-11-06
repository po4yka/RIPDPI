use bytes::Bytes;
use http_body_util::Full;
use hyper::client::conn::http1::SendRequest;

use super::FronterError;
use super::response::{self, HttpResponse};

pub(super) type Sender = SendRequest<Full<Bytes>>;

/// # Cancel safety:
/// NOT cancel-safe: the owning exchange must drop its connection after cancellation.
pub(super) async fn send_post(
    sender: &mut Sender,
    path: &str,
    host: &str,
    body: &[u8],
) -> Result<HttpResponse, FronterError> {
    send(sender, "POST", path, host, Bytes::copy_from_slice(body)).await
}

/// # Cancel safety:
/// NOT cancel-safe: the owning exchange must drop its connection after cancellation.
pub(super) async fn send_get(sender: &mut Sender, path: &str, host: &str) -> Result<HttpResponse, FronterError> {
    send(sender, "GET", path, host, Bytes::new()).await
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation can interrupt a sent request; the owner drops the connection.
async fn send(
    sender: &mut Sender,
    method: &str,
    path: &str,
    host: &str,
    body: Bytes,
) -> Result<HttpResponse, FronterError> {
    let mut request = ::http::Request::builder()
        .method(method)
        .uri(path)
        .header(::http::header::HOST, host)
        .header(::http::header::ACCEPT_ENCODING, "gzip");
    if method == "POST" {
        request = request.header(::http::header::CONTENT_TYPE, "application/json");
    }
    let request = request.body(Full::new(body)).map_err(|error| FronterError::BadResponse(error.to_string()))?;
    let response = sender.send_request(request).await.map_err(|error| FronterError::BadResponse(error.to_string()))?;
    response::read(response).await
}
