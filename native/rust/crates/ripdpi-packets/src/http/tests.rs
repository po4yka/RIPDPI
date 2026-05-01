use super::*;
use crate::types::{
    MH_DMIX, MH_HMIX, MH_HOSTEXTRASPACE, MH_HOSTPAD, MH_HOSTTAB, MH_METHODEOL, MH_METHODSPACE, MH_SPACE, MH_UNIXEOL,
};

#[test]
fn parse_http_extracts_host_and_port() {
    let request = b"GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n";
    let parsed = parse_http(request).expect("parse http host header");

    assert_eq!(parsed.host, b"example.com");
    assert_eq!(parsed.port, 8080);
}

#[test]
fn http_marker_info_tracks_method_host_and_port() {
    let request = b"\r\nGET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n";
    let markers = http_marker_info(request).expect("parse http markers");

    assert_eq!(markers.method_start, 2);
    assert_eq!(&request[markers.host_start..markers.host_end], b"example.com");
    assert_eq!(markers.port, 8080);
}

#[test]
fn http_marker_info_handles_ipv6_host_literals() {
    let request = b"GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n";
    let markers = http_marker_info(request).expect("parse ipv6 http markers");

    assert_eq!(&request[markers.host_start..markers.host_end], b"::1");
    assert_eq!(markers.port, 8080);
}

#[test]
fn second_level_domain_span_matches_structural_labels() {
    assert_eq!(second_level_domain_span(b"sub.example.com"), Some((4, 11)));
    assert_eq!(second_level_domain_span(b"example.com"), Some((0, 7)));
    assert_eq!(second_level_domain_span(b"localhost"), None);
}

#[test]
fn is_http_range_boundaries() {
    let connect = b"CONNECT host:443 HTTP/1.1\r\nHost: host\r\n\r\n";
    assert!(is_http(connect));
    let shifted = b"\nGET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    assert!(is_http(shifted));
    let trace = b"TRACE / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    assert!(is_http(trace));
    let below = b"BELOW / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    assert!(!is_http(below));
    let above = b"UPDATE / HTTP/1.1\r\nHost: e.com\r\n\r\n";
    assert!(!is_http(above));
}

#[test]
fn get_http_code_range_boundaries() {
    assert_eq!(get_http_code(b"HTTP/1.1 100 Continue\r\n\r\n"), Some(100));
    assert_eq!(get_http_code(b"HTTP/1.1 511 Not Extended\r\n\r\n"), Some(511));
    assert_eq!(get_http_code(b"HTTP/1.1 099 Below\r\n\r\n"), None);
    assert_eq!(get_http_code(b"HTTP/1.1 512 Above\r\n\r\n"), None);
}

#[test]
fn http_redirect_detection_uses_host_suffix() {
    let request = b"GET / HTTP/1.1\r\nHost: api.example.com\r\n\r\n";
    let redirect = b"HTTP/1.1 302 Found\r\nLocation: https://login.other.net/path\r\n\r\n";
    let same_site = b"HTTP/1.1 302 Found\r\nLocation: https://cdn.example.com/path\r\n\r\n";

    assert!(is_http_redirect(request, redirect));
    assert!(!is_http_redirect(request, same_site));
}

#[test]
fn is_http_redirect_same_suffix_not_redirect() {
    let req = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let same = b"HTTP/1.1 302 Found\r\nLocation: https://other.example.com/page\r\n\r\n";
    assert!(!is_http_redirect(req, same));
    let diff = b"HTTP/1.1 302 Found\r\nLocation: https://sub.other.net/page\r\n\r\n";
    assert!(is_http_redirect(req, diff));
}

#[test]
fn parse_http_ipv6_host_bracket() {
    let request = b"GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n";
    let parsed = parse_http(request).expect("parse ipv6 host");
    assert_eq!(parsed.host, b"::1");
    assert_eq!(parsed.port, 8080);
}

#[test]
fn mod_http_like_c_applies_header_and_domain_mixing() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_HMIX | MH_DMIX);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert!(output.contains("\r\nhOsT: ExAmPlE.CoM\r\n"));
}

#[test]
fn mod_http_like_c_applies_unix_eol_with_user_agent_padding() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_UNIXEOL);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert_eq!(mutation.bytes.len(), input.len());
    assert_eq!(output, "GET / HTTP/1.1\nHost: example.com\nUser-Agent: agent    \n\n");
}

#[test]
fn mod_http_like_c_applies_method_eol_and_trims_user_agent() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_METHODEOL);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert_eq!(mutation.bytes.len(), input.len());
    assert_eq!(output, "\r\nGET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: age\r\n\r\n");
}

#[test]
fn mod_http_like_c_best_effort_skips_eol_mutations_without_user_agent() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_UNIXEOL | MH_METHODEOL);

    assert_eq!(mutation.rc, -1);
    assert_eq!(mutation.bytes, input);
}

#[test]
fn mod_http_like_c_keeps_pipeline_order_for_safe_and_aggressive_mutations() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_HMIX | MH_DMIX | MH_SPACE | MH_UNIXEOL | MH_METHODEOL);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert!(output.starts_with("\r\nGET / HTTP/1.1\n"));
    assert!(output.contains("\nhOsT:ExAmPlE.CoM\t\n"));
    assert!(output.contains("\nUser-Agent: agent  \n\n"));
}

#[test]
fn mod_http_like_c_applies_method_space() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_METHODSPACE);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert!(output.starts_with("GET  / HTTP/1.1\r\n"));
}

#[test]
fn mod_http_like_c_applies_host_pad() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_HOSTPAD);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert!(output.contains("X-Pad: 01234567890123456789012345678901\r\n"));
}

#[test]
fn mod_http_like_c_applies_host_extra_space() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_HOSTEXTRASPACE);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert!(output.contains("Host : example.com"));
}

#[test]
fn mod_http_like_c_applies_host_tab() {
    let input = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mutation = mod_http_like_c(input, MH_HOSTTAB);
    let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

    assert_eq!(mutation.rc, 0);
    assert!(output.contains("Host:\texample.com"));
}

#[test]
fn parse_http_ignores_host_in_body() {
    // F-005: Host header in the body must NOT be picked up.
    let request = b"GET / HTTP/1.1\r\nHost: real.com\r\n\r\n\nHost: evil.com\r\n";
    let parsed = parse_http(request).expect("should parse real host");
    assert_eq!(parsed.host, b"real.com");
}

#[test]
fn redirect_ignores_location_in_body() {
    // F-006: Location header in the body must NOT be picked up.
    let req = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let resp =
        b"HTTP/1.1 302 Found\r\nLocation: https://cdn.example.com/ok\r\n\r\n\nLocation: https://evil.net/redir\r\n";
    assert!(!is_http_redirect(req, resp));
}

proptest::proptest! {
    #[test]
    fn parse_http_never_panics(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..512)) {
        let _ = is_http(&data);
        let _ = parse_http(&data);
        let _ = is_http_redirect(&data, &data);
    }
}
