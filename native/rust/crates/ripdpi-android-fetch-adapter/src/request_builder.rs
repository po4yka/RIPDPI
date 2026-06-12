use std::collections::BTreeMap;
use std::io;

use android_support::authority_header_value;
use bytes::Bytes;
use http::header::{HOST, HeaderName, HeaderValue};
use http::{Method, Request};
use http_body_util::Full;

pub(crate) fn build_request(
    method: &Method,
    target_path: &str,
    host: &str,
    port: u16,
    https: bool,
    headers: &BTreeMap<String, String>,
) -> io::Result<Request<Full<Bytes>>> {
    let mut builder = Request::builder().method(method.clone()).uri(target_path);
    let mut has_host_header = false;
    for (name, value) in headers {
        let header_name = HeaderName::try_from(name.as_str())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid header name: {error}")))?;
        if header_name == HOST {
            has_host_header = true;
        }
        let header_value = HeaderValue::try_from(value.as_str())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid header value: {error}")))?;
        builder = builder.header(header_name, header_value);
    }
    if !has_host_header {
        builder = builder.header(HOST, authority_header_value(host, port, https));
    }
    builder
        .body(Full::new(Bytes::new()))
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid request body: {error}")))
}
