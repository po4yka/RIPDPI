use crate::relay::{random_padding_value, referer_padding, stream_up_path, HEADER_PADDING_MAX, HEADER_PADDING_MIN};
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
    assert!(padding.chars().all(|character| character == 'X'));
}

#[test]
fn random_padding_value_uses_expected_range() {
    let value = random_padding_value();
    assert!((HEADER_PADDING_MIN..=HEADER_PADDING_MAX).contains(&value.len()));
    assert!(value.chars().all(|character| character == 'X'));
}
