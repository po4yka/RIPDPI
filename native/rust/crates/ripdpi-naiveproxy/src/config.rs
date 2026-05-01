use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use rustls::ClientConfig as RustlsClientConfig;

use crate::tls::default_tls_config;

#[derive(Clone)]
pub(crate) struct NaiveProxyConfig {
    pub(crate) listen: String,
    pub(crate) server: String,
    pub(crate) server_port: u16,
    pub(crate) server_name: String,
    pub(crate) username: Option<String>,
    pub(crate) password: Option<String>,
    pub(crate) path: Option<String>,
    pub(crate) tls_config: Arc<RustlsClientConfig>,
}

pub(crate) fn parse_args() -> HashMap<String, String> {
    let mut parsed = HashMap::new();
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        if !flag.starts_with("--") {
            continue;
        }

        let value = args.next().unwrap_or_default();
        parsed.insert(flag.trim_start_matches("--").to_owned(), value);
    }
    parsed
}

pub(crate) fn parse_config(args: HashMap<String, String>) -> io::Result<NaiveProxyConfig> {
    let listen = args.get("listen").cloned().unwrap_or_else(|| "127.0.0.1:11980".to_owned());
    let server_value = args
        .get("server")
        .cloned()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --server"))?;
    let (server, server_port) = parse_server_endpoint(&server_value, args.get("server-port"))?;
    let server_name = args
        .get("server-name")
        .cloned()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --server-name"))?;
    let username = normalize_optional(args.get("username"));
    let password = normalize_optional(args.get("password"));
    if username.is_some() ^ password.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "NaiveProxy requires both username and password when authentication is configured",
        ));
    }

    Ok(NaiveProxyConfig {
        listen,
        server,
        server_port,
        server_name,
        username,
        password,
        path: normalize_optional(args.get("path")),
        tls_config: default_tls_config(),
    })
}

fn normalize_optional(value: Option<&String>) -> Option<String> {
    value.and_then(|raw| {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_owned())
        }
    })
}

fn parse_server_endpoint(server_value: &str, server_port: Option<&String>) -> io::Result<(String, u16)> {
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

fn parse_u16(value: Option<&String>, flag: &str) -> io::Result<u16> {
    let raw = value
        .map(|entry| entry.trim())
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
