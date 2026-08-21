use std::io;
use std::net::IpAddr;
use std::time::Duration;

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolConfig};

use crate::backend::RelayBackend;
use crate::config::{RelayBackendConfig, RelayKind, ResolvedRelayRuntimeConfig};
use crate::transport_descriptor::{RelayTransportDescriptor, relay_transport_descriptor, relay_transport_registration};

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
/// The generic TCP / UDP / connection-reuse capabilities are read from the
/// [`RelayTransportDescriptor`] table — the single source of truth for these
/// `relay_kind`-keyed facts. VLESS Reality then applies its profile-local
/// `udp_enabled`, transport, and flow gates to that kind capability. The
/// `Unsupported` catch-all has no descriptor row and reports the empty
/// [`RelayCapabilities::default`] profile.
pub(crate) fn planned_backend_capabilities(config: &ResolvedRelayRuntimeConfig) -> RelayCapabilities {
    let Some(descriptor) = relay_transport_descriptor_for(config) else {
        return RelayCapabilities::default();
    };
    let udp = if matches!(RelayKind::from_config(config), RelayKind::VlessReality { .. }) {
        let RelayBackendConfig::VlessReality(vless) = &config.backend else {
            return RelayCapabilities::default();
        };
        descriptor.udp
            && config.common.udp_enabled
            && vless.vless_transport == "reality_tcp"
            && matches!(vless.vless_flow.trim(), "xtls-rprx-vision" | "xtls-rprx-vision-udp443")
    } else {
        descriptor.udp
    };
    RelayCapabilities { tcp: descriptor.tcp, udp, reusable: descriptor.reusable }
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

/// Pool tiers by transport family. Kept out of the transport descriptor
/// (README) because sub-modes share a kind yet need different budgets; the
/// tiers below are the single source for those budgets.
///
/// QUIC-multiplexed families amortize one handshake across many leases, so
/// they sustain the widest concurrency and the longest idle retention.
const QUIC_MULTIPLEXED_MAX_ACTIVE_LEASES: usize = 64;
const QUIC_MULTIPLEXED_IDLE_TIMEOUT: Duration = Duration::from_secs(45);
/// Stream-multiplexing transports (xhttp, Cloudflare tunnel) also reuse a
/// carrier across leases, with a tighter budget tuned to their carrier cost.
const STREAM_MULTIPLEXED_MAX_ACTIVE_LEASES: usize = 48;
const STREAM_MULTIPLEXED_IDLE_TIMEOUT: Duration = Duration::from_secs(20);
/// Every other kind opens one carrier per lease; a small pool with a short
/// idle window keeps fd pressure bounded on mobile devices.
const SINGLE_CARRIER_MAX_ACTIVE_LEASES: usize = 16;
const SINGLE_CARRIER_IDLE_TIMEOUT: Duration = Duration::from_secs(5);

pub(crate) fn pool_config_for_backend(config: &ResolvedRelayRuntimeConfig) -> RelayPoolConfig {
    match RelayKind::from_config(config) {
        RelayKind::Hysteria2 | RelayKind::TuicV5 | RelayKind::Masque | RelayKind::AnyTls => RelayPoolConfig {
            max_active_leases: QUIC_MULTIPLEXED_MAX_ACTIVE_LEASES,
            idle_timeout: QUIC_MULTIPLEXED_IDLE_TIMEOUT,
        },
        RelayKind::CloudflareTunnel | RelayKind::Vless { xhttp: true } | RelayKind::VlessReality { xhttp: true } => {
            RelayPoolConfig {
                max_active_leases: STREAM_MULTIPLEXED_MAX_ACTIVE_LEASES,
                idle_timeout: STREAM_MULTIPLEXED_IDLE_TIMEOUT,
            }
        }
        RelayKind::Vless { xhttp: false }
        | RelayKind::VlessReality { xhttp: false }
        | RelayKind::Mieru
        | RelayKind::Ssh
        | RelayKind::ChainRelay
        | RelayKind::ShadowTlsV3
        | RelayKind::Trojan
        | RelayKind::Shadowsocks
        | RelayKind::Tor
        | RelayKind::NaiveProxy => RelayPoolConfig {
            max_active_leases: SINGLE_CARRIER_MAX_ACTIVE_LEASES,
            idle_timeout: SINGLE_CARRIER_IDLE_TIMEOUT,
        },
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
        RelayKind::Vless { xhttp: true } | RelayKind::VlessReality { xhttp: true } | RelayKind::CloudflareTunnel => {
            format!("{}:{}", config.common.server, config.common.server_port)
        }
        RelayKind::Masque => match &config.backend {
            RelayBackendConfig::Masque(masque) => url::Url::parse(&masque.url)
                .ok()
                .and_then(|url| {
                    let host = url.host_str()?;
                    let port = url.port_or_known_default()?;
                    Some(format!("{host}:{port}"))
                })
                .unwrap_or_else(|| format!("{}:{}", config.common.server, config.common.server_port)),
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
