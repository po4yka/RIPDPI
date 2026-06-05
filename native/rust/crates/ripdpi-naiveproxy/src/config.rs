use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use rustls::ClientConfig as RustlsClientConfig;

use crate::tls::default_tls_config;

/// `Debug` is implemented manually to redact the HTTP `Proxy-Authorization`
/// credentials (`username`, `password`). A derived `Debug` would expose
/// the basic-auth pair to any `tracing::debug!(?config)` call or panic
/// message. See `redacted_debug_omits_username_and_password` for the
/// contract. Mirrors the redaction style on `ripdpi-vless::VlessRealityConfig`
/// and `ripdpi-tuic::Config`.
#[derive(Clone)]
pub struct NaiveProxyConfig {
    pub(crate) listen: String,
    pub(crate) server: String,
    pub(crate) server_port: u16,
    pub(crate) server_name: String,
    pub(crate) username: Option<String>,
    pub(crate) password: Option<String>,
    pub(crate) path: Option<String>,
    pub(crate) tls_config: Arc<RustlsClientConfig>,
}

impl std::fmt::Debug for NaiveProxyConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Render the basic-auth pair as "<redacted>" when present and
        // "None" when absent, so the *presence* of credentials is still
        // legible without leaking the values themselves.
        let redact = |value: &Option<String>| if value.is_some() { "Some(<redacted>)" } else { "None" };
        let username = redact(&self.username);
        let password = redact(&self.password);
        f.debug_struct("NaiveProxyConfig")
            .field("listen", &self.listen)
            .field("server", &self.server)
            .field("server_port", &self.server_port)
            .field("server_name", &self.server_name)
            .field("username", &username)
            .field("password", &password)
            .field("path", &self.path)
            .finish_non_exhaustive()
    }
}

pub(crate) fn parse_config() -> io::Result<NaiveProxyConfig> {
    parse_config_from(pico_args::Arguments::from_env())
}

pub(crate) fn parse_config_from(mut args: pico_args::Arguments) -> io::Result<NaiveProxyConfig> {
    let listen = optional_value(&mut args, "--listen")?.unwrap_or_else(|| "127.0.0.1:11980".to_owned());
    let server_value: String = args
        .opt_value_from_str::<_, String>("--server")
        .map_err(invalid_args)?
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --server"))?;
    let server_port = args.opt_value_from_str::<_, String>("--server-port").map_err(invalid_args)?;
    let (server, server_port) = parse_server_endpoint(&server_value, server_port.as_deref())?;
    let server_name: String = args
        .opt_value_from_str::<_, String>("--server-name")
        .map_err(invalid_args)?
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --server-name"))?;
    let username = normalize_optional(optional_value(&mut args, "--username")?.as_deref());
    let password = normalize_optional(optional_value(&mut args, "--password")?.as_deref());
    if username.is_some() ^ password.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "NaiveProxy requires both username and password when authentication is configured",
        ));
    }
    let path = normalize_optional(optional_value(&mut args, "--path")?.as_deref());
    let remaining = args.finish();
    if !remaining.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unexpected arguments: {remaining:?}")));
    }

    Ok(NaiveProxyConfig {
        listen,
        server,
        server_port,
        server_name,
        username,
        password,
        path,
        tls_config: default_tls_config(),
    })
}

fn optional_value(args: &mut pico_args::Arguments, flag: &'static str) -> io::Result<Option<String>> {
    args.opt_value_from_str::<_, String>(flag).map_err(invalid_args)
}

fn invalid_args(error: pico_args::Error) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, error)
}

fn normalize_optional(value: Option<&str>) -> Option<String> {
    value.and_then(|raw| {
        let trimmed = raw.trim();
        if trimmed.is_empty() { None } else { Some(trimmed.to_owned()) }
    })
}

fn parse_server_endpoint(server_value: &str, server_port: Option<&str>) -> io::Result<(String, u16)> {
    if let Some(port) = server_port {
        return Ok((server_value.trim().to_owned(), parse_u16(Some(port), "--server-port")?));
    }

    if let Ok(address) = server_value.parse::<SocketAddr>() {
        return Ok((address.ip().to_string(), address.port()));
    }

    if let Some((host, port)) = split_host_port(server_value) {
        return Ok((host.to_owned(), port));
    }

    Err(io::Error::new(
        io::ErrorKind::InvalidInput,
        "NaiveProxy requires --server-port when --server is not a host:port authority",
    ))
}

fn parse_u16(value: Option<&str>, flag: &str) -> io::Result<u16> {
    let raw = value
        .map(str::trim)
        .filter(|entry| !entry.is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("missing {flag}")))?;
    raw.parse::<u16>()
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid {flag} value {raw}: {error}")))
}

