use crate::error::{HysteriaError, Result};
use crate::hopping_policy::PortHoppingConfig;
use crate::port_hopping::PortHoppingWindow;
use crate::quic_transport::CertificatePinSha256;
use std::fmt;

#[derive(Clone)]
pub struct Config {
    pub auth: String,
    pub server_addr: String,
    pub server_name: String,
    pub insecure: bool,
    /// SHA-256 pin of the server leaf certificate (`pinSHA256`). With
    /// `insecure`, the pin replaces CA validation; without it, both apply.
    pub pin_sha256: Option<CertificatePinSha256>,
    pub salamander_key: Option<String>,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    /// Validated remote port set and exact fixed/random cadence.
    pub port_hopping: Option<PortHoppingConfig>,
}

impl fmt::Debug for Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Config")
            .field("auth", &"<redacted>")
            .field("server_addr", &self.server_addr)
            .field("server_name", &self.server_name)
            .field("insecure", &self.insecure)
            .field("pin_sha256", &self.pin_sha256.is_some())
            .field("salamander_key", &self.salamander_key.as_ref().map(|_| "<redacted>"))
            .field("quic_bind_low_port", &self.quic_bind_low_port)
            .field("quic_migrate_after_handshake", &self.quic_migrate_after_handshake)
            .field("socket_protection", &self.socket_protection)
            .field("port_hopping", &self.port_hopping)
            .finish()
    }
}

impl Config {
    pub fn from_parts(auth: String, server_host: &str, server_port: i32, server_name: String) -> Result<Self> {
        if server_host.trim().is_empty() {
            return Err(HysteriaError::InvalidAddress("missing hysteria host".to_string()));
        }
        let port = u16::try_from(server_port)
            .map_err(|_| HysteriaError::InvalidAddress("hysteria port must fit u16".to_string()))?;
        if port == 0 {
            return Err(HysteriaError::InvalidAddress("hysteria port must be nonzero".to_string()));
        }
        let host = server_host.trim();
        let server_addr = if host.contains(':') && !(host.starts_with('[') && host.ends_with(']')) {
            format!("[{host}]:{port}")
        } else {
            format!("{host}:{port}")
        };
        Ok(Self {
            auth,
            server_addr,
            server_name,
            insecure: false,
            pin_sha256: None,
            salamander_key: None,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            port_hopping: None,
        })
    }

    pub fn from_url(url: &str) -> Result<Self> {
        crate::config_uri::parse(url)
    }

