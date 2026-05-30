#![forbid(unsafe_code)]

//! Mieru outbound foundation for RIPDPI.
//!
//! Mieru ([enfein/mieru](https://github.com/enfein/mieru)) is a socks5/proxy
//! protocol built on a **custom UDP/TCP carrier with replay protection**: the
//! handshake mixes a clock-synced nonce so a captured packet cannot be replayed
//! later. This crate provides the configuration, validation, password
//! redaction, and relay-registration surface, but the **custom UDP/TCP session
//! and the replay-resistant handshake are intentionally stubbed**: a fully valid
//! config still terminates in [`MieruError::Unimplemented`] rather than
//! fabricating a placeholder handshake. The real engine — including a
//! network-time source for the replay clock (NOT `System` time) — is tracked
//! under `epic-extended-outbound-protocol-support`.

mod client;
mod config;
mod error;

pub use client::*;
pub use config::*;
pub use error::*;

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_config() -> MieruConfig {
        MieruConfig {
            server: "mieru.example".to_string(),
            port: 443,
            username: "alice".to_string(),
            password: "correct-horse".to_string(),
            protocol: MieruProtocol::Tcp,
            multiplexing: MieruMux::Middle,
            mtu: 1400,
        }
    }

    #[tokio::test]
    async fn connect_returns_unimplemented_for_valid_config() {
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, MieruError::Unimplemented));
    }

    #[tokio::test]
    async fn connect_validates_before_stubbing() {
        let mut config = valid_config();
        config.password = String::new();
        let error = connect(&config).await.expect_err("invalid config must fail validation");
        assert!(matches!(error, MieruError::EmptyPassword));
    }

    #[tokio::test]
    async fn tcp_connect_returns_unimplemented() {
        // The client is only constructible once the real engine lands; until
        // then exercise the stub through the public connect path.
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, MieruError::Unimplemented));
    }
}
