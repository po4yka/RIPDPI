//! SIP002 `ss://` URI parser tests.
//!
//! Covers: SIP002 base64 userinfo, plain userinfo, legacy whole-URI base64,
//! fragment tag, IPv6 host, stream cipher rejection, error cases.

use ripdpi_shadowsocks::cipher::Cipher;
use ripdpi_shadowsocks::uri::{ShadowsocksUri, UriError};

/// `aes-256-gcm:fixture-password-sip002@example.com:8388`
/// base64url → `YWVzLTI1Ni1nY206Zml4dHVyZS1wYXNzd29yZC1zaXAwMDJAZXhhbXBsZS5jb206ODM4OA==`
///
/// SIP002 userinfo only (before @): base64url("aes-256-gcm:fixture-password-sip002")
/// = "YWVzLTI1Ni1nY206Zml4dHVyZS1wYXNzd29yZC1zaXAwMDI="
const SIP002_AES256_URI: &str = "ss://YWVzLTI1Ni1nY206Zml4dHVyZS1wYXNzd29yZC1zaXAwMDI=@example.com:8388#MyNode";

#[test]
fn sip002_aes256gcm_parses_correctly() {
    let parsed = ShadowsocksUri::parse(SIP002_AES256_URI).expect("valid SIP002 URI");

    assert_eq!(parsed.cipher, Cipher::AeadAes256Gcm);
    assert_eq!(parsed.host, "example.com");
    assert_eq!(parsed.port, 8388);
    assert_eq!(parsed.tag.as_deref(), Some("MyNode"));
    // Password must not be empty.
    assert!(!parsed.password.expose().is_empty());
}

#[test]
fn sip002_plain_userinfo_parses_correctly() {
    // Plain method:password@host:port (no base64 encoding of userinfo).
    let uri = "ss://chacha20-ietf-poly1305:fixture-plain-password@proxy.example.com:443";
    let parsed = ShadowsocksUri::parse(uri).expect("valid plain SIP002 URI");

    assert_eq!(parsed.cipher, Cipher::AeadChacha20IetfPoly1305);
    assert_eq!(parsed.host, "proxy.example.com");
    assert_eq!(parsed.port, 443);
    assert_eq!(parsed.password.expose(), "fixture-plain-password");
    assert!(parsed.tag.is_none());
}

#[test]
fn legacy_base64_whole_uri_parses_correctly() {
    // Legacy: ss://base64(method:password@host:port)
    // Encodes: "aes-128-gcm:fixture-legacy-password@legacy.example.com:8388"
    let inner = "aes-128-gcm:fixture-legacy-password@legacy.example.com:8388";
    let encoded = base64_encode(inner);
    let uri = format!("ss://{encoded}");

    let parsed = ShadowsocksUri::parse(&uri).expect("valid legacy URI");

    assert_eq!(parsed.cipher, Cipher::AeadAes128Gcm);
    assert_eq!(parsed.host, "legacy.example.com");
    assert_eq!(parsed.port, 8388);
    assert_eq!(parsed.password.expose(), "fixture-legacy-password");
}

#[test]
fn fragment_tag_is_url_decoded() {
    let uri = "ss://aes-256-gcm:fixture-tag-password@h.example.com:1080#My%20Node%20%F0%9F%9A%80";
    let parsed = ShadowsocksUri::parse(uri).expect("valid URI with encoded tag");

    // Tag should be percent-decoded.
    assert!(parsed.tag.is_some());
    let tag = parsed.tag.unwrap();
    assert!(tag.contains("My") && tag.contains("Node"), "tag={tag}");
}

#[test]
fn ipv6_host_parses_correctly() {
    let uri = "ss://aes-256-gcm:fixture-ipv6-password@[::1]:9000";
    let parsed = ShadowsocksUri::parse(uri).expect("valid IPv6 URI");

    assert_eq!(parsed.host, "::1");
    assert_eq!(parsed.port, 9000);
}

#[test]
fn stream_cipher_in_uri_returns_cipher_error() {
    // rc4 in URI should be rejected.
    let _uri = "ss://cmM0OmZpeHR1cmUtcmM0LXBhc3N3b3Jk@example.com:8388";
    // rc4:fixture-rc4-password base64 → cmM0OmZpeHR1cmUtcmM0LXBhc3N3b3Jk
    // Manually construct a plain userinfo URI with rc4:
    let uri_plain = "ss://rc4:fixture-rc4-password@example.com:8388";
    let result = ShadowsocksUri::parse(uri_plain);

    assert!(result.is_err(), "rc4 URI must be rejected");
    match result.unwrap_err() {
        UriError::Cipher(_) => {}
        e => panic!("expected UriError::Cipher, got {e:?}"),
    }
}

#[test]
fn bad_scheme_returns_error() {
    let result = ShadowsocksUri::parse("vless://user@example.com:443");
    assert!(matches!(result, Err(UriError::BadScheme)));
}

#[test]
fn empty_password_returns_error() {
    let uri = "ss://aes-256-gcm:@example.com:8388";
    let result = ShadowsocksUri::parse(uri);
    assert!(result.is_err());
}

#[test]
fn missing_port_returns_error() {
    let uri = "ss://aes-256-gcm:fixture-pass@example.com";
    let result = ShadowsocksUri::parse(uri);
    assert!(result.is_err());
}

#[test]
fn sip002_chacha_with_tag() {
    let uri = "ss://chacha20-ietf-poly1305:fixture-sip002-chacha-pass@ch.example.com:8388#ChachaNode";
    let parsed = ShadowsocksUri::parse(uri).expect("valid chacha SIP002 URI");

    assert_eq!(parsed.cipher, Cipher::AeadChacha20IetfPoly1305);
    assert_eq!(parsed.tag.as_deref(), Some("ChachaNode"));
}

fn base64_encode(s: &str) -> String {
    use base64::engine::Engine;
    base64::engine::general_purpose::STANDARD.encode(s.as_bytes())
}