    #[must_use]
    pub fn port_hopping_window(&self) -> PortHoppingWindow {
        self.port_hopping.as_ref().map_or(PortHoppingWindow::Disabled, PortHoppingConfig::window)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn remote_hopping_uri_accepts_multiple_server_ports() {
        Config::from_url("hysteria2://secret@localhost:20000,21000-22000/?hopInterval=5s")
            .expect("official multi-port authority must reach runtime");
    }

    #[test]
    fn remote_hopping_uri_rejects_conflicting_intervals() {
        assert!(
            Config::from_url("hysteria2://secret@localhost:443/?hopInterval=30&minHopInterval=5&maxHopInterval=10")
                .is_err()
        );
    }

    #[test]
    fn from_url_without_hop_knobs_disables_port_hopping() {
        let config = Config::from_url("hysteria2://secret@example.com:443/?sni=cover.example").expect("parse url");
        assert!(config.port_hopping.is_none());
        assert_eq!(config.port_hopping_window(), PortHoppingWindow::Disabled);
    }

    #[test]
    fn from_url_with_numeric_seconds_uses_fixed_hop_interval() {
        let config = Config::from_url("hysteria2://secret@example.com:443/?hopInterval=30").expect("parse url");

        assert_eq!(config.port_hopping_window(), PortHoppingWindow::Fixed(Duration::from_secs(30)));
    }

    #[test]
    fn from_url_with_window_randomizes_port_hopping() {
        let config = Config::from_url("hysteria2://secret@example.com:443/?minHopInterval=10&maxHopInterval=60")
            .expect("parse url");

        assert_eq!(
            config.port_hopping_window(),
            PortHoppingWindow::Randomized { min: Duration::from_secs(10), max: Duration::from_secs(60) }
        );
    }

    #[test]
    fn from_url_rejects_incomplete_or_reversed_windows() {
        for query in [
            "minHopInterval=10",
            "maxHopInterval=10",
            "minHopInterval=30&maxHopInterval=10",
            "hopInterval=30&hopInterval=60",
        ] {
            assert!(Config::from_url(&format!("hy2://secret@example.com:443/?{query}")).is_err());
        }
    }

    #[test]
    fn from_url_preserves_ipv6_multiport_and_exact_millisecond_cadence() {
        let config =
            Config::from_url("hy2://secret@[::1]:443,5000-5002/?minHopInterval=5001ms&maxHopInterval=6002ms").unwrap();
        assert_eq!(config.server_addr, "[::1]:443");
        assert_eq!(config.port_hopping.as_ref().unwrap().ports(), &[443, 5000, 5001, 5002]);
        assert_eq!(
            config.port_hopping_window(),
            PortHoppingWindow::Randomized { min: Duration::from_millis(5001), max: Duration::from_millis(6002) }
        );
    }

    #[test]
    fn from_url_defaults_to_443_with_optional_singleton_hopping() {
        let scalar = Config::from_url("hy2://secret@localhost/").unwrap();
        assert_eq!(scalar.server_addr, "localhost:443");
        assert!(scalar.port_hopping.is_none());
        let singleton = Config::from_url("hy2://secret@localhost/?hopInterval=5000ms").unwrap();
        assert_eq!(singleton.port_hopping.as_ref().unwrap().ports(), &[443]);
    }

    #[test]
    fn from_url_rejects_non_numeric_hop_interval() {
        let error = Config::from_url("hysteria2://secret@example.com:443/?minHopInterval=soon")
            .expect_err("non-numeric hop interval must fail");
        assert!(matches!(error, HysteriaError::InvalidAddress(_)));
    }

    #[test]
    fn from_url_rejects_zero_hop_interval() {
        let error = Config::from_url("hysteria2://secret@example.com:443/?hopInterval=0")
            .expect_err("zero hop interval must fail");
        assert!(matches!(error, HysteriaError::InvalidAddress(_)));
    }

    #[test]
    fn from_parts_preserves_reserved_auth_characters() {
        let auth = "p@ss:/?#%word".to_string();
        let config = Config::from_parts(auth.clone(), "2001:db8::1", 443, "cover.example".to_string())
            .expect("construct config without URL coercion");
        assert_eq!(auth, config.auth);
        assert_eq!("[2001:db8::1]:443", config.server_addr);
        assert_eq!("cover.example", config.server_name);
    }

    #[test]
    fn from_url_parses_the_pin_sha256_certificate_pin() {
        let pin = "AB:".repeat(31) + "AB";
        let config = Config::from_url(&format!("hysteria2://secret@example.com:443/?insecure=1&pinSHA256={pin}"))
            .expect("parse");
        assert!(config.insecure);
        assert_eq!(config.pin_sha256, Some([0xab; 32]));
    }

    #[test]
    fn from_url_rejects_a_malformed_pin_instead_of_dropping_it() {
        let error = Config::from_url("hysteria2://secret@example.com:443/?insecure=1&pinSHA256=abcd")
            .expect_err("malformed pin must fail");
        assert!(matches!(error, HysteriaError::InvalidCertificatePin(_)));
    }

    #[test]
    fn debug_redacts_auth_and_salamander_key() {
        let config =
            Config::from_url("hysteria2://debug-auth-secret@example.com:443/?obfs-password=debug-salamander-secret")
                .expect("parse url");

        let rendered = format!("{config:?}");

        assert!(!rendered.contains("debug-auth-secret"));
        assert!(!rendered.contains("debug-salamander-secret"));
        assert!(rendered.contains("<redacted>"));
    }
}
