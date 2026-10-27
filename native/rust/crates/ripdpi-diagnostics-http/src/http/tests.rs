use std::collections::HashMap;

use crate::transport::ConnectionStream;

use super::response_parser::response_content_length;
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
fn classify_http_response_preserves_403_without_blockpage_evidence() {
    let response =
        HttpResponse { status_code: 403, reason: "Forbidden".to_string(), headers: HashMap::new(), body: vec![] };
    assert_eq!(classify_http_response(&response), "http_status_403");
}

#[test]
fn classify_http_response_preserves_origin_refusal_without_blockpage_evidence() {
    let response = HttpResponse {
        status_code: 403,
        reason: "Forbidden".to_string(),
        headers: HashMap::new(),
        body: b"Authentication is required to view this resource".to_vec(),
    };
    let fingerprints = crate::blockpage_fingerprints::load_fingerprints();
    assert_eq!(
        classify_http_response_with_fingerprints(&response, &fingerprints),
        ("http_status_403".to_string(), None)
    );
}

#[test]
fn classify_http_response_preserves_generic_403_forbidden_and_access_denied_pages() {
    let fingerprints = crate::blockpage_fingerprints::load_fingerprints();
    for body in ["<html><h1>403 Forbidden</h1></html>", "<html><h1>Access Denied</h1></html>"] {
        let response = HttpResponse {
            status_code: 403,
            reason: "Forbidden".to_string(),
            headers: HashMap::new(),
            body: body.as_bytes().to_vec(),
        };
        assert_eq!(
            classify_http_response_with_fingerprints(&response, &fingerprints),
            ("http_status_403".to_string(), None),
        );
    }
}

#[test]
fn classify_http_response_preserves_403_blockpage_body_evidence() {
    let response = HttpResponse {
        status_code: 403,
        reason: "Forbidden".to_string(),
        headers: HashMap::new(),
        body: b"This site has been blocked".to_vec(),
    };
    assert_eq!(classify_http_response(&response), "http_blockpage");
}

#[test]
fn classify_http_response_preserves_403_fingerprint_evidence() {
    let response = HttpResponse {
        status_code: 403,
        reason: "Forbidden".to_string(),
        headers: HashMap::new(),
        body: b"<html>zapret-info.gov.ru</html>".to_vec(),
    };
    let fingerprints = crate::blockpage_fingerprints::load_fingerprints();
    assert_eq!(
        classify_http_response_with_fingerprints(&response, &fingerprints),
        ("http_blockpage".to_string(), Some("rkn_standard".to_string())),
    );
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
fn classify_http_response_redirect_for_302() {
    let response =
        HttpResponse { status_code: 302, reason: "Found".to_string(), headers: HashMap::new(), body: vec![] };
    assert_eq!(classify_http_response(&response), "http_status_302");
}

#[test]
fn classify_http_response_blockpage_for_302_with_blockpage_body() {
    let response = HttpResponse {
        status_code: 302,
        reason: "Found".to_string(),
        headers: HashMap::new(),
        body: b"This site has been blocked".to_vec(),
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
fn generic_words_on_ordinary_responses_are_not_blockpages() {
    for (status_code, body, expected) in [
        (429, &b"Your IP is temporarily blocked. Try again later."[..], "http_status_429"),
        (200, &b"{\"error\":\"forbidden\",\"detail\":\"missing token\"}"[..], "http_ok"),
        (404, &b"<h1>Access Denied</h1>"[..], "http_status_404"),
        (503, &b"Service restriction in effect"[..], "http_status_503"),
    ] {
        let response =
            HttpResponse { status_code, reason: String::new(), headers: HashMap::new(), body: body.to_vec() };
        assert_eq!(classify_http_response(&response), expected, "status {status_code}");
    }
}

#[test]
fn explicit_block_message_is_a_blockpage_for_any_status() {
    let mut page = b"<html>This site has been blocked by the government</html>".to_vec();
    for status_code in [200, 404, 503] {
        let response = HttpResponse { status_code, reason: String::new(), headers: HashMap::new(), body: page.clone() };
        assert_eq!(classify_http_response(&response), "http_blockpage", "status {status_code}");
    }
    page.resize(9000, b' ');
    let large = HttpResponse { status_code: 200, reason: String::new(), headers: HashMap::new(), body: page };
    assert_eq!(classify_http_response(&large), "http_ok");
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
fn read_http_response_rejects_truncated_content_length() {
    use ripdpi_diagnostics_transport::transport::ConnectionStream;
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
    };

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabc").unwrap();
    });
    let mut stream = ConnectionStream::Plain(TcpStream::connect(addr).unwrap());
    assert_eq!(read_http_response(&mut stream, 1024).unwrap_err(), "response_truncated");
    server.join().unwrap();
}

#[test]
fn read_http_headers_rejects_eof_before_boundary() {
    use std::{io::Write, net::TcpListener, thread};

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n").unwrap();
    });
    let mut stream = ConnectionStream::Plain(std::net::TcpStream::connect(addr).unwrap());
    let headers = read_http_headers(&mut stream, 1024);
    server.join().unwrap();
    assert_eq!(headers.unwrap_err(), "response_missing_headers");
}

#[test]
fn read_http_response_ignores_content_length_on_not_modified() {
    use ripdpi_diagnostics_transport::transport::ConnectionStream;
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
    };

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.write_all(b"HTTP/1.1 304 Not Modified\r\nContent-Length: 5\r\n\r\n").unwrap();
    });
    let mut stream = ConnectionStream::Plain(TcpStream::connect(addr).unwrap());
    let response = read_http_response(&mut stream, 1024).expect("bodyless 304 response");
    assert_eq!(response.status_code, 304);
    assert!(response.body.is_empty());
    server.join().unwrap();
}

