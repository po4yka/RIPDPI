use std::io;
use std::net::IpAddr;
use std::time::Duration;

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolConfig};

use crate::{RelayBackend, ResolvedRelayRuntimeConfig};

pub(crate) fn planned_backend_capabilities(config: &ResolvedRelayRuntimeConfig) -> RelayCapabilities {
    match config.kind.as_str() {
        "hysteria2" | "masque" | "tuic_v5" => RelayCapabilities { tcp: true, udp: true, reusable: true },
        "cloudflare_tunnel" => RelayCapabilities { tcp: true, udp: false, reusable: true },
        "shadowtls_v3" | "naiveproxy" | "vless_reality" | "chain_relay" => {
            RelayCapabilities { tcp: true, udp: false, reusable: false }
        }
        _ => RelayCapabilities::default(),
    }
}

pub(crate) fn planned_backend_fallback_mode(config: &ResolvedRelayRuntimeConfig) -> Option<String> {
    match config.kind.as_str() {
        "naiveproxy" => Some("subprocess".to_string()),
        _ if planned_backend_capabilities(config).tcp || planned_backend_capabilities(config).udp => None,
        other => Some(format!("unsupported:{other}")),
    }
}

pub(crate) fn pool_config_for_backend(config: &ResolvedRelayRuntimeConfig) -> RelayPoolConfig {
    match config.kind.as_str() {
        "hysteria2" | "tuic_v5" | "masque" => {
            RelayPoolConfig { max_active_leases: 64, idle_timeout: Duration::from_secs(45) }
        }
        "cloudflare_tunnel" => RelayPoolConfig { max_active_leases: 48, idle_timeout: Duration::from_secs(20) },
        "vless_reality" if config.vless_transport == "xhttp" => {
            RelayPoolConfig { max_active_leases: 48, idle_timeout: Duration::from_secs(20) }
        }
        "vless_reality" | "chain_relay" | "shadowtls_v3" | "naiveproxy" => {
            RelayPoolConfig { max_active_leases: 16, idle_timeout: Duration::from_secs(5) }
        }
        _ => RelayPoolConfig::default(),
    }
}

pub(crate) fn describe_upstream(config: &ResolvedRelayRuntimeConfig) -> String {
    match config.kind.as_str() {
        "chain_relay" => format!(
            "{}:{} -> {}:{}",
            config.chain_entry_server, config.chain_entry_port, config.chain_exit_server, config.chain_exit_port,
        ),
        "vless_reality" if config.vless_transport == "xhttp" => {
            format!("{}:{}{}", config.server, config.server_port, normalized_xhttp_path(config))
        }
        "cloudflare_tunnel" => format!("{}:{}{}", config.server, config.server_port, normalized_xhttp_path(config)),
        "masque" => config.masque_url.clone(),
        _ => format!("{}:{}", config.server, config.server_port),
    }
}

pub(crate) fn describe_runtime_health(state: &str, backend: Option<&RelayBackend>) -> String {
    let Some(pool_health) = backend.and_then(RelayBackend::pool_health) else {
        return state.to_string();
    };
    format!(
        "{state} (pool busy={} idle={} evictions={} backpressure={})",
        pool_health.busy_streams, pool_health.idle_streams, pool_health.evictions, pool_health.backpressure_events,
    )
}

pub(crate) fn normalized_xhttp_path(config: &ResolvedRelayRuntimeConfig) -> String {
    let trimmed = config.xhttp_path.trim().trim_matches('/');
    if trimmed.is_empty() {
        "/".to_owned()
    } else {
        format!("/{trimmed}")
    }
}

pub(crate) fn validate_runtime_config(config: &ResolvedRelayRuntimeConfig, backend: &RelayBackend) -> io::Result<()> {
    let outbound_bind_ip = parse_outbound_bind_ip(&config.outbound_bind_ip)?;
    if config.udp_enabled && !backend.udp_capable() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support UDP ASSOCIATE", config.kind),
        ));
    }

    if outbound_bind_ip.is_some() && matches!(config.kind.as_str(), "hysteria2" | "masque") {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support outbound bind IP", config.kind),
        ));
    }

    validate_finalmask_config(config)?;

    Ok(())
}

pub(crate) fn validate_finalmask_config(config: &ResolvedRelayRuntimeConfig) -> io::Result<()> {
    let finalmask = &config.finalmask;
    if finalmask.r#type.trim().is_empty() || finalmask.r#type == "off" {
        return Ok(());
    }

    let supported_kind =
        config.kind == "cloudflare_tunnel" || (config.kind == "vless_reality" && config.vless_transport == "xhttp");
    if !supported_kind {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("finalmask is unsupported for relay kind {} on its active transport", config.kind),
        ));
    }

    match finalmask.r#type.as_str() {
        "header_custom" => {
            if finalmask.header_hex.trim().is_empty() && finalmask.trailer_hex.trim().is_empty() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "finalmask header_custom requires header or trailer hex",
                ));
            }
        }
        "sudoku" => {
            if finalmask.sudoku_seed.trim().is_empty() {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "finalmask sudoku requires sudoku_seed"));
            }
        }
        "fragment" => {
            if finalmask.fragment_packets <= 0
                || finalmask.fragment_min_bytes <= 0
                || finalmask.fragment_max_bytes <= 0
                || finalmask.fragment_min_bytes > finalmask.fragment_max_bytes
            {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "finalmask fragment requires a positive packet count and byte range",
                ));
            }
        }
        "noise" => {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "finalmask noise is not available for xHTTP transports",
            ));
        }
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported finalmask type {}", finalmask.r#type),
            ));
        }
    }

    Ok(())
}

pub(crate) fn parse_outbound_bind_ip(value: &str) -> io::Result<Option<IpAddr>> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    trimmed.parse::<IpAddr>().map(Some).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid relay outbound_bind_ip {trimmed}: {error}"))
    })
}
