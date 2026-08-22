use ripdpi_relay_tls_transports::SocketProtectionPolicy;
use std::io;
use std::net::IpAddr;

use crate::backend::builder::BuildContext;
use crate::backend::builder::builders::common::vless_reality_config;
use crate::backend::{PooledRelayBackend, RelayBackend};
use crate::config::{ChainRelayConfig, RelayBackendConfig, ResolvedChainRelayHopConfig, ResolvedRelayRuntimeConfig};
use crate::protocols::{ChainHopConnector, ChainRelaySessionFactory};
use crate::telemetry::{ChainHopTelemetryState, QuicMigrationTelemetryState};

pub(crate) fn build(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    let RelayBackendConfig::ChainRelay(chain) = &config.backend else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "expected chain relay config"));
    };

    let ordered = chain.ordered_hops();
    ChainRelayConfig::validate_hop_count(ordered.len())?;
    let last_index = ordered.len() - 1;

    // Fold the ordered hop list into one connector per hop. Only the entry hop
    // (index 0) may carry the outbound bind IP — the relay data plane's
    // protect-equivalent — and only it opens a real outbound socket; every later
    // hop tunnels through the prior hop and is built without a bind IP so it can
    // never silently open an unbound, TUN-routed socket. The exit hop (last
    // index) additionally rejects QUIC-only kinds, which cannot terminate a
    // chain because nothing can tunnel through them.
    let hops = ordered
        .iter()
        .enumerate()
        .map(|(index, hop)| {
            let position = HopPosition::new(index, last_index);
            chain_hop_connector(
                hop,
                position,
                context.outbound_bind_ip,
                context.socket_protection,
                context.quic_migration.clone(),
            )
        })
        .collect::<io::Result<Vec<_>>>()?;

    let telemetry = ChainHopTelemetryState::default();
    let backend = PooledRelayBackend::new(
        ChainRelaySessionFactory { hops, outbound_bind_ip: context.outbound_bind_ip, telemetry: telemetry.clone() },
        context.pool_config,
        None,
    );

    Ok(RelayBackend::ChainRelay { backend, telemetry })
}

/// A hop's position within the ordered chain, used for role-specific validation
/// and diagnostics labels.
#[derive(Clone, Copy)]
struct HopPosition {
    index: usize,
    last_index: usize,
}

impl HopPosition {
    fn new(index: usize, last_index: usize) -> Self {
        Self { index, last_index }
    }

    fn is_entry(self) -> bool {
        self.index == 0
    }

    fn is_exit(self) -> bool {
        self.index == self.last_index
    }

    /// Human-readable label for error messages: `entry`, `exit`, or `hop N`.
    fn label(self) -> String {
        if self.is_entry() {
            "entry".to_string()
        } else if self.is_exit() {
            "exit".to_string()
        } else {
            format!("hop {}", self.index)
        }
    }
}

fn chain_hop_connector(
    hop: &ResolvedChainRelayHopConfig,
    position: HopPosition,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: SocketProtectionPolicy,
    quic_migration: QuicMigrationTelemetryState,
) -> io::Result<ChainHopConnector> {
    let label = position.label();
    // Only the entry hop creates a real outbound socket, so only it may carry an
    // outbound bind IP. Hand `None` to every later hop's factory.
    let hop_bind_ip = if position.is_entry() { outbound_bind_ip } else { None };
    let hop_socket_protection = if position.is_entry() { socket_protection } else { SocketProtectionPolicy::Inactive };

    match hop.kind.as_str() {
        "vless_reality" => {
            resolved_hop_vless_reality_config(hop, &label, hop_socket_protection).map(ChainHopConnector::VlessReality)
        }
        "masque" => resolved_hop_masque_config(hop, &label, hop_socket_protection).map(ChainHopConnector::Masque),
        "trojan" => {
            resolved_hop_trojan_config(hop, &label, hop_bind_ip, hop_socket_protection).map(ChainHopConnector::Trojan)
        }
        "anytls" => {
            resolved_hop_anytls_config(hop, &label, hop_bind_ip, hop_socket_protection).map(ChainHopConnector::AnyTls)
        }
        "shadowsocks" => resolved_hop_shadowsocks_factory(hop, &label, hop_bind_ip, hop_socket_protection)
            .map(ChainHopConnector::Shadowsocks),
        "shadowtls_v3" => resolved_hop_shadowtls_factory(hop, &label, hop_bind_ip, hop_socket_protection)
            .map(ChainHopConnector::ShadowTls),
        "hysteria2" => {
            reject_quic_non_entry(position, &label, "Hysteria2")?;
            resolved_hop_hysteria2_factory(hop, &label, hop_socket_protection, quic_migration)
                .map(ChainHopConnector::Hysteria2)
        }
        "tuic_v5" => {
            reject_quic_non_entry(position, &label, "TUIC")?;
            resolved_hop_tuic_factory(hop, &label, hop_bind_ip, hop_socket_protection, quic_migration)
                .map(ChainHopConnector::Tuic)
        }
        other => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("chain {label}: resolved hop kind {other} is not supported by chain relay"),
        )),
    }
}