#[test]
fn read_http_response_ignores_bytes_after_content_length() {
    use ripdpi_diagnostics_transport::transport::ConnectionStream;
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
    };

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nblocked").unwrap();
    });
    let mut stream = ConnectionStream::Plain(TcpStream::connect(addr).unwrap());
    let response = read_http_response(&mut stream, 1024).expect("empty body response");
    assert!(response.body.is_empty());
    server.join().unwrap();
}

#[test]
fn read_http_response_skips_interim_response() {
    use ripdpi_diagnostics_transport::transport::ConnectionStream;
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
    };

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.write_all(b"HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK").unwrap();
    });
    let mut stream = ConnectionStream::Plain(TcpStream::connect(addr).unwrap());
    let response = read_http_response(&mut stream, 1024).expect("final response after early hints");
    assert_eq!(response.status_code, 200);
    assert_eq!(response.body, b"OK");
    server.join().unwrap();
}

#[test]
fn read_http_response_decodes_chunked_body() {
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
    };

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nblo\r\n4\r\ncked\r\n0\r\n\r\n")
            .unwrap();
    });
    let mut stream = ConnectionStream::Plain(TcpStream::connect(addr).unwrap());
    let response = read_http_response(&mut stream, 1024).expect("complete chunked response");
    assert_eq!(response.body, b"blocked");
    server.join().unwrap();
}

#[test]
fn read_http_response_rejects_truncated_chunked_body() {
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
    };

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabc").unwrap();
    });
    let mut stream = ConnectionStream::Plain(TcpStream::connect(addr).unwrap());
    assert_eq!(read_http_response(&mut stream, 1024).unwrap_err(), "response_truncated");
    server.join().unwrap();
}

#[test]
fn response_content_length_rejects_ambiguous_framing() {
    assert!(response_content_length(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5").is_err());
    assert!(response_content_length(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 5").is_err());
    assert_eq!(response_content_length(b"HTTP/1.1 200 OK\r\nContent-Length: 5, 5"), Ok(Some(5)));
    assert_eq!(response_content_length(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked"), Ok(None));
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
    let obs = HttpObservation { status: "http_blockpage".to_string(), response: None, error: None };
    assert!(is_blockpage(&obs));
}

#[test]
fn is_blockpage_false_for_ok_status() {
    let obs = HttpObservation { status: "http_ok".to_string(), response: None, error: None };
    assert!(!is_blockpage(&obs));
}

#[test]
fn classify_http_response_redirect_for_301() {
    let response = HttpResponse {
        status_code: 301,
        reason: "Moved Permanently".to_string(),
        headers: HashMap::new(),
        body: vec![],
    };
    assert_eq!(classify_http_response(&response), "http_status_301");
}

#[test]
fn classify_http_response_redirect_for_307() {
    let response = HttpResponse {
        status_code: 307,
        reason: "Temporary Redirect".to_string(),
        headers: HashMap::new(),
        body: vec![],
    };
    assert_eq!(classify_http_response(&response), "http_status_307");
}

#[test]
fn alt_svc_header_preserved_in_response() {
    let mut headers = HashMap::new();
    headers.insert("alt-svc".to_string(), "h3=\":443\"; ma=86400".to_string());
    let response = HttpResponse { status_code: 200, reason: "OK".to_string(), headers, body: b"ok".to_vec() };
    let alt_svc = response.headers.get("alt-svc").unwrap();
    assert!(alt_svc.contains("h3"));
    assert!(!alt_svc.contains("h2")); // Only h3 in this value
}

#[test]
fn alt_svc_header_detects_h3_among_multiple_protocols() {
    let mut headers = HashMap::new();
    headers.insert("alt-svc".to_string(), "h3=\":443\", h2=\":443\"; ma=86400".to_string());
    let response = HttpResponse { status_code: 200, reason: "OK".to_string(), headers, body: b"ok".to_vec() };
    let alt_svc = response.headers.get("alt-svc").unwrap();
    assert!(alt_svc.contains("h3"));
    assert!(alt_svc.contains("h2"));
}

#[test]
fn alt_svc_header_absent_returns_none() {
    let response =
        HttpResponse { status_code: 200, reason: "OK".to_string(), headers: HashMap::new(), body: b"ok".to_vec() };
    assert!(!response.headers.contains_key("alt-svc"));
}

#[test]
fn parse_http_response_preserves_alt_svc_header() {
    let raw_headers = b"HTTP/1.1 200 OK\r\nAlt-Svc: h3=\":443\"; ma=86400\r\nServer: nginx";
    let response = parse_http_response(raw_headers, vec![]).unwrap();
    assert_eq!(response.headers.get("alt-svc").unwrap(), "h3=\":443\"; ma=86400");
}

#[test]
fn http_request_preserves_ipv6_authority_and_port() {
    use ripdpi_diagnostics_transport::transport::{TargetAddress, TransportConfig};
    use std::{
        io::{Read, Write},
        net::TcpListener,
        thread,
    };
    for plural in [false, true] {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = [0; 1024];
            let len = stream.read(&mut buf).unwrap();
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").unwrap();
            String::from_utf8(buf[..len].to_vec()).unwrap()
        });
        let target = TargetAddress::Ip(addr.ip());
        let transport = TransportConfig::Direct { route_experiment: None };
        let result = if plural {
            execute_http_request_targets(&[target], addr.port(), &transport, "::1", "/", false)
        } else {
            execute_http_request(&target, addr.port(), &transport, "::1", "/", false)
        };
        result.unwrap();
        let request = server.join().unwrap();
        assert!(request.contains(&format!("Host: [::1]:{}\r\n", addr.port())), "{request}");
    }
}
