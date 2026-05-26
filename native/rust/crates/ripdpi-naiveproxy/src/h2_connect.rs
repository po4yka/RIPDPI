use std::io;

use base64::prelude::{Engine as _, BASE64_STANDARD};
use bytes::Bytes;
use http::header::PROXY_AUTHORIZATION;
use http::{HeaderMap, HeaderValue, Request};
use http_body_util::Empty;
use rand::RngExt;

use crate::config::NaiveProxyConfig;
use crate::socks5::SocksTarget;

pub const CONNECT_PADDING_HEADER_MIN: usize = 16;
pub const CONNECT_PADDING_HEADER_MAX: usize = 32;
pub const RESPONSE_PADDING_HEADER_MIN: usize = 30;
pub const RESPONSE_PADDING_HEADER_MAX: usize = 62;

const PADDING_HEADER: &str = "padding";
const PADDING_TYPE_REQUEST_HEADER: &str = "padding-type-request";
const PADDING_TYPE_REPLY_HEADER: &str = "padding-type-reply";
const PADDING_TYPE_NONE: &str = "0";
const PADDING_TYPE_VARIANT1: &str = "1";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PayloadPaddingMode {
    Plain,
    Variant1,
}

pub fn build_h2_connect_request(
    config: &NaiveProxyConfig,
    target: &SocksTarget,
    padding_header_value: String,
) -> io::Result<Request<Empty<Bytes>>> {
    validate_padding_header_len(
        padding_header_value.len(),
        CONNECT_PADDING_HEADER_MIN,
        CONNECT_PADDING_HEADER_MAX,
        "CONNECT request padding header",
    )?;

    let mut builder = Request::builder()
        .method(http::Method::CONNECT)
        .uri(target.authority())
        .header(PADDING_HEADER, header_value(&padding_header_value, PADDING_HEADER)?)
        .header(PADDING_TYPE_REQUEST_HEADER, PADDING_TYPE_VARIANT1);

    if let (Some(username), Some(password)) = (&config.username, &config.password) {
        let encoded = BASE64_STANDARD.encode(format!("{username}:{password}"));
        builder = builder.header(PROXY_AUTHORIZATION, format!("Basic {encoded}"));
    }

    if let Some(path) = &config.path {
        builder = builder.header("x-naive-path", header_value(path, "x-naive-path")?);
    }

    builder
        .body(Empty::<Bytes>::new())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H2 CONNECT request: {error}")))
}

pub fn generate_connect_padding_header() -> String {
    let mut rng = rand::rng();
    let len = rng.random_range(CONNECT_PADDING_HEADER_MIN..=CONNECT_PADDING_HEADER_MAX);
    "~".repeat(len)
}

pub fn negotiate_payload_padding_from_response(
    request_sent_padding: bool,
    headers: &HeaderMap,
) -> io::Result<PayloadPaddingMode> {
    let Some(padding) = headers.get(PADDING_HEADER) else {
        return Ok(PayloadPaddingMode::Plain);
    };
    if !request_sent_padding {
        return Ok(PayloadPaddingMode::Plain);
    }

    validate_padding_header_len(
        padding.as_bytes().len(),
        RESPONSE_PADDING_HEADER_MIN,
        RESPONSE_PADDING_HEADER_MAX,
        "CONNECT response padding header",
    )?;

    match headers.get(PADDING_TYPE_REPLY_HEADER).map(HeaderValue::as_bytes) {
        None => Ok(PayloadPaddingMode::Variant1),
        Some(value) if value == PADDING_TYPE_VARIANT1.as_bytes() => Ok(PayloadPaddingMode::Variant1),
        Some(value) if value == PADDING_TYPE_NONE.as_bytes() => Ok(PayloadPaddingMode::Plain),
        Some(value) => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported NaiveProxy padding-type-reply {}", String::from_utf8_lossy(value)),
        )),
    }
}

fn validate_padding_header_len(value: usize, min: usize, max: usize, label: &str) -> io::Result<()> {
    if (min..=max).contains(&value) {
        return Ok(());
    }
    Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{label} length {value} outside [{min},{max}]")))
}

fn header_value(value: &str, name: &str) -> io::Result<HeaderValue> {
    HeaderValue::from_str(value)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid {name} header: {error}")))
}
