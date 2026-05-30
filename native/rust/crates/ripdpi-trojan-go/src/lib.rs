#![forbid(unsafe_code)]

//! Trojan-Go outbound foundation for RIPDPI.
//!
//! Trojan-Go is a **legacy** Trojan-family outbound protocol. RIPDPI carries it
//! only to keep existing subscriptions working through the migration window;
//! the sunset target is **2027-06-30 (remove once subscriptions migrate off
//! Trojan-Go)**. This crate provides the configuration, validation, password
//! redaction, and relay-registration surface, but the **SMUX / WebSocket wire
//! engine and the password-hash handshake are intentionally stubbed**: a fully
//! valid config still terminates in [`TrojanGoError::Unimplemented`] rather than
//! fabricating a placeholder handshake or password hash. The real engine is
//! tracked under `epic-extended-outbound-protocol-support`.

mod client;
mod config;
mod error;

pub use client::*;
pub use config::*;
pub use error::*;

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> TrojanGoConfig {
        TrojanGoConfig {
            server: "trojan-go.example".to_string(),
            port: 443,
            password: "correct-horse".to_string(),
            sni: Some("trojan-go.example".to_string()),
            ws_path: None,
            ws_host: None,
            mux: TrojanGoMux::Off,
            inner: TrojanGoInner::None,
        }
    }

    #[tokio::test]
    async fn connect_returns_unimplemented_for_valid_config() {
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, TrojanGoError::Unimplemented));
    }

    #[tokio::test]
    async fn connect_validates_before_stubbing() {
        let mut config = valid_config();
        config.password = String::new();
        let error = connect(&config).await.expect_err("invalid config must fail validation");
        assert!(matches!(error, TrojanGoError::EmptyPassword));
    }

    #[tokio::test]
    async fn tcp_connect_returns_unimplemented() {
        // The client is only constructible once the real engine lands; until
        // then exercise the stub through the public connect path.
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, TrojanGoError::Unimplemented));
    }
}
