use std::collections::HashMap;
use std::io::{ErrorKind, Read, Write};

use crate::tls::{open_probe_stream, TlsClientProfile};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig};
use crate::util::*;

// --- Types ---

#[derive(Clone, Debug)]
pub(crate) struct HttpResponse {
    pub(crate) status_code: u16,
    pub(crate) reason: String,
    pub(crate) headers: HashMap<String, String>,
    pub(crate) body: Vec<u8>,
}

#[derive(Clone, Debug)]
pub(crate) struct HttpObservation {
    pub(crate) status: String,
    pub(crate) response: Option<HttpResponse>,
    pub(crate) error: Option<String>,
}

// --- Functions ---

pub(crate) fn try_http_request(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> HttpObservation {
    match execute_http_request(target, port, transport, host_header, path, secure) {
        Ok(response) => {
            HttpObservation { status: classify_http_response(&response), response: Some(response), error: None }
        }
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

pub(crate) fn execute_http_request(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> Result<HttpResponse, String> {
    let mut stream = open_probe_stream(
        target,
        port,
        transport,
        if secure { Some(host_header) } else { None },
        secure,
        TlsClientProfile::Auto,
    )?;
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host_header}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let response = read_http_response(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(response)
}

pub(crate) fn read_http_response(stream: &mut ConnectionStream, max_bytes: usize) -> Result<HttpResponse, String> {
    let buf = read_http_headers(stream, max_bytes)?;
    let header_end = find_headers_end(&buf).ok_or_else(|| "response_missing_headers".to_string())?;
    let header_bytes = buf[..header_end].to_vec();
    let mut body = buf[header_end + 4..].to_vec();
    let content_length = parse_content_length(&header_bytes);
    if let Some(expected_length) = content_length {
        if expected_length > max_bytes {
            return Err("response_too_large".to_string());
        }
        while body.len() < expected_length {
            let remaining = expected_length - body.len();
            let mut chunk = vec![0u8; remaining.min(4096)];
            let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..read]);
        }
    } else {
        loop {
            let mut chunk = [0u8; 4096];
            match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(read) => {
                    body.extend_from_slice(&chunk[..read]);
                    if body.len() > max_bytes {
                        return Err("response_too_large".to_string());
                    }
                }
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    break;
                }
                Err(err) => return Err(err.to_string()),
            }
        }
    }

    parse_http_response(&header_bytes, body)
}

pub(crate) fn read_http_headers(stream: &mut ConnectionStream, max_bytes: usize) -> Result<Vec<u8>, String> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 1024];
    loop {
        let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
        if read == 0 {
            if buf.is_empty() {
                return Err("unexpected eof".to_string());
            }
            break;
        }
        buf.extend_from_slice(&chunk[..read]);
        if buf.len() > max_bytes {
            return Err("response_too_large".to_string());
        }
        if find_headers_end(&buf).is_some() {
            break;
        }
    }
    Ok(buf)
}

pub(crate) fn parse_http_response(headers: &[u8], body: Vec<u8>) -> Result<HttpResponse, String> {
    let text = String::from_utf8_lossy(headers);
    let mut lines = text.split("\r\n");
    let status_line = lines.next().ok_or_else(|| "missing_status_line".to_string())?;
    let mut status_parts = status_line.splitn(3, ' ');
    let _http_version = status_parts.next();
    let status_code = status_parts
        .next()
        .ok_or_else(|| "missing_status_code".to_string())?
        .parse::<u16>()
        .map_err(|err| err.to_string())?;
    let reason = status_parts.next().unwrap_or_default().to_string();
    let mut parsed_headers = HashMap::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some((name, value)) = line.split_once(':') {
            parsed_headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
        }
    }
    Ok(HttpResponse { status_code, reason, headers: parsed_headers, body })
}

pub(crate) fn classify_http_response(response: &HttpResponse) -> String {
    if response.status_code == 200 && !body_has_blockpage_keywords(&response.body) {
        "http_ok".to_string()
    } else if response.status_code == 403
        || response.status_code == 451
        || response.status_code == 302
        || body_has_blockpage_keywords(&response.body)
    {
        "http_blockpage".to_string()
    } else {
        format!("http_status_{}", response.status_code)
    }
}

pub(crate) fn describe_http_observation(observation: &HttpObservation) -> String {
    match (&observation.response, &observation.error) {
        (Some(response), _) => format!(
            "{} {} {}",
            response.status_code,
            response.reason,
            response.headers.get("server").cloned().unwrap_or_else(|| "server=unknown".to_string())
        ),
        (None, Some(error)) => error.clone(),
        (None, None) => "none".to_string(),
    }
}

pub(crate) fn is_blockpage(observation: &HttpObservation) -> bool {
    observation.status == "http_blockpage"
}

pub(crate) fn body_has_blockpage_keywords(body: &[u8]) -> bool {
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    ["blocked", "access denied", "forbidden", "restriction", "censorship"].iter().any(|needle| text.contains(needle))
}