/// QUIC-only kinds can serve only as the entry hop: nothing can tunnel through
/// a QUIC datagram session, so they cannot be an intermediate or exit hop.
fn reject_quic_non_entry(position: HopPosition, label: &str, kind: &str) -> io::Result<()> {
    if position.is_entry() {
        return Ok(());
    }
    Err(io::Error::new(
        io::ErrorKind::InvalidInput,
        format!("chain {label}: {kind} can only be the entry hop of a chain"),
    ))
}

fn resolved_hop_vless_reality_config(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<ripdpi_vless::config::VlessRealityConfig> {
    if hop.kind != "vless_reality" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("chain {label}: resolved hop kind {} is not supported by the fixed VLESS chain runtime", hop.kind),
        ));
    }
    if hop.vless_transport != "reality_tcp" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "chain {label}: resolved VLESS hop transport {} is not supported by chain relay",
                hop.vless_transport
            ),
        ));
    }
    let mut config = vless_reality_config(
        &hop.server,
        hop.server_port,
        hop.vless_uuid.as_deref().unwrap_or_default(),
        &hop.server_name,
        &hop.reality_public_key,
        &hop.reality_short_id,
        &hop.vless_flow,
        &hop.tls_fingerprint_profile,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: {error}")))?;
    config.socket_protection = socket_protection;
    Ok(config)
}

fn resolved_hop_masque_config(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<ripdpi_masque::config::MasqueConfig> {
    if hop.masque_url.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: MASQUE URL is required")));
    }
    let tcp_protocol = ripdpi_masque::config::MasqueTcpProtocol::from_wire(&hop.masque_tcp_protocol)
        .map_err(|error| io::Error::new(error.kind(), format!("chain {label}: {error}")))?;
    if tcp_protocol == ripdpi_masque::config::MasqueTcpProtocol::Http3 {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("chain {label}: masque_h3_tcp_unsupported; select HTTP/2 classic CONNECT"),
        ));
    }
    Ok(ripdpi_masque::config::MasqueConfig {
        socket_protection,
        url: hop.masque_url.clone(),
        proxy_socket_addr: None,
        tcp_protocol,
        use_http2_fallback: hop.masque_use_http2_fallback,
        auth_mode: hop.masque_auth_mode.clone(),
        auth_token: hop.masque_auth_token.clone(),
        client_certificate_chain_pem: hop.masque_client_certificate_chain_pem.clone(),
        client_private_key_pem: hop.masque_client_private_key_pem.clone(),
        cloudflare_geohash_header: hop.masque_cloudflare_geohash_header.clone(),
        privacy_pass_provider_url: hop.masque_privacy_pass_provider_url.clone(),
        privacy_pass_provider_auth_token: hop.masque_privacy_pass_provider_auth_token.clone(),
        tls_fingerprint_profile: hop.tls_fingerprint_profile.clone(),
        root_certificate_pem: None,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    })
}

fn resolved_hop_trojan_config(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<ripdpi_relay_tls_transports::TrojanClientConfig> {
    let server_port = u16::try_from(hop.server_port).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Trojan server port must fit u16"))
    })?;
    Ok(ripdpi_relay_tls_transports::TrojanClientConfig {
        server_host: hop.server.clone(),
        server_port,
        server_name: hop.server_name.clone(),
        password: hop.trojan_password.clone().unwrap_or_default(),
        tls_fingerprint_profile: hop.tls_fingerprint_profile.clone(),
        root_certificate_pem: hop.trojan_root_certificate_pem.clone(),
        socket_protection,
        outbound_bind_ip,
    })
}

fn resolved_hop_anytls_config(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<ripdpi_relay_tls_transports::AnyTlsClientConfig> {
    let server_port = u16::try_from(hop.server_port).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: AnyTLS server port must fit u16"))
    })?;
    Ok(ripdpi_relay_tls_transports::AnyTlsClientConfig {
        server_host: hop.server.clone(),
        server_port,
        server_name: hop.server_name.clone(),
        password: hop.anytls_password.clone().unwrap_or_default(),
        tls_fingerprint_profile: hop.tls_fingerprint_profile.clone(),
        root_certificate_pem: hop.anytls_root_certificate_pem.clone(),
        client_name: "ripdpi-anytls/0.1.0".to_string(),
        socket_protection,
        outbound_bind_ip,
    })
}

