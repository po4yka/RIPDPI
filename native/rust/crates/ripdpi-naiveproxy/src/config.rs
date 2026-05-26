use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use rustls::ClientConfig as RustlsClientConfig;

use crate::tls::default_tls_config;

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
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_owned())
        }
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
