use bytes::Bytes;
use http_body_util::Full;
use hyper::Request;
use reqwest::header::{ACCEPT, CONNECTION, CONTENT_TYPE, HOST};
use url::{Position, Url};

use crate::types::EncryptedDnsError;

pub(super) fn build_post_http1_request(
    url: &Url,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Request<Full<Bytes>>, EncryptedDnsError> {
    if url.host_str().is_none() {
        return Err(EncryptedDnsError::MissingHost);
    }
    Request::post(&url[Position::BeforePath..Position::AfterQuery])
        .header(HOST, &url[Position::BeforeHost..Position::AfterPort])
        .header(CONTENT_TYPE, content_type)
        .header(ACCEPT, accept)
        .header(CONNECTION, "close")
        .body(Full::new(Bytes::copy_from_slice(body)))
        .map_err(|error| EncryptedDnsError::Request(error.to_string()))
}
