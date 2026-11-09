use super::*;
use crate::{MH_DMIX, http_marker_info, mod_http_like_c};

#[test]
fn authority_uses_target_forms_and_preserves_raw_host_markers() {
    for (request, expected_host, expected_port) in [
        (&b"GET http://real.example:8080/path HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..], &b"real.example"[..], 8080),
        (&b"GET HTTPS://real.example/path HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..], &b"real.example"[..], 443),
        (&b"CONNECT real.example:443 HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..], &b"real.example"[..], 443),
        (&b"OPTIONS * HTTP/1.1\r\nHost: real.example\r\n\r\n"[..], &b"real.example"[..], 80),
        (&b"GET /path HTTP/1.1\r\nhOsT:\t real.example:8080 \t\r\n\r\n"[..], &b"real.example"[..], 8080),
        (&b"GET http://[2001:db8::1]:8080/path HTTP/1.0\r\n\r\n"[..], &b"2001:db8::1"[..], 8080),
    ] {
        assert_eq!(http_request_authority(request), Some(HttpHost { host: expected_host, port: expected_port }));
    }
    let request = b"GET http://real.example/path HTTP/1.1\r\nHost: decoy.example\r\n\r\n";
    let marker = http_marker_info(request).expect("raw Host marker");
    assert_eq!(&request[marker.host_start..marker.host_end], b"decoy.example");
    assert_eq!(
        mod_http_like_c(request, MH_DMIX).bytes,
        b"GET http://real.example/path HTTP/1.1\r\nHost: DeCoY.ExAmPlE\r\n\r\n"
    );
}

#[test]
fn authority_rejects_incomplete_ambiguous_and_unsupported_inputs() {
    for request in [
        &b"GET / HTTP/1.1\r\nHost: example.com\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: first.example\r\nhost: second.example\r\n\r\n"[..],
        &b"GET http://real.example/ HTTP/1.1\r\nHost: first.example\r\nHost: second.example\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost : example.com\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: example.com\r\n .evil.test\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: example.com:abc\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: example.com:65536\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: example.com,other.com\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: user@example.com\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: [not-ipv6]:80\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: ::1\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: [::1]:\r\n\r\n"[..],
        &b"CONNECT real.example HTTP/1.1\r\nHost: real.example\r\n\r\n"[..],
        &b"GET ftp://real.example/ HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..],
        &b"GET http://user:pass@real.example/ HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..],
        &b"GET / HTTP/2.0\r\nHost: real.example\r\n\r\n"[..],
    ] {
        assert_eq!(http_request_authority(request), None, "{:?}", String::from_utf8_lossy(request));
    }
}

#[test]
fn authority_keeps_ipv6_and_default_ports_separate_from_raw_host() {
    for (value, expected_host, expected_port) in [
        ("[2001:db8::a]:8080", &b"2001:db8::a"[..], 8080),
        ("[::1]", &b"::1"[..], 80),
        ("Example.COM.", &b"Example.COM."[..], 80),
        ("127.0.0.1:65535", &b"127.0.0.1"[..], 65535),
    ] {
        let request = format!("GET / HTTP/1.1\r\nHost: {value}\r\n\r\n");
        assert_eq!(
            http_request_authority(request.as_bytes()),
            Some(HttpHost { host: expected_host, port: expected_port })
        );
    }
}

#[test]
fn authority_rejects_colon_containing_header_continuations() {
    for request in [
        &b"GET / HTTP/1.1\r\nHost: example.com\r\n :443\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: example.com\r\n\t:443\r\n\r\n"[..],
        &b"GET / HTTP/1.1\r\nHost: example.com\r\n \tother:443\r\n\r\n"[..],
        &b"GET http://real.example/ HTTP/1.1\r\nHost: example.com\r\n :443\r\n\r\n"[..],
    ] {
        assert_eq!(http_request_authority(request), None, "{:?}", String::from_utf8_lossy(request));
    }
}
