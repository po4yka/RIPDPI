#![forbid(unsafe_code)]

//! VMess outbound foundation for RIPDPI.
//!
//! VMess is a legacy V2Ray-family outbound protocol. This crate provides the
//! configuration, validation, and legacy-cipher rejection surface plus relay
//! registration, but the **AEAD wire engine is intentionally stubbed**: VMess
//! here is AEAD-only (`aes-128-gcm` / `chacha20-poly1305`), and a fully valid
//! config still terminates in [`VmessError::Unimplemented`] rather than
//! fabricating placeholder crypto. The real handshake is tracked under
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

    fn valid_config() -> VmessConfig {
        VmessConfig {
            server: "vmess.example".to_string(),
            port: 443,
            uuid: "b831381d-6324-4d53-ad4f-8cda48b30811".to_string(),
            alter_id: 0,
            security: VmessSecurity::Aes128Gcm,
            transport: VmessTransport::Tcp,
            ws_path: None,
            ws_host: None,
            grpc_service: None,
            h2_path: None,
            h2_host: None,
        }
    }

    #[tokio::test]
    async fn connect_returns_unimplemented_for_valid_config() {
        let error = connect(&valid_config()).await.expect_err("foundation connect is stubbed");
        assert!(matches!(error, VmessError::Unimplemented));
    }

    #[tokio::test]
    async fn connect_validates_before_stubbing() {
        let mut config = valid_config();
        config.alter_id = 16;
        let error = connect(&config).await.expect_err("invalid config must fail validation");
        assert!(matches!(error, VmessError::AlterIdUnsupported));
    }
}
