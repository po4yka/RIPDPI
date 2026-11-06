//! Product policy for the upstream Shadowsocks protocol implementation.
#![forbid(unsafe_code)]
#![warn(clippy::pedantic, clippy::redundant_clone)]

mod association;

use std::{
    io,
    net::{IpAddr, SocketAddr},
    sync::Arc,
};

use ripdpi_network_time::NetworkTimeProvider;
use shadowsocks::{
    config::{ReplayAttackPolicy, ServerAddr, ServerConfig, ServerType},
    context::{Context, SharedContext},
    crypto::CipherKind,
};

pub use association::UdpAssociation;

/// The app supports AEAD only. Upstream also accepts unencrypted and legacy methods.
///
/// # Errors
/// Returns `Unsupported` for a method outside the app allowlist.
pub fn method(name: &str) -> io::Result<CipherKind> {
    let name = name.trim().to_ascii_lowercase();
    match name.as_str() {
        "aes-128-gcm"
        | "aes-256-gcm"
        | "chacha20-ietf-poly1305"
        | "2022-blake3-aes-128-gcm"
        | "2022-blake3-aes-256-gcm"
        | "2022-blake3-chacha20-poly1305" => name.parse().map_err(|_| invalid_config()),
        _ => Err(io::Error::new(io::ErrorKind::Unsupported, "unsupported Shadowsocks method")),
    }
}

/// Validates all keys before a resolver or socket can be used. Errors contain no credentials.
///
/// # Errors
/// Returns an error for an empty endpoint, unsupported method, or invalid key material.
pub fn server_config(host: String, port: u16, name: &str, password: String) -> io::Result<ServerConfig> {
    let method = method(name)?;
    if host.is_empty() || port == 0 || password.trim().is_empty() {
        return Err(invalid_config());
    }
    let address = match host.parse::<IpAddr>() {
        Ok(ip) => ServerAddr::SocketAddr(SocketAddr::new(ip, port)),
        Err(_) => ServerAddr::DomainName(host, port),
    };
    ServerConfig::new(address, password, method).map_err(|_| invalid_config())
}

/// One factory shares replay state across all TCP connections.
#[must_use]
pub fn client_context() -> SharedContext {
    let mut context = Context::new(ServerType::Local);
    context.set_replay_attack_policy(ReplayAttackPolicy::Reject);
    context.set_timestamp_provider(Arc::new(|| NetworkTimeProvider::shared().now_unix_u64()));
    Arc::new(context)
}

fn invalid_config() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "invalid Shadowsocks server or credential")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_supported_aead_methods_are_accepted() {
        for name in ["none", "plain", "aes-128-cfb", "rc4-md5", "unknown"] {
            assert_eq!(method(name).unwrap_err().kind(), io::ErrorKind::Unsupported);
        }
        for name in ["aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305"] {
            let config = server_config("invalid.test".into(), 443, name, "test".into()).unwrap();
            assert!(!config.method().is_aead_2022());
        }
    }

    #[test]
    fn identity_chains_validate_every_key_and_never_echo_secrets() {
        let short = "AAECAwQFBgcICQoLDA0ODw==";
        let long = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
        for (name, key) in [("2022-blake3-aes-128-gcm", short), ("2022-blake3-aes-256-gcm", long)] {
            assert!(server_config("invalid.test".into(), 443, name, format!("{key}:{key}")).is_ok());
            for credential in [format!("bad:{key}"), format!("{key}:bad"), String::new()] {
                let error = server_config("invalid.test".into(), 443, name, credential).unwrap_err();
                assert_eq!(error.to_string(), "invalid Shadowsocks server or credential");
            }
        }
        assert!(server_config("invalid.test".into(), 443, "2022-blake3-chacha20-poly1305", long.into()).is_ok());
        assert!(
            server_config("invalid.test".into(), 443, "2022-blake3-chacha20-poly1305", format!("{long}:{long}"))
                .is_err()
        );
    }
}