fn resolved_hop_shadowsocks_factory(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<ripdpi_relay_tls_transports::ShadowsocksSessionFactory> {
    let server_port = u16::try_from(hop.server_port).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Shadowsocks server port must fit u16"))
    })?;
    let method = hop.shadowsocks_method.clone().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Shadowsocks method is required"))
    })?;
    let password = hop.shadowsocks_password.clone().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Shadowsocks password is required"))
    })?;
    ripdpi_relay_tls_transports::ShadowsocksSessionFactory::new(
        hop.server.clone(),
        server_port,
        method,
        password,
        outbound_bind_ip,
        socket_protection,
    )
    .map_err(|error| io::Error::new(error.kind(), format!("chain {label}: {error}")))
}

fn resolved_hop_shadowtls_factory(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: SocketProtectionPolicy,
) -> io::Result<ripdpi_relay_tls_transports::ShadowTlsSessionFactory> {
    let inner = hop.shadow_tls_inner.as_ref().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: ShadowTLS inner relay config is required"))
    })?;
    Ok(ripdpi_relay_tls_transports::ShadowTlsSessionFactory {
        client_config: ripdpi_relay_tls_transports::ShadowTlsClientConfig {
            password: hop.shadow_tls_password.clone().unwrap_or_default(),
            server_name: hop.server_name.clone(),
            inner_profile_id: hop.shadow_tls_inner_profile_id.clone(),
            socket_protection,
            outbound_bind_ip,
        },
        outer_server: hop.server.clone(),
        outer_server_port: hop.server_port,
        inner: ripdpi_relay_tls_transports::ShadowTlsInnerConfig {
            kind: inner.kind.clone(),
            server: inner.server.clone(),
            server_port: inner.server_port,
            server_name: inner.server_name.clone(),
            reality_public_key: inner.reality_public_key.clone(),
            reality_short_id: inner.reality_short_id.clone(),
            vless_flow: inner.vless_flow.clone(),
            vless_transport: inner.vless_transport.clone(),
            xhttp_mode: inner.xhttp_mode.clone(),
            vless_uuid: inner.vless_uuid.clone(),
            tls_fingerprint_profile: inner.tls_fingerprint_profile.clone(),
        },
    })
}

fn resolved_hop_hysteria2_factory(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    socket_protection: SocketProtectionPolicy,
    quic_migration: QuicMigrationTelemetryState,
) -> io::Result<crate::protocols::Hysteria2SessionFactory> {
    let password = hop.hysteria_password.as_ref().filter(|value| !value.trim().is_empty()).ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: Hysteria2 password is required"))
    })?;
    let mut config =
        ripdpi_hysteria2::Config::from_parts(password.clone(), &hop.server, hop.server_port, hop.server_name.clone())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: {error}")))?;
    config.salamander_key = hop.hysteria_salamander_key.as_ref().filter(|value| !value.trim().is_empty()).cloned();
    config.quic_bind_low_port = false;
    config.quic_migrate_after_handshake = false;
    config.socket_protection = socket_protection;
    Ok(crate::protocols::Hysteria2SessionFactory { config, migration: quic_migration })
}

fn resolved_hop_tuic_factory(
    hop: &ResolvedChainRelayHopConfig,
    label: &str,
    outbound_bind_ip: Option<IpAddr>,
    socket_protection: SocketProtectionPolicy,
    quic_migration: QuicMigrationTelemetryState,
) -> io::Result<crate::protocols::TuicSessionFactory> {
    let server_port = u16::try_from(hop.server_port).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("chain {label}: TUIC server port must fit u16"))
    })?;
    Ok(crate::protocols::TuicSessionFactory {
        config: ripdpi_tuic::Config {
            server: hop.server.clone(),
            server_port: i32::from(server_port),
            server_name: hop.server_name.clone(),
            uuid: hop.tuic_uuid.clone().unwrap_or_default(),
            password: hop.tuic_password.clone().unwrap_or_default(),
            zero_rtt: hop.tuic_zero_rtt,
            congestion_control: hop.tuic_congestion_control.clone(),
            udp_enabled: false,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            socket_protection,
            outbound_bind_ip,
            keepalive_interval_ms: 0,
            root_certificate_pem: None,
        },
        migration: quic_migration,
    })
}
