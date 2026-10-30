//! Operator-supplied extra CONNECT headers (`Name: value` lines, upstream
//! `--extra-headers` format). Values may be secrets: parsing errors never
//! echo them, and `NaiveProxyConfig`'s manual `Debug` omits them. Values are sent as
//! ordinary HPACK headers (not never-indexed) to match upstream's wire shape.

use std::io;

use http::{HeaderName, HeaderValue};

pub(crate) const MAX_EXTRA_HEADERS: usize = 16;
pub(crate) const MAX_EXTRA_HEADERS_BYTES: usize = 4096;

/// Names the helper owns, or that would break the tunnel or its framing.
const RESERVED: &[&str] = &[
    "host",
    "proxy-authorization",
    "padding",
    "padding-type-request",
    "x-naive-path",
    "connection",
    "transfer-encoding",
    "content-length",
    "te",
    "upgrade",
    "keep-alive",
    "proxy-connection",
];

pub(crate) type ExtraHeaders = Vec<(HeaderName, HeaderValue)>;

pub(crate) fn parse_extra_headers(raw: &str) -> io::Result<ExtraHeaders> {
    if raw.len() > MAX_EXTRA_HEADERS_BYTES {
        return Err(invalid("NaiveProxy extra headers are too large"));
    }
    let mut headers = ExtraHeaders::new();
    for (index, line) in raw.split('\n').enumerate() {
        let line = line.strip_suffix('\r').unwrap_or(line);
        if line.trim().is_empty() {
            continue;
        }
        let position = index + 1;
        let (name, value) = line
            .split_once(':')
            .ok_or_else(|| invalid(format!("NaiveProxy extra header line {position} is not `Name: value`")))?;
        let name = HeaderName::from_bytes(name.trim().as_bytes())
            .map_err(|_| invalid(format!("NaiveProxy extra header line {position} has an invalid name")))?;
        if RESERVED.contains(&name.as_str()) {
            return Err(invalid(format!("NaiveProxy extra header line {position} uses a reserved name")));
        }
        let value = HeaderValue::from_str(value.trim())
            .map_err(|_| invalid(format!("NaiveProxy extra header line {position} has an invalid value")))?;
        if headers.len() == MAX_EXTRA_HEADERS {
            return Err(invalid("NaiveProxy has too many extra headers"));
        }
        headers.push((name, value));
    }
    Ok(headers)
}

fn invalid(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_crlf_and_lf_lines_and_trims_values() {
        let headers = parse_extra_headers("X-One:  a b \r\nx-two: c\n\r\n").expect("valid headers");
        assert_eq!(headers.len(), 2);
        assert_eq!(headers[0].0.as_str(), "x-one");
        assert_eq!(headers[0].1.as_bytes(), b"a b");
        assert_eq!(headers[1].1.as_bytes(), b"c");
        assert!(!headers[0].1.is_sensitive(), "values must use normal HPACK indexing");
    }

    #[test]
    fn empty_input_yields_no_headers() {
        assert!(parse_extra_headers("").expect("empty").is_empty());
    }

    #[test]
    fn rejects_invalid_syntax_without_echoing_values() {
        for raw in ["no-colon", "bad name: v", ": v", ":path: v", "X-A: secretvalue\u{1}", "X-A: secretvalue\u{0}"] {
            let error = parse_extra_headers(raw).expect_err(raw);
            assert!(!error.to_string().contains("secretvalue"), "error echoed the value: {error}");
        }
    }

    #[test]
    fn rejects_reserved_names_case_insensitively() {
        for name in RESERVED {
            let upper = name.to_uppercase();
            assert!(parse_extra_headers(&format!("{upper}: v")).is_err(), "{name} must be reserved");
        }
        assert!(parse_extra_headers("X-Padding: v").is_ok());
    }

    #[test]
    fn caps_count_and_size() {
        let at_cap = (0..MAX_EXTRA_HEADERS).map(|i| format!("x-{i}: v")).collect::<Vec<_>>().join("\r\n");
        assert_eq!(parse_extra_headers(&at_cap).expect("at cap").len(), MAX_EXTRA_HEADERS);
        assert!(parse_extra_headers(&format!("{at_cap}\r\nx-over: v")).is_err());
        let oversized = format!("x-big: {}", "a".repeat(MAX_EXTRA_HEADERS_BYTES));
        assert!(parse_extra_headers(&oversized).is_err());
    }
}
