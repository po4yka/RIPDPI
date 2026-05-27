use std::io;
use std::net::IpAddr;
use std::time::Duration;

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolConfig};

use crate::backend::RelayBackend;
use crate::config::{RelayBackendConfig, RelayKind, ResolvedRelayRuntimeConfig};
use crate::transport_descriptor::{relay_transport_descriptor, relay_transport_registration, RelayTransportDescriptor};

/// The [`RelayTransportDescriptor`] for `config`'s relay kind, or `None` for
/// the `Unsupported` catch-all (which has no descriptor row).
///
/// `RelayKind::from_config` structurally separates the `Unsupported` arm, so a
/// genuinely unknown `relay_kind` string never resolves to a descriptor. Every
/// concrete kind's `kind_id()` is a descriptor key; the
/// `relay_transport_descriptors_cover_every_kind_exactly_once` test pins that.
fn relay_transport_descriptor_for(config: &ResolvedRelayRuntimeConfig) -> Option<&'static RelayTransportDescriptor> {
    match RelayKind::from_config(config) {
        RelayKind::Unsupported(_) => None,
        _ => relay_transport_descriptor(config.kind_id()),
    }
}

/// The relay backend's planned SOCKS capability profile.
///
/// The generic TCP / UDP / connection-reuse capabilities are read straight
/// from the [`RelayTransportDescriptor`] table — the single source of truth
/// for these `relay_kind`-keyed facts. The `Unsupported` catch-all has no
/// descriptor row and reports the empty [`RelayCapabilities::default`] profile.
pub(crate) fn planned_backend_capabilities(config: &ResolvedRelayRuntimeConfig) -> RelayCapabilities {
    let Some(descriptor) = relay_transport_descriptor_for(config) else {
        return RelayCapabilities::default();
    };
    RelayCapabilities { tcp: descriptor.tcp, udp: descriptor.udp, reusable: descriptor.reusable }
}

/// The relay backend's out-of-process fallback mode, or `None` for an
/// in-process kind.
///
/// A registered kind reports its registration's `fallback_mode`
/// (`Some("subprocess")` for NaiveProxy, `None` for every in-process kind). A
/// kind with no registration is an `Unsupported` / `off` / unknown kind and
/// reports the `unsupported:<kind>` marker — unchanged from the pre-registry
/// `match RelayKind` form.
pub(crate) fn planned_backend_fallback_mode(config: &ResolvedRelayRuntimeConfig) -> Option<String> {
    if let Some(registration) = relay_transport_registration(config.kind_id()) {
        return registration.fallback_mode.map(str::to_string);
    }
    match RelayKind::from_config(config) {
        RelayKind::Unsupported(kind) => Some(format!("unsupported:{kind}")),
        _ => None,
    }
}

pub(crate) fn pool_config_for_backend(config: &ResolvedRelayRuntimeConfig) -> RelayPoolConfig {
    match RelayKind::from_config(config) {
        RelayKind::Hysteria2 | RelayKind::TuicV5 | RelayKind::Masque | RelayKind::AnyTls => {
            RelayPoolConfig { max_active_leases: 64, idle_timeout: Duration::from_secs(45) }
        }
        RelayKind::CloudflareTunnel | RelayKind::VlessReality { xhttp: true } => {
            RelayPoolConfig { max_active_leases: 48, idle_timeout: Duration::from_secs(20) }
        }
        RelayKind::VlessReality { xhttp: false }
        | RelayKind::ChainRelay
        | RelayKind::ShadowTlsV3
        | RelayKind::Trojan
        | RelayKind::Shadowsocks
        | RelayKind::NaiveProxy => RelayPoolConfig { max_active_leases: 16, idle_timeout: Duration::from_secs(5) },
        RelayKind::Unsupported(_) => RelayPoolConfig::default(),
    }
}

pub(crate) fn describe_upstream(config: &ResolvedRelayRuntimeConfig) -> String {
    match RelayKind::from_config(config) {
        RelayKind::ChainRelay => match &config.backend {
            RelayBackendConfig::ChainRelay(chain) => {
                format!("{}:{} -> {}:{}", chain.entry_server, chain.entry_port, chain.exit_server, chain.exit_port,)
            }
            _ => format!("{}:{}", config.common.server, config.common.server_port),
        },
        RelayKind::VlessReality { xhttp: true } => {
            format!("{}:{}{}", config.common.server, config.common.server_port, normalized_xhttp_path(config))
        }
        RelayKind::CloudflareTunnel => {
            format!("{}:{}{}", config.common.server, config.common.server_port, normalized_xhttp_path(config))
        }
        RelayKind::Masque => match &config.backend {
            RelayBackendConfig::Masque(masque) => masque.url.clone(),
            _ => format!("{}:{}", config.common.server, config.common.server_port),
        },
        _ => format!("{}:{}", config.common.server, config.common.server_port),
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
    let trimmed = config.xhttp_path().trim().trim_matches('/');
    if trimmed.is_empty() {
        "/".to_owned()
    } else {
        format!("/{trimmed}")
    }
}

pub(crate) fn validate_runtime_config(config: &ResolvedRelayRuntimeConfig, backend: &RelayBackend) -> io::Result<()> {
    let outbound_bind_ip = parse_outbound_bind_ip(&config.common.outbound_bind_ip)?;
    if config.common.udp_enabled && !backend.udp_capable() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support UDP ASSOCIATE", config.kind_id()),
        ));
    }

    // Outbound-bind-IP support is a generic, `relay_kind`-keyed capability:
    // the descriptor table is the source of truth. An `Unsupported` kind has
    // no descriptor row and keeps the historical permissive default (allowed).
    let supports_outbound_bind_ip =
        relay_transport_descriptor_for(config).is_none_or(|descriptor| descriptor.supports_outbound_bind_ip);
    if outbound_bind_ip.is_some() && !supports_outbound_bind_ip {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support outbound bind IP", config.kind_id()),
        ));
    }

    validate_finalmask_config(config)?;

    Ok(())
}

pub(crate) fn validate_finalmask_config(config: &ResolvedRelayRuntimeConfig) -> io::Result<()> {
    let finalmask = &config.common.finalmask;
    if finalmask.r#type.trim().is_empty() || finalmask.r#type == "off" {
        return Ok(());
    }

    if !RelayKind::from_config(config).supports_finalmask() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("finalmask is unsupported for relay kind {} on its active transport", config.kind_id()),
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
            let Some((min, max)) = parse_rand_range(&finalmask.rand_range) else {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "finalmask noise requires rand_range in min-max format",
                ));
            };
            if min > max {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "finalmask noise rand_range minimum must not exceed maximum",
                ));
            }
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

fn parse_rand_range(value: &str) -> Option<(usize, usize)> {
    let (min, max) = value.trim().split_once('-')?;
    Some((min.trim().parse().ok()?, max.trim().parse().ok()?))
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
