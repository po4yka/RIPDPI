use std::collections::HashMap;
use std::time::Duration;

use crate::error::{HysteriaError, Result};
use crate::port_hopping::PortHoppingWindow;

#[derive(Debug, Clone)]
pub struct Config {
    pub auth: String,
    pub server_addr: String,
    pub server_name: String,
    pub insecure: bool,
    pub salamander_key: Option<String>,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    /// Legacy fixed port-hopping cadence (`hopInterval`). When set and the
    /// `min`/`max` window below is unset, every hop waits exactly this long --
    /// the pre-Hysteria-2.8.0 behavior. `None` (the default) means no fixed
    /// cadence is configured.
    pub hop_interval: Option<Duration>,
    /// Inclusive lower bound of the Hysteria 2.8.0 randomized port-hopping
    /// window (`minHopInterval`). When either window bound is set the runtime
    /// randomizes each session's hop interval inside `[min, max]`.
    pub min_hop_interval: Option<Duration>,
    /// Inclusive upper bound of the Hysteria 2.8.0 randomized port-hopping
    /// window (`maxHopInterval`).
    pub max_hop_interval: Option<Duration>,
}

impl Config {
    pub fn from_parts(auth: String, server_host: &str, server_port: i32, server_name: String) -> Result<Self> {
        if server_host.trim().is_empty() {
            return Err(HysteriaError::InvalidAddress("missing hysteria host".to_string()));
        }
        let port = u16::try_from(server_port)
            .map_err(|_| HysteriaError::InvalidAddress("hysteria port must fit u16".to_string()))?;
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
            salamander_key: None,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            hop_interval: None,
            min_hop_interval: None,
            max_hop_interval: None,
        })
    }

    pub fn from_url(url: &str) -> Result<Self> {
        let parsed = url::Url::parse(&url.replace("hysteria2://", "http://"))?;
        let host =
            parsed.host_str().ok_or_else(|| HysteriaError::InvalidAddress("missing hysteria host".to_string()))?;
        let port = parsed.port().ok_or_else(|| HysteriaError::InvalidAddress("missing hysteria port".to_string()))?;
        let query: HashMap<String, String> = parsed.query_pairs().into_owned().collect();

        Ok(Self {
            auth: parsed.username().to_string(),
            server_addr: format!("{host}:{port}"),
            server_name: query.get("sni").cloned().unwrap_or_else(|| host.to_string()),
            insecure: query.get("insecure").is_some_and(|value| value == "1"),
            salamander_key: query.get("obfs-password").cloned(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            hop_interval: parse_hop_interval(&query, "hopInterval")?,
            min_hop_interval: parse_hop_interval(&query, "minHopInterval")?,
            max_hop_interval: parse_hop_interval(&query, "maxHopInterval")?,
        })
    }

    /// Resolve the three port-hopping knobs into a single hopping policy.
    ///
    /// * a configured `minHopInterval` / `maxHopInterval` window yields
    ///   [`PortHoppingWindow::Randomized`] (Hysteria 2.8.0 behavior);
    /// * a bare `hopInterval` yields [`PortHoppingWindow::Fixed`] -- the
    ///   pre-2.8.0 fixed cadence, preserved for backward compatibility;
    /// * all three unset yields [`PortHoppingWindow::Disabled`].
    #[must_use]
    pub fn port_hopping_window(&self) -> PortHoppingWindow {
        PortHoppingWindow::resolve(self.hop_interval, self.min_hop_interval, self.max_hop_interval)
    }
}

/// Parse a hop-interval query parameter expressed in whole seconds, matching
/// the Hysteria CLI's `hopInterval` / `minHopInterval` / `maxHopInterval`
/// units. An absent key yields `None` (no override); a present but
/// non-numeric or zero value is an [`HysteriaError::InvalidAddress`] so a
/// malformed profile fails loudly instead of silently disabling hopping.
fn parse_hop_interval(query: &HashMap<String, String>, key: &str) -> Result<Option<Duration>> {
    let Some(raw) = query.get(key) else {
        return Ok(None);
    };
    let seconds: u64 = raw
        .parse()
        .map_err(|_| HysteriaError::InvalidAddress(format!("invalid {key}: expected whole seconds, got {raw:?}")))?;
    if seconds == 0 {
        return Err(HysteriaError::InvalidAddress(format!("invalid {key}: must be greater than zero")));
    }
    Ok(Some(Duration::from_secs(seconds)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_url_without_hop_knobs_disables_port_hopping() {
        let config = Config::from_url("hysteria2://secret@example.com:443/?sni=cover.example").expect("parse url");
        assert_eq!(config.hop_interval, None);
        assert_eq!(config.min_hop_interval, None);
        assert_eq!(config.max_hop_interval, None);
        assert_eq!(config.port_hopping_window(), PortHoppingWindow::Disabled);
    }

    #[test]
    fn from_url_with_fixed_hop_interval_is_backward_compatible() {
        let config = Config::from_url("hysteria2://secret@example.com:443/?hopInterval=30").expect("parse url");
        assert_eq!(config.hop_interval, Some(Duration::from_secs(30)));
        assert_eq!(config.min_hop_interval, None);
        assert_eq!(config.max_hop_interval, None);
        assert_eq!(config.port_hopping_window(), PortHoppingWindow::Fixed(Duration::from_secs(30)));
    }

    #[test]
    fn from_url_with_window_randomizes_port_hopping() {
        let config = Config::from_url("hysteria2://secret@example.com:443/?minHopInterval=10&maxHopInterval=60")
            .expect("parse url");
        assert_eq!(config.min_hop_interval, Some(Duration::from_secs(10)));
        assert_eq!(config.max_hop_interval, Some(Duration::from_secs(60)));
        assert_eq!(
            config.port_hopping_window(),
            PortHoppingWindow::Randomized { min: Duration::from_secs(10), max: Duration::from_secs(60) }
        );
    }

    #[test]
    fn from_url_window_takes_precedence_over_fixed_hop_interval() {
        let config =
            Config::from_url("hysteria2://secret@example.com:443/?hopInterval=30&minHopInterval=10&maxHopInterval=60")
                .expect("parse url");
        assert_eq!(config.hop_interval, Some(Duration::from_secs(30)));
        assert!(config.port_hopping_window().is_randomized());
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
}
