#![forbid(unsafe_code)]

//! Legacy Hysteria v1 outbound foundation for RIPDPI.
//!
//! Hysteria v1 is the original
//! ([apernet/hysteria](https://github.com/apernet/hysteria) pre-2.0) protocol:
//! a **custom QUIC framing** over a UDP / wechat-video / faketcp carrier, with
//! its own auth payload and a **bandwidth-based ("brutal") congestion
//! controller** that REQUIRES the operator to declare up/down link speeds. This
//! crate is distinct from `ripdpi-hysteria2`; it exists only to keep existing
//! v1 subscriptions importable during the migration window.
//!
//! **Committed sunset target: 2027-12-31** — this crate is to be removed once
//! subscriptions have fully migrated to Hysteria2. Treat it as legacy: do not
//! build new surface on top of it.
//!
//! This crate provides the configuration, validation, secret redaction, and
//! relay-registration surface, but the **custom QUIC-framing v1 handshake, the
//! authentication, and the bandwidth-based congestion controller are
//! intentionally stubbed**: a fully valid config still terminates in
//! [`HysteriaV1Error::Unimplemented`] rather than fabricating a placeholder
//! handshake. The real engine is tracked under
//! `epic-extended-outbound-protocol-support`.

mod client;
mod config;
mod error;

pub use client::*;
pub use config::*;
pub use error::*;

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> HysteriaV1Config {
        HysteriaV1Config {
            server: "hysteria.example".to_string(),
            port: 443,
            auth_type: HysteriaV1AuthType::String,
            auth_payload: "correct-horse".to_string(),
            obfuscation: Some("obfs-secret".to_string()),
            protocol: HysteriaV1Protocol::Udp,
            up_mbps: 10,
            down_mbps: 50,
            sni: Some("hysteria.example".to_string()),
            alpn: Some("h3".to_string()),
        }
    }

    #[tokio::test]
    async fn connect_returns_unimplemented_for_valid_config() {
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, HysteriaV1Error::Unimplemented));
    }

    #[tokio::test]
    async fn connect_validates_before_stubbing() {
        let mut config = valid_config();
        config.auth_payload = String::new();
        let error = connect(&config).await.expect_err("invalid config must fail validation");
        assert!(matches!(error, HysteriaV1Error::EmptyAuth));
    }

    #[tokio::test]
    async fn tcp_connect_returns_unimplemented() {
        // The client is only constructible once the real engine lands; until
        // then exercise the stub through the public connect path.
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, HysteriaV1Error::Unimplemented));
    }
}