fn split_host_port(authority: &str) -> Option<(&str, u16)> {
    let (host, port) = authority.rsplit_once(':')?;
    let parsed_port = port.parse::<u16>().ok()?;
    if host.is_empty() {
        return None;
    }

    Some((host.trim_matches(['[', ']']), parsed_port))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a config with credentials set, without touching the heavy
    /// local-network-fixture async path. `default_tls_config()` is the
    /// same TLS config the real `parse_config` attaches.
    fn sample_config() -> NaiveProxyConfig {
        NaiveProxyConfig {
            listen: "127.0.0.1:11980".to_owned(),
            server: "example.com".to_owned(),
            server_port: 443,
            server_name: "www.example.com".to_owned(),
            username: Some("naive-user".to_owned()),
            password: Some("naive-pass".to_owned()),
            path: Some("/proxy".to_owned()),
            tls_config: default_tls_config(),
        }
    }

    #[test]
    fn redacted_debug_omits_username_and_password() {
        let cfg = sample_config();
        let dbg = format!("{cfg:?}");
        assert!(!dbg.contains("naive-user"), "Debug output exposes username: {dbg}",);
        assert!(!dbg.contains("naive-pass"), "Debug output exposes password: {dbg}",);
        assert!(dbg.contains("<redacted>"), "redaction marker should be present: {dbg}");
        assert!(dbg.contains("example.com"), "server should remain visible: {dbg}");
    }

    #[test]
    fn redacted_debug_renders_absent_credentials_as_none() {
        let mut cfg = sample_config();
        cfg.username = None;
        cfg.password = None;
        let dbg = format!("{cfg:?}");
        assert!(dbg.contains("username: \"None\""), "absent username should render as None: {dbg}");
        assert!(dbg.contains("password: \"None\""), "absent password should render as None: {dbg}");
        assert!(!dbg.contains("<redacted>"), "no redaction marker when no credentials set: {dbg}");
    }

    /// Install a temporary subscriber that captures every event's
    /// field-set into a single joined string, run `emit`, and return
    /// the captured render. Mirrors the CaptureLayer pattern in
    /// `ripdpi-vless/src/config.rs`
    /// (`tracing_event_with_config_field_does_not_echo_uuid_or_key`).
    fn capture_events(emit: impl FnOnce()) -> String {
        use std::fmt;
        use std::sync::{Arc as StdArc, Mutex};

        use tracing::{Event, Subscriber};
        use tracing_subscriber::Registry;
        use tracing_subscriber::layer::{Context, Layer, SubscriberExt};

        struct CaptureLayer(StdArc<Mutex<Vec<String>>>);

        impl<S: Subscriber> Layer<S> for CaptureLayer {
            fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
                struct Visitor<'a>(&'a mut String);
                impl<'a> tracing::field::Visit for Visitor<'a> {
                    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn fmt::Debug) {
                        use fmt::Write;
                        let _ = write!(self.0, " {}={:?}", field.name(), value);
                    }
                }
                let mut rendered = String::new();
                event.record(&mut Visitor(&mut rendered));
                self.0.lock().expect("capture mutex").push(rendered);
            }
        }

        let captured = StdArc::new(Mutex::new(Vec::<String>::new()));
        let subscriber = Registry::default().with(CaptureLayer(StdArc::clone(&captured)));
        tracing::subscriber::with_default(subscriber, emit);
        captured.lock().expect("capture mutex").join("\n")
    }

    fn assert_no_credentials(joined: &str) {
        assert!(!joined.contains("naive-user"), "tracing event exposes username: {joined}",);
        assert!(!joined.contains("naive-pass"), "tracing event exposes password: {joined}",);
        assert!(joined.contains("<redacted>"), "tracing event must carry the redaction marker: {joined}");
    }

    /// Happy-path tracing-event-capture variant of the redaction
    /// contract: a representative `tracing::debug!(config = ?cfg, ...)`
    /// must not echo the basic-auth username/password.
    #[test]
    fn tracing_event_with_config_field_does_not_echo_credentials() {
        let cfg = sample_config();
        let joined = capture_events(|| {
            tracing::debug!(config = ?cfg, "NaiveProxy connecting");
        });
        assert_no_credentials(&joined);
    }

    /// Error-path variant: a misconfig / connect failure logging the
    /// config via `tracing::error!(config = ?cfg, "...")` must also be
    /// free of credentials.
    #[test]
    fn tracing_error_event_with_config_field_does_not_echo_credentials() {
        let cfg = sample_config();
        let joined = capture_events(|| {
            tracing::error!(config = ?cfg, error = "tls handshake failed", "NaiveProxy connect failed");
        });
        assert_no_credentials(&joined);
    }
}