pub(crate) fn extract_host_from_url(url: &str) -> Option<String> {
    let without_scheme = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let host = without_scheme.split('/').next()?;
    let host = host.split(':').next()?;
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

pub(crate) fn extract_path_from_url(url: &str) -> String {
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .unwrap_or(url);
    match without_scheme.find('/') {
        Some(idx) => without_scheme[idx..].to_string(),
        None => "/".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_http_response_ok_for_200() {
        let response = HttpResponse {
            status_code: 200,
            reason: "OK".to_string(),
            headers: HashMap::new(),
            body: b"Hello world".to_vec(),
        };
        assert_eq!(classify_http_response(&response), "http_ok");
    }

    #[test]
    fn classify_http_response_blockpage_for_403() {
        let response = HttpResponse {
            status_code: 403,
            reason: "Forbidden".to_string(),
            headers: HashMap::new(),
            body: vec![],
        };
        assert_eq!(classify_http_response(&response), "http_blockpage");
    }

    #[test]
    fn classify_http_response_blockpage_for_451() {
        let response = HttpResponse {
            status_code: 451,
            reason: "Unavailable For Legal Reasons".to_string(),
            headers: HashMap::new(),
            body: vec![],
        };
        assert_eq!(classify_http_response(&response), "http_blockpage");
    }

    #[test]
    fn classify_http_response_blockpage_for_302_redirect() {
        let response = HttpResponse {
            status_code: 302,
            reason: "Found".to_string(),
            headers: HashMap::new(),
            body: vec![],
        };
        assert_eq!(classify_http_response(&response), "http_blockpage");
    }

    #[test]
    fn classify_http_response_blockpage_for_200_with_keywords() {
        let response = HttpResponse {
            status_code: 200,
            reason: "OK".to_string(),
            headers: HashMap::new(),
            body: b"<html>Access Denied</html>".to_vec(),
        };
        assert_eq!(classify_http_response(&response), "http_blockpage");
    }

    #[test]
    fn classify_http_response_status_for_500() {
        let response = HttpResponse {
            status_code: 500,
            reason: "Internal Server Error".to_string(),
            headers: HashMap::new(),
            body: vec![],
        };
        assert_eq!(classify_http_response(&response), "http_status_500");
    }

    #[test]
    fn body_has_blockpage_keywords_detects_blocked() {
        assert!(body_has_blockpage_keywords(b"This page is Blocked by your ISP"));
    }

    #[test]
    fn body_has_blockpage_keywords_detects_forbidden() {
        assert!(body_has_blockpage_keywords(b"<h1>Forbidden</h1>"));
    }

    #[test]
    fn body_has_blockpage_keywords_returns_false_for_normal() {
        assert!(!body_has_blockpage_keywords(b"<html>Hello World</html>"));
    }

    #[test]
    fn parse_http_response_extracts_status_and_headers() {
        let headers = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nServer: nginx";
        let body = b"hello".to_vec();
        let response = parse_http_response(headers, body).unwrap();
        assert_eq!(response.status_code, 200);
        assert_eq!(response.reason, "OK");
        assert_eq!(response.headers.get("content-type").unwrap(), "text/html");
        assert_eq!(response.headers.get("server").unwrap(), "nginx");
        assert_eq!(response.body, b"hello");
    }

    #[test]
    fn parse_http_response_handles_missing_reason() {
        let headers = b"HTTP/1.1 204";
        let response = parse_http_response(headers, vec![]).unwrap();
        assert_eq!(response.status_code, 204);
        assert_eq!(response.reason, "");
    }

    #[test]
    fn extract_host_from_url_https() {
        assert_eq!(extract_host_from_url("https://example.com/path"), Some("example.com".to_string()));
    }

    #[test]
    fn extract_host_from_url_http_with_port() {
        assert_eq!(extract_host_from_url("http://example.com:8080/path"), Some("example.com".to_string()));
    }

    #[test]
    fn extract_host_from_url_no_scheme_returns_none() {
        assert_eq!(extract_host_from_url("example.com/path"), None);
    }

    #[test]
    fn extract_path_from_url_returns_path() {
        assert_eq!(extract_path_from_url("https://example.com/dns-query"), "/dns-query");
    }

    #[test]
    fn extract_path_from_url_no_path_returns_slash() {
        assert_eq!(extract_path_from_url("https://example.com"), "/");
    }

    #[test]
    fn describe_http_observation_with_response() {
        let obs = HttpObservation {
            status: "http_ok".to_string(),
            response: Some(HttpResponse {
                status_code: 200,
                reason: "OK".to_string(),
                headers: {
                    let mut h = HashMap::new();
                    h.insert("server".to_string(), "nginx".to_string());
                    h
                },
                body: vec![],
            }),
            error: None,
        };
        assert_eq!(describe_http_observation(&obs), "200 OK nginx");
    }

    #[test]
    fn describe_http_observation_with_error() {
        let obs = HttpObservation {
            status: "http_unreachable".to_string(),
            response: None,
            error: Some("connection refused".to_string()),
        };
        assert_eq!(describe_http_observation(&obs), "connection refused");
    }

    #[test]
    fn describe_http_observation_no_server_header() {
        let obs = HttpObservation {
            status: "http_ok".to_string(),
            response: Some(HttpResponse {
                status_code: 200,
                reason: "OK".to_string(),
                headers: HashMap::new(),
                body: vec![],
            }),
            error: None,
        };
        assert_eq!(describe_http_observation(&obs), "200 OK server=unknown");
    }

    #[test]
    fn is_blockpage_true_for_blockpage_status() {
        let obs = HttpObservation {
            status: "http_blockpage".to_string(),
            response: None,
            error: None,
        };
        assert!(is_blockpage(&obs));
    }

    #[test]
    fn is_blockpage_false_for_ok_status() {
        let obs = HttpObservation {
            status: "http_ok".to_string(),
            response: None,
            error: None,
        };
        assert!(!is_blockpage(&obs));
    }
}
