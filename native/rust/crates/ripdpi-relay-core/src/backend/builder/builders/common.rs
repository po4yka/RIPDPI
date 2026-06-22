use std::io;

use crate::config::ResolvedRelayFinalmaskConfig;

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
