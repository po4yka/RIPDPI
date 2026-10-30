use std::io;

use base64::prelude::{BASE64_STANDARD, Engine as _};
use bytes::Bytes;
use http::header::PROXY_AUTHORIZATION;
use http::{HeaderMap, HeaderValue, Request};
use http_body_util::Empty;
use rand::RngExt;

use crate::config::NaiveProxyConfig;
use crate::socks5::SocksTarget;

pub const CONNECT_PADDING_HEADER_MIN: usize = 16;
pub const CONNECT_PADDING_HEADER_MAX: usize = 32;

const PADDING_HEADER: &str = "padding";
const PADDING_TYPE_REQUEST_HEADER: &str = "padding-type-request";
const PADDING_TYPE_REPLY_HEADER: &str = "padding-type-reply";
const PADDING_TYPE_NONE: &str = "0";
const PADDING_TYPE_VARIANT1: &str = "1";
/// The types upstream offers, in its order: Variant1, then none.
const PADDING_TYPE_REQUEST: &str = "1, 0";
/// The first 17 printable HPACK symbols, in symbol order, whose Huffman code
/// is at least 8 bits, as upstream `InitializeNonindexCodes` selects them.
const NONINDEX_CODES: &[u8; 17] = b"!\"#$&'()*+,;<>?@X";

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
        .header(PADDING_TYPE_REQUEST_HEADER, PADDING_TYPE_REQUEST);

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
    nonindex_header_value(rng.random(), len)
}

/// Upstream `FillNonindexHeaderValue`: each of the first 16 characters takes
/// 4 random bits; the rest repeat the 17th symbol.
fn nonindex_header_value(mut unique_bits: u64, len: usize) -> String {
    (0..len)
        .map(|index| {
            if index >= 16 {
                return char::from(NONINDEX_CODES[16]);
            }
            let code = NONINDEX_CODES[usize::from(u8::try_from(unique_bits & 0b1111).unwrap_or(0))];
            unique_bits >>= 4;
            char::from(code)
        })
        .collect()
}

/// Upstream `NaiveProxyDelegate` order: an explicit `padding-type-reply` decides;
/// without it, a legacy `padding` header (the Go forwardproxy fork) means Variant1.
pub fn negotiate_payload_padding_from_response(
    request_sent_padding: bool,
    headers: &HeaderMap,
) -> io::Result<PayloadPaddingMode> {
    if !request_sent_padding {
        return Ok(PayloadPaddingMode::Plain);
    }
    let mut replies = headers.get_all(PADDING_TYPE_REPLY_HEADER).iter();
    match (replies.next().map(HeaderValue::as_bytes), replies.next()) {
        (None, _) if headers.contains_key(PADDING_HEADER) => Ok(PayloadPaddingMode::Variant1),
        (None, _) => Ok(PayloadPaddingMode::Plain),
        (Some(value), None) if value == PADDING_TYPE_VARIANT1.as_bytes() => Ok(PayloadPaddingMode::Variant1),
        (Some(value), None) if value == PADDING_TYPE_NONE.as_bytes() => Ok(PayloadPaddingMode::Plain),
        (Some(value), _) => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "unsupported NaiveProxy padding-type-reply {}",
                String::from_utf8_lossy(&value[..value.len().min(16)])
            ),
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
