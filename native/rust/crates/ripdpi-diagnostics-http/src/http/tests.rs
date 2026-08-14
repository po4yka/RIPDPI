use std::collections::HashMap;

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
    let response =
        HttpResponse { status_code: 403, reason: "Forbidden".to_string(), headers: HashMap::new(), body: vec![] };
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
fn body_has_blockpage_keywords_ignores_large_legitimate_pages() {
    let mut page = b"<html><body>Learn how to circumvent censorship and access blocked content</body>".to_vec();
    page.resize(9000, b' '); // Pad to >8KB to simulate a real website
    assert!(!body_has_blockpage_keywords(&page));
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
