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
/// `relay_kind`-keyed facts. Every kind applies the profile UDP flag; both VLESS
/// kinds also apply transport and flow gates to that capability. Shadowsocks
/// UDP additionally requires an SS2022 method. The `Unsupported` catch-all has no descriptor row and reports the empty
/// [`RelayCapabilities::default`] profile.
pub(crate) fn planned_backend_capabilities(config: &ResolvedRelayRuntimeConfig) -> RelayCapabilities {
    let Some(descriptor) = relay_transport_descriptor_for(config) else {
        return RelayCapabilities::default();
    };
    let udp = config.common.udp_enabled
        && descriptor.udp
        && match &config.backend {
            RelayBackendConfig::VlessReality(vless) => vless_udp_supported(&vless.vless_transport, &vless.vless_flow),
            // Plain VLESS (TLS) has a native backend only for xHTTP.
            RelayBackendConfig::Vless(vless) => {
                vless.vless_transport == "xhttp" && vless_udp_supported(&vless.vless_transport, &vless.vless_flow)
            }
            RelayBackendConfig::Shadowsocks(shadowsocks) => {
                ripdpi_relay_tls_transports::ShadowsocksSessionFactory::supports_udp(&shadowsocks.method)
            }
            _ => true,
        };
    // Per-kind descriptors cannot express mux settings; match the built factories.
    let reusable = match RelayKind::from_config(config) {
        RelayKind::VlessReality { xhttp: true } => true,
        RelayKind::VlessReality { xhttp: false } => vless_mux_stream_limit(config).is_some(),
        RelayKind::Mieru => mieru_mux_stream_limit(config).is_some(),
        _ => descriptor.reusable,
    };
    RelayCapabilities { tcp: descriptor.tcp, udp, reusable }
}

/// VLESS transports with a native XUDP path, mirroring Kotlin
/// `NativeVlessCapabilities.supportsUdp`: Reality TCP keeps its Vision-only
/// XUDP carrier; xHTTP carries XUDP with the empty flow, as Xray does for UDP
/// over a non-Vision stream transport.
fn vless_udp_supported(transport: &str, flow: &str) -> bool {
    let flow = ripdpi_vless::addons::VlessFlow::parse(flow);
    match transport {
        "reality_tcp" => {
            matches!(flow, Ok(ripdpi_vless::addons::VlessFlow::Vision | ripdpi_vless::addons::VlessFlow::VisionUdp443))
        }
        "xhttp" => flow == Ok(ripdpi_vless::addons::VlessFlow::None),
        _ => false,
    }
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
/// Every other kind opens one carrier per lease. SOCKS admission already bounds
/// the carriers and their fds; a smaller lease cap would make new connections
/// wait behind idle long-lived ones until the connect deadline expires.
const SINGLE_CARRIER_MAX_ACTIVE_LEASES: usize = crate::runtime::MAX_CONCURRENT_SOCKS_SESSIONS;
const SINGLE_CARRIER_IDLE_TIMEOUT: Duration = Duration::from_secs(5);

pub(crate) fn pool_config_for_backend(config: &ResolvedRelayRuntimeConfig) -> RelayPoolConfig {
    match RelayKind::from_config(config) {
        RelayKind::Hysteria2 | RelayKind::TuicV5 | RelayKind::Masque | RelayKind::AnyTls => RelayPoolConfig {
            max_active_leases: QUIC_MULTIPLEXED_MAX_ACTIVE_LEASES,
            idle_timeout: QUIC_MULTIPLEXED_IDLE_TIMEOUT,
            max_active_datagram_leases: None,
        },
        RelayKind::CloudflareTunnel | RelayKind::Vless { xhttp: true } | RelayKind::VlessReality { xhttp: true } => {
            RelayPoolConfig {
                max_active_leases: STREAM_MULTIPLEXED_MAX_ACTIVE_LEASES,
                idle_timeout: STREAM_MULTIPLEXED_IDLE_TIMEOUT,
                max_active_datagram_leases: None,
            }
        }
        RelayKind::VlessReality { xhttp: false } if let Some(max_streams) = vless_mux_stream_limit(config) => {
            // One yamux carrier serves every lease, and yamux resets the whole
            // carrier when a stream opens past its limit: wait at the limit instead.
            // XUDP associations never use the yamux carrier, so they keep the
            // single-carrier budget and do not take stream slots.
            RelayPoolConfig {
                max_active_leases: max_streams.min(SINGLE_CARRIER_MAX_ACTIVE_LEASES),
                idle_timeout: STREAM_MULTIPLEXED_IDLE_TIMEOUT,
                max_active_datagram_leases: Some(SINGLE_CARRIER_MAX_ACTIVE_LEASES),
            }
        }
        RelayKind::Mieru if let Some(max_streams) = mieru_mux_stream_limit(config) => RelayPoolConfig {
            // One multiplexed carrier serves every lease, so the pool cap must
            // match the streams that carrier admits; a lower carrier limit would
            // make a lease wait forever in `open_stream`.
            max_active_leases: max_streams,
            idle_timeout: SINGLE_CARRIER_IDLE_TIMEOUT,
            max_active_datagram_leases: None,
        },
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
            max_active_datagram_leases: None,
        },
        RelayKind::Unsupported(_) => RelayPoolConfig::default(),
    }
}

