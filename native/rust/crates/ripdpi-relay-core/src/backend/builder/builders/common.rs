use std::io;

use crate::config::ResolvedRelayFinalmaskConfig;

/// Fail closed on a missing or blank credential instead of silently deriving
/// an empty-string secret (`unwrap_or_default()`), which surfaces downstream
/// as an opaque authentication timeout rather than a config error. A
/// whitespace-only value counts as missing; the returned secret is the
/// ORIGINAL string, never trimmed.
pub(crate) fn required_secret<'a>(value: Option<&'a str>, what: &str) -> io::Result<&'a str> {
    match value {
        Some(secret) if !secret.trim().is_empty() => Ok(secret),
        _ => Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{what} is required"))),
    }
}

pub(crate) fn finalmask_config(config: &ResolvedRelayFinalmaskConfig) -> ripdpi_xhttp::FinalmaskConfig {
    ripdpi_xhttp::FinalmaskConfig {
        r#type: config.r#type.clone(),
        header_hex: config.header_hex.clone(),
        trailer_hex: config.trailer_hex.clone(),
        rand_range: config.rand_range.clone(),
        sudoku_seed: config.sudoku_seed.clone(),
        fragment_packets: config.fragment_packets,
        fragment_min_bytes: config.fragment_min_bytes,
        fragment_max_bytes: config.fragment_max_bytes,
    }
}

pub(crate) fn vless_reality_config(
    server: &str,
    server_port: i32,
    uuid: &str,
    server_name: &str,
    public_key: &str,
    short_id: &str,
    flow: &str,
    tls_fingerprint_profile: &str,
) -> io::Result<ripdpi_vless::config::VlessRealityConfig> {
    ripdpi_vless::config::VlessRealityConfig::from_strings(
        server,
        server_port,
        uuid,
        server_name,
        public_key,
        short_id,
        tls_fingerprint_profile,
    )
    .and_then(|config| config.with_flow_str(flow))
    .map_err(invalid_input)
}

pub(crate) fn invalid_input(error: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, error.to_string())
}

pub(crate) fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
