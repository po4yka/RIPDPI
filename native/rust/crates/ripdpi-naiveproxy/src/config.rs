use std::io::{self, Read};
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
    /// Path to the parent's `VpnService.protect()` Unix domain socket, read
    /// once from `RIPDPI_PROTECT_PATH`. `None` outside the VPN subprocess
    /// (desktop / non-VPN), in which case the upstream connect is a plain
    /// `TcpStream::connect`. See `ripdpi_subprocess_protect`.
    pub(crate) protect_socket_path: Option<String>,
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
            .field("protect_socket_path", &self.protect_socket_path)
            .finish_non_exhaustive()
    }
}

pub(crate) fn parse_config() -> io::Result<NaiveProxyConfig> {
    let stdin = io::stdin();
    let mut stdin = stdin.lock();
    parse_config_from_reader(pico_args::Arguments::from_env(), &mut stdin)
}

pub(crate) fn parse_config_from_reader(
    mut args: pico_args::Arguments,
    mut credentials_input: impl Read,
) -> io::Result<NaiveProxyConfig> {
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
    let use_credentials_stdin = args.contains("--credentials-stdin");
    let (username, password) =
        if use_credentials_stdin { read_credentials_from_stdin(&mut credentials_input)? } else { (None, None) };
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
        protect_socket_path: ripdpi_subprocess_protect::protect_path_from_env(),
    })
}

fn read_credentials_from_stdin(input: &mut impl Read) -> io::Result<(Option<String>, Option<String>)> {
    let mut payload = String::new();
    input.read_to_string(&mut payload)?;
    let mut lines = payload.lines();
    let username = decode_stdin_credential(lines.next(), "username")?;
    let password = decode_stdin_credential(lines.next(), "password")?;
    if lines.any(|line| !line.trim().is_empty()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "NaiveProxy credential stdin contains unexpected extra data",
        ));
    }
    Ok((normalize_optional(username.as_deref()), normalize_optional(password.as_deref())))
}

fn decode_stdin_credential(value: Option<&str>, field_name: &str) -> io::Result<Option<String>> {
    let Some(encoded) = value.map(str::trim).filter(|value| !value.is_empty()) else {
        return Ok(None);
    };
    let decoded = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, encoded).map_err(|error| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid NaiveProxy {field_name} credential encoding: {error}"),
        )
    })?;
    String::from_utf8(decoded).map(Some).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid NaiveProxy {field_name} credential text: {error}"))
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
    if !host.starts_with('[') && host.contains(':') {
        return None;
    }
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
            protect_socket_path: None,
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
    fn config_carries_protect_socket_path() {
        let mut cfg = sample_config();
        assert!(cfg.protect_socket_path.is_none(), "sample config must default to no protect path");
        cfg.protect_socket_path = Some("/data/local/tmp/ripdpi-protect.sock".to_owned());
        assert_eq!(cfg.protect_socket_path.as_deref(), Some("/data/local/tmp/ripdpi-protect.sock"));
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

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::split_host_port;

    /// Regression test (audit H4 siblings): a bare IPv6 authority must be
    /// rejected instead of yielding a colon-containing "host".
    #[test]
    fn split_host_port_rejects_bare_ipv6_authority() {
        assert_eq!(split_host_port("2001:db8::1"), None);
        assert_eq!(split_host_port("2001:db8::1:443"), None);
    }

    #[test]
    fn split_host_port_accepts_bracketed_ipv6_and_domain() {
        assert_eq!(split_host_port("[2001:db8::1]:443"), Some(("2001:db8::1", 443)));
        assert_eq!(split_host_port("example.com:443"), Some(("example.com", 443)));
    }
}