/// The per-carrier stream limit of a VLESS Reality mux profile, or `None`
/// when mux is off (or invalid, which the backend build rejects).
/// Streams one Mieru carrier admits, or `None` for level `off` (one carrier per
/// lease, so it keeps the single-carrier budget) or an unparsable level.
fn mieru_mux_stream_limit(config: &ResolvedRelayRuntimeConfig) -> Option<usize> {
    let RelayBackendConfig::Mieru(mieru) = &config.backend else {
        return None;
    };
    let level = ripdpi_relay_tls_transports::MieruMux::parse(&mieru.multiplexing).ok()?;
    level.is_multiplexed().then(|| level.max_concurrent_streams())
}

fn vless_mux_stream_limit(config: &ResolvedRelayRuntimeConfig) -> Option<usize> {
    let RelayBackendConfig::VlessReality(vless) = &config.backend else {
        return None;
    };
    if vless.vless_mux_protocol.trim().is_empty() {
        return None;
    }
    ripdpi_vless::VlessMuxConfig::from_strings(
        &vless.vless_mux_protocol,
        vless.vless_mux_max_concurrent_streams,
        vless.vless_mux_per_connection_kbps,
        vless.vless_mux_padding_max,
    )
    .ok()
    .map(|mux| mux.max_concurrent_streams)
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
    // The SOCKS listener is meant only for this app's own clients: they present
    // the per-session RFC 1929 credentials when the config carries them. A
    // non-loopback bind host would publish the relay to the local network —
    // fail closed.
    let socks_host = config.common.local_socks_host.trim();
    let is_loopback =
        socks_host == "localhost" || socks_host.parse::<std::net::IpAddr>().is_ok_and(|ip| ip.is_loopback());
    if !is_loopback {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "relay SOCKS listener host `{socks_host}` must be a loopback address: the NO-AUTH listener must never be reachable off-device"
            ),
        ));
    }

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
    let kind = finalmask.r#type.trim();
    if kind.is_empty() || kind == "off" {
        return Ok(());
    }

    if !RelayKind::from_config(config).supports_finalmask() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("finalmask is unsupported for relay kind {} on its active transport", config.kind_id()),
        ));
    }

    // Cloudflare's edge, not an Xray server, terminates the TLS that the mask
    // wraps, and it has no inbound mask. Only fragment keeps the bytes valid TLS.
    if RelayKind::from_config(config) == RelayKind::CloudflareTunnel && kind != "fragment" {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!(
                "finalmask {} is unsupported for cloudflare_tunnel: the Cloudflare edge cannot remove it; use fragment",
                finalmask.r#type
            ),
        ));
    }

    crate::backend::builder::builders::finalmask_config(finalmask).validate()
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

#[cfg(test)]
mod finalmask_hex_tests {
    use super::*;

    fn config_with_finalmask(r#type: &str, header_hex: &str) -> ResolvedRelayRuntimeConfig {
        let mut config = crate::tests::sample_config("vless");
        config.common.finalmask = crate::config::ResolvedRelayFinalmaskConfig {
            r#type: r#type.to_string(),
            header_hex: header_hex.to_string(),
            ..crate::config::ResolvedRelayFinalmaskConfig::default()
        };
        config
    }

    #[test]
    fn header_custom_rejects_odd_length_and_non_hex_payloads() {
        for bad in ["abc", "zzzz"] {
            let error = validate_finalmask_config(&config_with_finalmask("header_custom", bad))
                .expect_err("invalid hex payload must fail closed");
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
            assert!(error.to_string().contains("even-length hex"), "unexpected error: {error}");
        }
        // Even-length valid hex passes; an absent payload with only a trailer
        // is validated the same way by symmetry.
        validate_finalmask_config(&config_with_finalmask("header_custom", "deadbeef")).expect("valid hex");
    }

    #[test]
    fn fragment_finalmask_rejects_counts_above_the_ui_limit() {
        for packets in [17, i32::MAX] {
            let mut config = config_with_finalmask("fragment", "");
            config.common.finalmask.fragment_packets = packets;
            config.common.finalmask.fragment_min_bytes = 1;
            config.common.finalmask.fragment_max_bytes = 1;
            assert!(validate_finalmask_config(&config).is_err(), "packet count {packets} must fail");
        }
    }

    #[test]
    fn active_finalmask_random_ranges_are_validated_at_admission() {
        for kind in ["header_custom", "noise"] {
            for range in ["invalid", "2-1", "0-16385"] {
                let mut config = config_with_finalmask(kind, "abcd");
                config.common.finalmask.rand_range = range.to_owned();
                assert!(validate_finalmask_config(&config).is_err(), "{kind} range {range} must fail");
            }
        }
    }
}
