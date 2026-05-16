use crate::config::XhttpProtocolMode;
use crate::relay::{
    random_padding_value, referer_padding, stream_one_path, stream_up_path, HEADER_PADDING_MAX, HEADER_PADDING_MIN,
};
use crate::{XhttpTlsConfig, XmuxConfig};

#[test]
fn tls_config_normalizes_path_host_and_xmux_defaults() {
    let config = XhttpTlsConfig::from_strings(
        "edge.example",
        443,
        "edge.example",
        "550e8400-e29b-41d4-a716-446655440000",
        "api/v1/stream/",
        "origin.example",
        "chrome_stable",
    )
    .expect("config");

    assert_eq!("/api/v1/stream", config.path);
    assert_eq!(Some("origin.example".to_owned()), config.host);
    assert_eq!(XmuxConfig::default(), config.xmux);
}

#[test]
fn stream_up_path_appends_session_id() {
    assert_eq!("/api/v1/stream/session123", stream_up_path("/api/v1/stream", "session123"));
    assert_eq!("/session123", stream_up_path("/", "session123"));
}

#[test]
fn referer_padding_uses_expected_range() {
    let referer = referer_padding("cdn.example", "/api/v1/stream");
    let (_, padding) = referer.split_once("x_padding=").expect("padding");
    assert!((HEADER_PADDING_MIN..=HEADER_PADDING_MAX).contains(&padding.len()));
    assert!(
        padding.chars().all(|character| character.is_ascii_hexdigit() && !character.is_ascii_lowercase()),
        "padding must be uppercase hex (random anti-fingerprint): {padding}"
    );
}

#[test]
fn random_padding_value_uses_expected_range() {
    let value = random_padding_value();
    assert!((HEADER_PADDING_MIN..=HEADER_PADDING_MAX).contains(&value.len()));
    assert!(
        value.chars().all(|character| character.is_ascii_hexdigit() && !character.is_ascii_lowercase()),
        "padding must be uppercase hex (random anti-fingerprint): {value}"
    );
}

/// Two successive paddings must not be identical: literal `"X".repeat(n)` was
/// the old behaviour and is exactly what DPI heuristics flag.
#[test]
fn random_padding_value_varies_between_calls() {
    let a = random_padding_value();
    let b = random_padding_value();
    // Same length is allowed (both are in [100, 1000]); same content is not.
    assert_ne!(a, b, "two random paddings must not be byte-identical: a={a} b={b}");
}

/// Stream-one mode does not embed a session id in the URL — xray-core's
/// `dialer.go` stream-one branch sets `sessionId = ""` and writes the request
/// to `<path>` only.
#[test]
fn stream_one_path_returns_base_path_without_session_segment() {
    assert_eq!("/api/v1/stream", stream_one_path("/api/v1/stream"));
    assert_eq!("/", stream_one_path("/"));
    assert_eq!("/leading", stream_one_path("leading/"));
}

#[test]
fn stream_one_and_stream_up_paths_differ_only_by_session_segment() {
    let session = "deadbeef";
    let one = stream_one_path("/api/v1/stream");
    let up = stream_up_path("/api/v1/stream", session);
    assert_eq!(up, format!("{one}/{session}"));
}

#[test]
fn protocol_mode_parses_xray_identifiers() {
    assert_eq!(XhttpProtocolMode::parse("stream-up").unwrap(), XhttpProtocolMode::StreamUp);
    assert_eq!(XhttpProtocolMode::parse("  stream-up  ").unwrap(), XhttpProtocolMode::StreamUp);
    assert_eq!(XhttpProtocolMode::parse("stream-one").unwrap(), XhttpProtocolMode::StreamOne);
    // "" / "auto" resolve to the back-compat default (StreamUp).
    assert_eq!(XhttpProtocolMode::parse("").unwrap(), XhttpProtocolMode::StreamUp);
    assert_eq!(XhttpProtocolMode::parse("auto").unwrap(), XhttpProtocolMode::StreamUp);
}

#[test]
fn protocol_mode_parse_rejects_unimplemented_modes() {
    // `packet-up` and `stream-down` are documented in the audit but not
    // yet wired through; rejecting them with a named error prevents a
    // silent downgrade to `stream-up`.
    for unsupported in ["packet-up", "stream-down", "garbage"] {
        let Err(err) = XhttpProtocolMode::parse(unsupported) else {
            panic!("{unsupported:?} must be rejected");
        };
        assert!(err.to_string().contains(unsupported), "error must name the offending value: {err}");
    }
}

#[test]
fn xhttp_tls_config_defaults_to_stream_up() {
    let cfg = XhttpTlsConfig::from_strings(
        "edge.example",
        443,
        "edge.example",
        "550e8400-e29b-41d4-a716-446655440000",
        "/api",
        "origin.example",
        "chrome_stable",
    )
    .expect("config");
    assert_eq!(XhttpProtocolMode::StreamUp, cfg.protocol_mode);
}

#[test]
fn with_protocol_mode_threads_choice_through() {
    let cfg = XhttpTlsConfig::from_strings(
        "edge.example",
        443,
        "edge.example",
        "550e8400-e29b-41d4-a716-446655440000",
        "/api",
        "",
        "chrome_stable",
    )
    .expect("config")
    .with_protocol_mode(XhttpProtocolMode::StreamOne);
    assert_eq!(XhttpProtocolMode::StreamOne, cfg.protocol_mode);
}

#[test]
fn with_protocol_mode_str_parses_xray_identifier() {
    let cfg = XhttpTlsConfig::from_strings(
        "edge.example",
        443,
        "edge.example",
        "550e8400-e29b-41d4-a716-446655440000",
        "/api",
        "",
        "chrome_stable",
    )
    .expect("config")
    .with_protocol_mode_str("stream-one")
    .expect("stream-one accepted");
    assert_eq!(XhttpProtocolMode::StreamOne, cfg.protocol_mode);
}
