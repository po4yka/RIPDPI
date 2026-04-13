use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::str::FromStr;
use std::{fmt, mem};

use ripdpi_config::{
    parse_http_fake_profile as parse_http_fake_profile_id, parse_tcp_flag_mask,
    parse_tls_fake_profile as parse_tls_fake_profile_id, parse_udp_fake_profile as parse_udp_fake_profile_id,
    validate_tcp_flag_masks, ActivationFilter, DesyncGroup, DesyncMode, EntropyMode, FakePacketSource, IpIdMode,
    NumericRange, OffsetBase, OffsetExpr, QuicFakeProfile, QuicInitialMode, RotationCandidate, RotationPolicy,
    RuntimeConfig, SeqOverlapFakeMode, StartupEnv, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind,
    UpstreamSocksConfig, WsizeConfig, AUTO_RECONN, AUTO_SORT, DETECT_CONNECT, DETECT_HTTP_LOCAT, DETECT_TLS_ERR,
    DETECT_TORST, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
};
use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};
use ripdpi_packets::{
    IS_HTTP, IS_HTTPS, IS_UDP, MH_DMIX, MH_HMIX, MH_HOSTEXTRASPACE, MH_HOSTPAD, MH_HOSTTAB, MH_METHODEOL,
    MH_METHODSPACE, MH_SPACE, MH_UNIXEOL,
};
use serde::de::{Deserializer, IgnoredAny, MapAccess, Visitor};

use crate::presets;
use crate::types::{
    ProxyConfigError, ProxyConfigPayload, ProxyLogContext, ProxyRuntimeContext, ProxyUiActivationFilter, ProxyUiConfig,
    ProxyUiNumericRange, RuntimeConfigEnvelope, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SNI_MODE_RANDOMIZED, FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO, FAKE_TLS_SOURCE_PROFILE, HOSTS_BLACKLIST,
    HOSTS_DISABLE, HOSTS_WHITELIST, IP_ID_MODE_RND, IP_ID_MODE_SEQ, IP_ID_MODE_SEQGROUP, IP_ID_MODE_ZERO,
    RELAY_KIND_OFF, SEQOVL_DEFAULT_OVERLAP_SIZE, SEQOVL_FAKE_MODE_PROFILE, SEQOVL_FAKE_MODE_RAND,
    TLS_RANDREC_DEFAULT_FRAGMENT_COUNT, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE,
    WARP_ROUTE_MODE_RULES,
};

const WARP_CONTROL_PLANE_HOSTS: &[&str] = &[
    "api.cloudflareclient.com",
    "connectivity.cloudflareclient.com",
    "engage.cloudflareclient.com",
    "downloads.cloudflareclient.com",
    "zero-trust-client.cloudflareclient.com",
    "pkg.cloudflareclient.com",
    "consumer-masque.cloudflareclient.com",
];

fn trim_non_empty(opt: Option<String>) -> Option<String> {
    opt.map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

fn sanitize_runtime_context(runtime_context: Option<ProxyRuntimeContext>) -> Option<ProxyRuntimeContext> {
    let mut runtime_context = runtime_context?;
    runtime_context.encrypted_dns = runtime_context.encrypted_dns.and_then(|mut value| {
        value.protocol = value.protocol.trim().to_ascii_lowercase();
        value.host = value.host.trim().to_string();
        if value.host.is_empty() {
            return None;
        }
        value.port = if value.port == 0 { 443 } else { value.port };
        value.tls_server_name = trim_non_empty(value.tls_server_name);
        value.bootstrap_ips = value
            .bootstrap_ips
            .into_iter()
            .map(|entry| entry.trim().to_string())
            .filter(|entry| !entry.is_empty())
            .collect();
        value.doh_url = trim_non_empty(value.doh_url);
        value.dnscrypt_provider_name = trim_non_empty(value.dnscrypt_provider_name);
        value.dnscrypt_public_key = trim_non_empty(value.dnscrypt_public_key);
        value.resolver_id = trim_non_empty(value.resolver_id);
        Some(value)
    });
    runtime_context.protect_path = trim_non_empty(runtime_context.protect_path);
    runtime_context.direct_path_capabilities = runtime_context
        .direct_path_capabilities
        .into_iter()
        .filter_map(|mut capability| {
            capability.authority = capability.authority.trim().trim_end_matches('.').to_ascii_lowercase();
            if capability.authority.is_empty() {
                return None;
            }
            capability.repeated_handshake_failure_class = trim_non_empty(capability.repeated_handshake_failure_class);
            capability.updated_at = capability.updated_at.max(0);
            Some(capability)
        })
        .collect();
    runtime_context.morph_policy = runtime_context.morph_policy.and_then(|mut policy| {
        policy.id = policy.id.trim().to_string();
        if policy.id.is_empty() {
            return None;
        }
        policy.first_flight_size_min = policy.first_flight_size_min.max(0);
        policy.first_flight_size_max = policy.first_flight_size_max.max(policy.first_flight_size_min);
        policy.padding_envelope_min = policy.padding_envelope_min.max(0);
        policy.padding_envelope_max = policy.padding_envelope_max.max(policy.padding_envelope_min);
        policy.entropy_target_permil = policy.entropy_target_permil.max(0);
        policy.tcp_burst_cadence_ms = policy.tcp_burst_cadence_ms.into_iter().map(|value| value.max(0)).collect();
        policy.tls_burst_cadence_ms = policy.tls_burst_cadence_ms.into_iter().map(|value| value.max(0)).collect();
        policy.quic_burst_profile = policy.quic_burst_profile.trim().to_ascii_lowercase();
        policy.fake_packet_shape_profile = policy.fake_packet_shape_profile.trim().to_ascii_lowercase();
        Some(policy)
    });
    if runtime_context.encrypted_dns.is_none()
        && runtime_context.protect_path.is_none()
        && runtime_context.preferred_edges.is_empty()
        && runtime_context.direct_path_capabilities.is_empty()
        && runtime_context.morph_policy.is_none()
    {
        return None;
    }
    Some(runtime_context)
}

fn sanitize_log_context(log_context: Option<ProxyLogContext>) -> Option<ProxyLogContext> {
    let mut log_context = log_context?;
    log_context.runtime_id = trim_non_empty(log_context.runtime_id);
    log_context.mode = trim_non_empty(log_context.mode).map(|value| value.to_ascii_lowercase());
    log_context.policy_signature = trim_non_empty(log_context.policy_signature);
    log_context.fingerprint_hash = trim_non_empty(log_context.fingerprint_hash);
    log_context.diagnostics_session_id = trim_non_empty(log_context.diagnostics_session_id);
    if log_context.runtime_id.is_none()
        && log_context.mode.is_none()
        && log_context.policy_signature.is_none()
        && log_context.fingerprint_hash.is_none()
        && log_context.diagnostics_session_id.is_none()
    {
        None
    } else {
        Some(log_context)
    }
}

fn group_needs_delayed_connect(group: &DesyncGroup) -> bool {
    !group.matches.filters.hosts.is_empty() || (group.matches.proto & (IS_HTTP | IS_HTTPS)) != 0
}

fn synthesize_tlsrec_prelude_for_bare_hostfake(chain: &mut Vec<TcpChainStep>) {
    let has_hostfake = chain.iter().any(|step| step.kind == TcpChainStepKind::HostFake);
    let has_tls_prelude = chain.iter().any(|step| step.kind.is_tls_prelude());
    if !has_hostfake || has_tls_prelude {
        return;
    }

    chain.insert(
        0,
        TcpChainStep {
            kind: TcpChainStepKind::TlsRec,
            offset: OffsetExpr::tls_marker(OffsetBase::ExtLen, 0),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            tcp_flags_set: None,
            tcp_flags_unset: None,
            tcp_flags_orig_set: None,
            tcp_flags_orig_unset: None,
            overlap_size: 0,
            seqovl_fake_mode: SeqOverlapFakeMode::Profile,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_routing: false,
            ipv6_frag_next_override: None,
        },
    );
}

fn parse_proxy_numeric_range(
    range: &ProxyUiNumericRange,
    field_name: &str,
    minimum: i64,
) -> Result<Option<NumericRange<i64>>, ProxyConfigError> {
    let start = range.start;
    let end = range.end;
    if start.is_none() && end.is_none() {
        return Ok(None);
    }
    let start = start.or(end).unwrap_or(minimum);
    let end = end.or(Some(start)).unwrap_or(start);
    if start < minimum || end < minimum || start > end {
        return Err(ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")));
    }
    Ok(Some(NumericRange::new(start, end)))
}

fn parse_proxy_activation_filter(
    filter: Option<&ProxyUiActivationFilter>,
    field_name: &str,
    allow_tcp_state_predicates: bool,
) -> Result<Option<ActivationFilter>, ProxyConfigError> {
    let Some(filter) = filter else {
        return Ok(None);
    };
    let round = filter
        .round
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.round"), 1))
        .transpose()?
        .flatten();
    let payload_size = filter
        .payload_size
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.payloadSize"), 0))
        .transpose()?
        .flatten();
    let stream_bytes = filter
        .stream_bytes
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.streamBytes"), 0))
        .transpose()?
        .flatten();
    if !allow_tcp_state_predicates
        && (filter.tcp_has_timestamp.is_some()
            || filter.tcp_has_ech.is_some()
            || filter.tcp_window_below.is_some()
            || filter.tcp_mss_below.is_some())
    {
        return Err(ProxyConfigError::InvalidConfig(format!("{field_name} must not declare TCP-state predicates")));
    }
    let filter = ActivationFilter {
        round,
        payload_size,
        stream_bytes,
        tcp_has_timestamp: filter.tcp_has_timestamp,
        tcp_has_ech: filter.tcp_has_ech,
        tcp_window_below: filter.tcp_window_below,
        tcp_mss_below: filter.tcp_mss_below,
    };
    Ok((!filter.is_unbounded()).then_some(filter))
}

pub fn normalize_fake_tls_sni_mode(value: &str) -> &'static str {
    match value.trim().to_ascii_lowercase().as_str() {
        FAKE_TLS_SNI_MODE_RANDOMIZED => FAKE_TLS_SNI_MODE_RANDOMIZED,
        _ => FAKE_TLS_SNI_MODE_FIXED,
    }
}

pub fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, ProxyConfigError> {
    validate_ui_payload_shape(json)?;
    serde_json::from_str::<ProxyConfigPayload>(json)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))
}

pub fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, ProxyConfigError> {
    runtime_config_envelope_from_payload(payload).map(|envelope| envelope.config)
}

pub fn runtime_config_envelope_from_payload(
    payload: ProxyConfigPayload,
) -> Result<RuntimeConfigEnvelope, ProxyConfigError> {
    match payload {
        ProxyConfigPayload::CommandLine { args, host_autolearn_store_path, runtime_context, log_context } => {
            let mut config = runtime_config_from_command_line(args)?;
            config.host_autolearn.store_path = host_autolearn_store_path
                .as_deref()
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(ToOwned::to_owned);
            Ok(RuntimeConfigEnvelope {
                config,
                runtime_context: sanitize_runtime_context(runtime_context),
                log_context: sanitize_log_context(log_context),
                native_log_level: None,
            })
        }
        ProxyConfigPayload::Ui { strategy_preset, mut config, runtime_context, log_context } => {
            let preset_id_opt = strategy_preset.as_deref().map(str::to_owned);
            if let Some(ref preset_id) = preset_id_opt {
                presets::apply_preset(preset_id, &mut config)?;
            }
            let native_log_level = config.native_log_level.clone();
            let mut runtime_config = runtime_config_from_ui(config)?;
            let runtime_preset_id = preset_id_opt.as_deref().unwrap_or("ripdpi_default");
            presets::apply_runtime_preset(runtime_preset_id, &mut runtime_config)?;
            Ok(RuntimeConfigEnvelope {
                config: runtime_config,
                runtime_context: sanitize_runtime_context(runtime_context),
                log_context: sanitize_log_context(log_context),
                native_log_level,
            })
        }
    }
}

fn validate_ui_payload_shape(json: &str) -> Result<(), ProxyConfigError> {
    const GROUPED_UI_KEYS: &[&str] = &[
        "listen",
        "protocols",
        "chains",
        "fakePackets",
        "parserEvasions",
        "adaptiveFallback",
        "quic",
        "hosts",
        "upstreamRelay",
        "warp",
        "hostAutolearn",
        "wsTunnel",
    ];
    const LEGACY_FLAT_UI_KEYS: &[&str] = &[
        "ip",
        "port",
        "maxConnections",
        "bufferSize",
        "tcpFastOpen",
        "defaultTtl",
        "customTtl",
        "noDomain",
        "desyncHttp",
        "desyncHttps",
        "desyncUdp",
        "desyncMethod",
        "splitMarker",
        "tcpChainSteps",
        "groupActivationFilter",
        "splitPosition",
        "splitAtHost",
        "fakeTtl",
        "adaptiveFakeTtlEnabled",
        "adaptiveFakeTtlDelta",
        "adaptiveFakeTtlMin",
        "adaptiveFakeTtlMax",
        "adaptiveFakeTtlFallback",
        "fakeSni",
        "httpFakeProfile",
        "fakeTlsUseOriginal",
        "fakeTlsRandomize",
        "fakeTlsDupSessionId",
        "fakeTlsPadEncap",
        "fakeTlsSize",
        "fakeTlsSniMode",
        "tlsFakeProfile",
        "oobChar",
        "hostMixedCase",
        "domainMixedCase",
        "hostRemoveSpaces",
        "httpMethodEol",
        "httpMethodSpace",
        "httpUnixEol",
        "httpHostPad",
        "tlsRecordSplit",
        "tlsRecordSplitMarker",
        "tlsRecordSplitPosition",
        "tlsRecordSplitAtSni",
        "hostsMode",
        "udpFakeCount",
        "udpChainSteps",
        "udpFakeProfile",
        "dropSack",
        "fakeOffsetMarker",
        "fakeOffset",
        "quicInitialMode",
        "quicSupportV1",
        "quicSupportV2",
        "quicFakeProfile",
        "quicFakeHost",
        "hostAutolearnEnabled",
        "hostAutolearnPenaltyTtlSecs",
        "hostAutolearnPenaltyTtlHours",
        "hostAutolearnMaxHosts",
        "hostAutolearnStorePath",
        "networkScopeKey",
        "adaptiveFallbackEnabled",
        "adaptiveFallbackTorst",
        "adaptiveFallbackTlsErr",
        "adaptiveFallbackHttpRedirect",
        "adaptiveFallbackConnectFailure",
        "adaptiveFallbackAutoSort",
        "adaptiveFallbackCacheTtlSeconds",
        "adaptiveFallbackCachePrefixV4",
    ];

    let shape = serde_json::Deserializer::from_str(json)
        .deserialize_any(UiPayloadShapeVisitor)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))?;

    if !shape.is_ui {
        return Ok(());
    }

    if let Some(legacy_key) = shape.legacy_key {
        return Err(ProxyConfigError::InvalidConfig(format!(
            "Legacy flat UI config JSON is not supported: {legacy_key}"
        )));
    }
    if !shape.has_grouped_key {
        return Err(ProxyConfigError::InvalidConfig(
            "Grouped UI config JSON must include at least one nested section".to_string(),
        ));
    }

    let _ = GROUPED_UI_KEYS;
    let _ = LEGACY_FLAT_UI_KEYS;
    Ok(())
}

#[derive(Default)]
struct UiPayloadShape {
    is_ui: bool,
    has_grouped_key: bool,
    legacy_key: Option<String>,
}

struct UiPayloadShapeVisitor;

impl<'de> Visitor<'de> for UiPayloadShapeVisitor {
    type Value = UiPayloadShape;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("a proxy config JSON object")
    }

    fn visit_map<A>(self, mut map: A) -> Result<Self::Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        const GROUPED_UI_KEYS: &[&str] = &[
            "listen",
            "protocols",
            "chains",
            "fakePackets",
            "parserEvasions",
            "quic",
            "hosts",
            "warp",
            "hostAutolearn",
            "wsTunnel",
        ];
        const LEGACY_FLAT_UI_KEYS: &[&str] = &[
            "ip",
            "port",
            "maxConnections",
            "bufferSize",
            "tcpFastOpen",
            "defaultTtl",
            "customTtl",
            "noDomain",
            "desyncHttp",
            "desyncHttps",
            "desyncUdp",
            "desyncMethod",
            "splitMarker",
            "tcpChainSteps",
            "groupActivationFilter",
            "splitPosition",
            "splitAtHost",
            "fakeTtl",
            "adaptiveFakeTtlEnabled",
            "adaptiveFakeTtlDelta",
            "adaptiveFakeTtlMin",
            "adaptiveFakeTtlMax",
            "adaptiveFakeTtlFallback",
            "fakeSni",
            "httpFakeProfile",
            "fakeTlsUseOriginal",
            "fakeTlsRandomize",
            "fakeTlsDupSessionId",
            "fakeTlsPadEncap",
            "fakeTlsSize",
            "fakeTlsSniMode",
            "tlsFakeProfile",
            "oobChar",
            "hostMixedCase",
            "domainMixedCase",
            "hostRemoveSpaces",
            "httpMethodEol",
            "httpUnixEol",
            "tlsRecordSplit",
            "tlsRecordSplitMarker",
            "tlsRecordSplitPosition",
            "tlsRecordSplitAtSni",
            "hostsMode",
            "udpFakeCount",
            "udpChainSteps",
            "udpFakeProfile",
            "dropSack",
            "fakeOffsetMarker",
            "fakeOffset",
            "quicInitialMode",
            "quicSupportV1",
            "quicSupportV2",
            "quicFakeProfile",
            "quicFakeHost",
            "hostAutolearnEnabled",
            "hostAutolearnPenaltyTtlSecs",
            "hostAutolearnPenaltyTtlHours",
            "hostAutolearnMaxHosts",
            "hostAutolearnStorePath",
            "networkScopeKey",
        ];

        let mut shape = UiPayloadShape::default();
        while let Some(mut key) = map.next_key::<String>()? {
            if key == "kind" {
                let kind = map.next_value::<serde_json::Value>()?;
                if kind.as_str() == Some("ui") {
                    shape.is_ui = true;
                }
                continue;
            }

            if GROUPED_UI_KEYS.contains(&key.as_str()) {
                shape.has_grouped_key = true;
            }
            if shape.legacy_key.is_none() && LEGACY_FLAT_UI_KEYS.contains(&key.as_str()) {
                shape.legacy_key = Some(mem::take(&mut key));
            }

            map.next_value::<IgnoredAny>()?;
        }

        Ok(shape)
    }
}

pub fn runtime_config_from_command_line(mut args: Vec<String>) -> Result<RuntimeConfig, ProxyConfigError> {
    if args.first().is_some_and(|value| !value.starts_with('-')) {
        args.remove(0);
    }

    let parsed = ripdpi_config::parse_cli(&args, &StartupEnv::default())
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid command-line proxy config: {}", err.option)))?;

    match parsed {
        ripdpi_config::ParseResult::Run(config) => Ok(*config),
        _ => Err(ProxyConfigError::InvalidConfig(
            "Command-line proxy config must resolve to a runnable config".to_string(),
        )),
    }
}

pub fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, ProxyConfigError> {
    let ProxyUiConfig {
        listen,
        protocols,
        chains,
        fake_packets,
        parser_evasions,
        adaptive_fallback,
        quic,
        hosts,
        upstream_relay,
        warp,
        host_autolearn,
        ws_tunnel,
        native_log_level: _,
        root_mode,
        root_helper_socket_path,
    } = payload;

    let listen_ip =
        IpAddr::from_str(&listen.ip).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy IP".to_string()))?;
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_ip = listen_ip;
    config.network.listen.listen_port =
        u16::try_from(listen.port).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()))?;
    config.network.listen.auth_token = listen.auth_token.filter(|t| !t.is_empty());
    if config.network.listen.listen_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()));
    }
    if listen.max_connections <= 0 || listen.max_connections > 4096 {
        return Err(ProxyConfigError::InvalidConfig("maxConnections must be in 1..=4096".to_string()));
    }
    config.network.max_open = listen.max_connections;
    config.network.buffer_size = usize::try_from(listen.buffer_size)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid bufferSize".to_string()))?;
    if config.network.buffer_size == 0 || config.network.buffer_size > 1_048_576 {
        return Err(ProxyConfigError::InvalidConfig("bufferSize must be in 1..=1048576".to_string()));
    }
    config.network.resolve = protocols.resolve_domains;
    config.network.tfo = listen.tcp_fast_open;
    config.quic.initial_mode = parse_quic_initial_mode(&quic.initial_mode)?;
    config.quic.support_v1 = quic.support_v1;
    config.quic.support_v2 = quic.support_v2;
    config.host_autolearn.enabled = host_autolearn.enabled;
    config.host_autolearn.penalty_ttl_secs = host_autolearn.penalty_ttl_hours.max(1).saturating_mul(3600);
    config.host_autolearn.max_hosts = host_autolearn.max_hosts.max(1);
    config.host_autolearn.store_path =
        host_autolearn.store_path.as_deref().map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned);
    config.host_autolearn.warmup_probe_enabled = host_autolearn.warmup_probe_enabled;
    config.host_autolearn.network_reprobe_enabled = host_autolearn.network_reprobe_enabled;
    config.adaptive.network_scope_key = host_autolearn
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    config.adaptive.ws_tunnel_mode = match ws_tunnel.mode.as_deref() {
        Some("fallback") => ripdpi_config::WsTunnelMode::Fallback,
        Some("always") => ripdpi_config::WsTunnelMode::Always,
        Some("off" | _) => ripdpi_config::WsTunnelMode::Off,
        None => {
            if ws_tunnel.enabled {
                ripdpi_config::WsTunnelMode::Always
            } else {
                ripdpi_config::WsTunnelMode::Off
            }
        }
    };
    config.adaptive.ws_tunnel_fake_sni = ws_tunnel.fake_sni.filter(|s| !s.is_empty());
    config.adaptive.auto_level = if adaptive_fallback.enabled { AUTO_RECONN } else { 0 };
    if adaptive_fallback.enabled && adaptive_fallback.auto_sort {
        config.adaptive.auto_level |= AUTO_SORT;
    }
    config.adaptive.cache_ttl = adaptive_fallback.cache_ttl_seconds.max(0);
    config.adaptive.cache_prefix = (32 - adaptive_fallback.cache_prefix_v4.clamp(1, 32)).max(1);
    if listen.custom_ttl {
        let ttl = u8::try_from(listen.default_ttl)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid defaultTtl".to_string()))?;
        if ttl == 0 {
            return Err(ProxyConfigError::InvalidConfig(
                "defaultTtl must be positive when customTtl is enabled".to_string(),
            ));
        }
        config.network.default_ttl = ttl;
        config.network.custom_ttl = true;
    }

    let mut groups = Vec::new();
    if warp.enabled && warp.built_in_rules_enabled {
        let mut control_plane = DesyncGroup::new(groups.len());
        control_plane.matches.filters.hosts = parse_hosts(Some(&WARP_CONTROL_PLANE_HOSTS.join("\n")))?;
        control_plane.policy.label = "warp_control_plane".to_string();
        groups.push(control_plane);
    }
    if hosts.mode == HOSTS_WHITELIST {
        let mut whitelist = DesyncGroup::new(groups.len());
        whitelist.matches.filters.hosts = parse_hosts(hosts.entries.as_deref())?;
        groups.push(whitelist);
    }

    if warp.enabled && warp.route_mode == WARP_ROUTE_MODE_RULES {
        let local_socks_ip = warp.local_socks_host.trim().parse::<IpAddr>().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
        let local_socks_port = u16::try_from(warp.local_socks_port)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid warp.localSocksPort".to_string()))?;
        if local_socks_port == 0 {
            return Err(ProxyConfigError::InvalidConfig("Invalid warp.localSocksPort".to_string()));
        }
        let route_hosts = parse_hosts(Some(warp.route_hosts.as_str()))?;
        if !route_hosts.is_empty() {
            let mut warp_group = DesyncGroup::new(groups.len());
            warp_group.matches.filters.hosts = route_hosts;
            warp_group.policy.ext_socks =
                Some(UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port) });
            warp_group.policy.label = "warp_routed".to_string();
            groups.push(warp_group);
        }
    }

    let relay_upstream = if upstream_relay.enabled && upstream_relay.kind != RELAY_KIND_OFF {
        let local_socks_ip =
            upstream_relay.local_socks_host.trim().parse::<IpAddr>().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
        let local_socks_port = u16::try_from(upstream_relay.local_socks_port)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid upstreamRelay.localSocksPort".to_string()))?;
        if local_socks_port == 0 {
            return Err(ProxyConfigError::InvalidConfig("Invalid upstreamRelay.localSocksPort".to_string()));
        }
        Some(UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port) })
    } else {
        None
    };

    let primary_group_index = groups.len();
    let mut group = DesyncGroup::new(groups.len());
    match hosts.mode.as_str() {
        HOSTS_DISABLE | HOSTS_WHITELIST => {}
        HOSTS_BLACKLIST => {
            group.matches.filters.hosts = parse_hosts(hosts.entries.as_deref())?;
        }
        _ => return Err(ProxyConfigError::InvalidConfig("Unknown hostsMode".to_string())),
    }

    if fake_packets.adaptive_fake_ttl_enabled {
        let delta = i8::try_from(fake_packets.adaptive_fake_ttl_delta)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlDelta".to_string()))?;
        let min_ttl = u8::try_from(fake_packets.adaptive_fake_ttl_min)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlMin".to_string()))?;
        let max_ttl = u8::try_from(fake_packets.adaptive_fake_ttl_max)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlMax".to_string()))?;
        if min_ttl == 0 || max_ttl == 0 || min_ttl > max_ttl {
            return Err(ProxyConfigError::InvalidConfig("Invalid adaptive fake TTL window".to_string()));
        }
        group.actions.auto_ttl = Some(ripdpi_config::AutoTtlConfig { delta, min_ttl, max_ttl });
        let fallback_ttl = if fake_packets.adaptive_fake_ttl_fallback > 0 {
            fake_packets.adaptive_fake_ttl_fallback
        } else if fake_packets.fake_ttl > 0 {
            fake_packets.fake_ttl
        } else {
            ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
        };
        group.actions.ttl = Some(
            u8::try_from(fallback_ttl)
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlFallback".to_string()))?,
        );
    } else if fake_packets.fake_ttl > 0 {
        group.actions.ttl = Some(
            u8::try_from(fake_packets.fake_ttl)
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid fakeTtl".to_string()))?,
        );
    }

    if let Some(upstream) = relay_upstream {
        for existing_group in &mut groups {
            if existing_group.is_actionable() && existing_group.policy.ext_socks.is_none() {
                existing_group.policy.ext_socks = Some(upstream);
                if existing_group.policy.label.is_empty() {
                    existing_group.policy.label = "relay_upstream".to_string();
                }
            }
        }
        group.policy.ext_socks = Some(upstream);
    }
    group.actions.http_fake_profile = parse_http_fake_profile(&fake_packets.http_fake_profile)?;
    group.actions.tls_fake_profile = parse_tls_fake_profile(&fake_packets.tls_fake_profile)?;
    group.actions.udp_fake_profile = parse_udp_fake_profile(&fake_packets.udp_fake_profile)?;
    group.actions.fake_tls_source = match fake_packets.fake_tls_source.trim().to_ascii_lowercase().as_str() {
        FAKE_TLS_SOURCE_PROFILE => FakePacketSource::Profile,
        FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO => FakePacketSource::CapturedClientHello,
        _ => {
            return Err(ProxyConfigError::InvalidConfig("Invalid fakePackets.fakeTlsSource".to_string()));
        }
    };
    group.actions.fake_tls_secondary_profile = if fake_packets.fake_tls_secondary_profile.trim().is_empty() {
        None
    } else {
        Some(parse_tls_fake_profile(&fake_packets.fake_tls_secondary_profile)?)
    };
    group.actions.fake_tcp_timestamp_enabled = fake_packets.fake_tcp_timestamp_enabled;
    group.actions.fake_tcp_timestamp_delta_ticks = fake_packets.fake_tcp_timestamp_delta_ticks;
    group.actions.drop_sack = fake_packets.drop_sack;
    group.actions.window_clamp = fake_packets.window_clamp;
    group.actions.wsize = fake_packets.wsize_window.filter(|&w| w > 0).map(|window| WsizeConfig {
        window,
        scale: fake_packets.wsize_scale.and_then(|s| if s >= 0 { Some(s as u8) } else { None }),
    });
    group.actions.strip_timestamps = fake_packets.strip_timestamps;
    group.actions.ip_id_mode = parse_ip_id_mode(&fake_packets.ip_id_mode)?;
    group.actions.quic_bind_low_port = fake_packets.quic_bind_low_port;
    group.actions.quic_migrate_after_handshake = fake_packets.quic_migrate_after_handshake;
    if let Some(v) = fake_packets.quic_fake_version {
        group.actions.quic_fake_version = v;
    }
    group.actions.entropy_mode = match fake_packets.entropy_mode.as_str() {
        "popcount" => EntropyMode::Popcount,
        "shannon" => EntropyMode::Shannon,
        "combined" => EntropyMode::Combined,
        _ => EntropyMode::Disabled,
    };
    if let Some(v) = fake_packets.entropy_padding_target_permil {
        if v > 0 {
            group.actions.entropy_padding_target_permil = Some(v);
        }
    }
    if let Some(v) = fake_packets.entropy_padding_max {
        if v > 0 {
            group.actions.entropy_padding_max = v;
        }
    }
    if let Some(v) = fake_packets.shannon_entropy_target_permil {
        if v > 0 {
            group.actions.shannon_entropy_target_permil = Some(v);
        }
    }
    let tcp_proto = (u32::from(protocols.desync_http) * IS_HTTP) | (u32::from(protocols.desync_https) * IS_HTTPS);
    let udp_enabled = protocols.desync_udp;
    group.matches.proto = tcp_proto;
    group.matches.any_protocol = chains.any_protocol;
    if let Some(filter) =
        parse_proxy_activation_filter(chains.group_activation_filter.as_ref(), "chains.groupActivationFilter", false)?
    {
        group.set_activation_filter(filter);
    }
    group.actions.quic_fake_profile = parse_quic_fake_profile(&quic.fake_profile)?;
    group.actions.quic_fake_host = {
        let host = quic.fake_host.trim();
        if host.is_empty() {
            None
        } else {
            ripdpi_config::normalize_quic_fake_host(host).ok()
        }
    };
    group.actions.mod_http = (u32::from(parser_evasions.host_mixed_case) * MH_HMIX)
        | (u32::from(parser_evasions.domain_mixed_case) * MH_DMIX)
        | (u32::from(parser_evasions.host_remove_spaces) * MH_SPACE)
        | (u32::from(parser_evasions.http_method_eol) * MH_METHODEOL)
        | (u32::from(parser_evasions.http_method_space) * MH_METHODSPACE)
        | (u32::from(parser_evasions.http_unix_eol) * MH_UNIXEOL)
        | (u32::from(parser_evasions.http_host_pad) * MH_HOSTPAD)
        | (u32::from(parser_evasions.http_host_extra_space) * MH_HOSTEXTRASPACE)
        | (u32::from(parser_evasions.http_host_tab) * MH_HOSTTAB);

    group.actions.tcp_chain = parse_proxy_tcp_chain(&chains.tcp_steps, "chains.tcpSteps")?;
    synthesize_tlsrec_prelude_for_bare_hostfake(&mut group.actions.tcp_chain);
    validate_tcp_chain(&group.actions.tcp_chain)?;
    if let Some(rotation) = chains.tcp_rotation.as_ref() {
        if rotation.candidates.is_empty() {
            return Err(ProxyConfigError::InvalidConfig(
                "chains.tcpRotation must declare at least one candidate".to_string(),
            ));
        }
        if rotation.fails == 0 {
            return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation fails must be positive".to_string()));
        }
        if rotation.retrans == 0 {
            return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation retrans must be positive".to_string()));
        }
        if rotation.time_secs == 0 {
            return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation timeSecs must be positive".to_string()));
        }
        let candidates = rotation
            .candidates
            .iter()
            .enumerate()
            .map(|(index, candidate)| {
                let field = format!("chains.tcpRotation.candidates[{index}].tcpSteps");
                let mut tcp_chain = parse_proxy_tcp_chain(&candidate.tcp_steps, &field)?;
                synthesize_tlsrec_prelude_for_bare_hostfake(&mut tcp_chain);
                validate_tcp_chain(&tcp_chain)?;
                Ok(RotationCandidate { tcp_chain })
            })
            .collect::<Result<Vec<_>, ProxyConfigError>>()?;
        group.actions.rotation_policy = Some(RotationPolicy {
            fails: rotation.fails,
            retrans: rotation.retrans,
            seq: rotation.seq,
            rst: rotation.rst,
            time_secs: rotation.time_secs,
            candidates,
        });
    }

    for step in &chains.udp_steps {
        if step.count < 0 {
            return Err(ProxyConfigError::InvalidConfig("udpChainSteps count must be non-negative".to_string()));
        }
        let kind = parse_udp_chain_step_kind(&step.kind)?;
        if kind == UdpChainStepKind::IpFrag2Udp {
            if step.count != 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "udpChainSteps kind=ipfrag2_udp must not declare count".to_string(),
                ));
            }
            if step.split_bytes <= 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "udpChainSteps kind=ipfrag2_udp must declare positive splitBytes".to_string(),
                ));
            }
        } else if step.split_bytes != 0 {
            return Err(ProxyConfigError::InvalidConfig(
                "udpChainSteps splitBytes is only supported for kind=ipfrag2_udp".to_string(),
            ));
        }
        let ipv6_ext = parse_ipv6_extension_profile(&step.ipv6_extension_profile)?;
        group.actions.udp_chain.push(UdpChainStep {
            kind,
            count: step.count,
            split_bytes: step.split_bytes,
            activation_filter: parse_proxy_activation_filter(
                step.activation_filter.as_ref(),
                "chains.udpSteps.activationFilter",
                false,
            )?,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: ipv6_ext.hop_by_hop,
            ipv6_dest_opt: ipv6_ext.dest_opt,
            ipv6_dest_opt2: ipv6_ext.dest_opt2,
            ipv6_frag_next_override: None,
        });
    }
    validate_udp_chain(&group.actions.udp_chain)?;

    let has_fake_step = group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
    });
    let has_oob_step = group
        .effective_tcp_chain()
        .iter()
        .any(|step| matches!(step.kind, TcpChainStepKind::Oob | TcpChainStepKind::Disoob));

    if has_fake_step {
        let fake_tls_sni_mode = normalize_fake_tls_sni_mode(&fake_packets.fake_tls_sni_mode);
        let fake_offset = parse_offset_expr_field(
            Some(fake_packets.fake_offset_marker.as_str()),
            || "0".to_string(),
            "fakePackets.fakeOffsetMarker",
        )?;
        if !fake_offset.supports_fake_offset() {
            return Err(ProxyConfigError::InvalidConfig("Invalid fakePackets.fakeOffsetMarker".to_string()));
        }
        group.actions.fake_offset = Some(fake_offset);
        if fake_packets.fake_tls_use_original {
            group.actions.fake_mod |= FM_ORIG;
        }
        if fake_packets.fake_tls_randomize {
            group.actions.fake_mod |= FM_RAND;
        }
        if fake_packets.fake_tls_dup_session_id {
            group.actions.fake_mod |= FM_DUPSID;
        }
        if fake_packets.fake_tls_pad_encap {
            group.actions.fake_mod |= FM_PADENCAP;
        }
        if fake_tls_sni_mode == FAKE_TLS_SNI_MODE_RANDOMIZED {
            group.actions.fake_mod |= FM_RNDSNI;
        } else {
            group.actions.fake_sni_list.push(fake_packets.fake_sni);
        }
        if fake_packets.fake_tls_size < -65535 || fake_packets.fake_tls_size > 65535 {
            return Err(ProxyConfigError::InvalidConfig("fakeTlsSize must be in -65535..=65535".to_string()));
        }
        group.actions.fake_tls_size = fake_packets.fake_tls_size;
    }

    if has_oob_step {
        group.actions.oob_data = Some(fake_packets.oob_char);
    }

    let has_tcp_proto = group.matches.proto != 0;
    groups.push(group);

    if udp_enabled {
        let mut udp_group = DesyncGroup::new(groups.len());
        udp_group.matches.proto = IS_UDP;
        udp_group.actions.udp_chain = groups[primary_group_index].actions.udp_chain.clone();
        udp_group.actions.quic_fake_profile = groups[primary_group_index].actions.quic_fake_profile;
        udp_group.actions.quic_fake_host = groups[primary_group_index].actions.quic_fake_host.clone();
        udp_group.matches.activation_filter = groups[primary_group_index].matches.activation_filter;
        groups.push(udp_group);
    }

    if has_tcp_proto || udp_enabled {
        let adaptive_detect = adaptive_detect_mask(&adaptive_fallback);
        if adaptive_fallback.enabled && adaptive_detect != 0 && has_tcp_proto {
            let mut adaptive_direct = DesyncGroup::new(groups.len());
            adaptive_direct.matches.detect = adaptive_detect;
            adaptive_direct.matches.proto = tcp_proto;
            adaptive_direct.policy.label = "adaptive_direct".to_string();
            adaptive_direct.policy.cache_ttl = config.adaptive.cache_ttl;
            groups.push(adaptive_direct);
        }
        let mut fallback = DesyncGroup::new(groups.len());
        fallback.matches.detect = DETECT_CONNECT;
        groups.push(fallback);
    }

    config.groups = groups;
    config.timeouts.connect_timeout_ms = 10_000;
    if listen.freeze_detection_enabled {
        config.timeouts.freeze_max_stalls = 3;
    }
    config.network.delay_conn = config.groups.iter().any(group_needs_delayed_connect);
    if !matches!(config.network.listen.bind_ip, IpAddr::V6(_)) {
        config.network.ipv6 = false;
    }
    if config.host_autolearn.enabled && config.host_autolearn.store_path.is_none() {
        return Err(ProxyConfigError::InvalidConfig(
            "hostAutolearn.storePath is required when hostAutolearn.enabled is true".to_string(),
        ));
    }

    config.process.root_mode = root_mode;
    config.process.root_helper_socket_path = root_helper_socket_path;

    Ok(config)
}

#[derive(Clone, Copy)]
struct ParsedIpv6ExtensionProfile {
    hop_by_hop: bool,
    dest_opt: bool,
    dest_opt2: bool,
}

fn parse_ipv6_extension_profile(value: &str) -> Result<ParsedIpv6ExtensionProfile, ProxyConfigError> {
    match value.trim() {
        "" | "none" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: false, dest_opt: false, dest_opt2: false }),
        "hopByHop" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: false, dest_opt2: false }),
        "hopByHop2" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: false, dest_opt2: true }),
        "destOpt" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: false, dest_opt: true, dest_opt2: false }),
        "hopByHopDestOpt" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: true, dest_opt2: false }),
        _ => Err(ProxyConfigError::InvalidConfig(
            "Unsupported ipv6ExtensionProfile; expected none, hopByHop, hopByHop2, destOpt, or hopByHopDestOpt"
                .to_string(),
        )),
    }
}

fn adaptive_detect_mask(config: &crate::types::ProxyUiAdaptiveFallbackConfig) -> u32 {
    let mut detect = 0;
    if config.torst {
        detect |= DETECT_TORST;
    }
    if config.tls_err {
        detect |= DETECT_TLS_ERR;
    }
    if config.http_redirect {
        detect |= DETECT_HTTP_LOCAT;
    }
    if config.connect_failure {
        detect |= DETECT_CONNECT;
    }
    detect
}

pub fn parse_tcp_chain_step_kind(value: &str) -> Result<TcpChainStepKind, ProxyConfigError> {
    match value {
        "split" => Ok(TcpChainStepKind::Split),
        "syndata" => Ok(TcpChainStepKind::SynData),
        "seqovl" => Ok(TcpChainStepKind::SeqOverlap),
        "disorder" => Ok(TcpChainStepKind::Disorder),
        "multidisorder" => Ok(TcpChainStepKind::MultiDisorder),
        "fake" => Ok(TcpChainStepKind::Fake),
        "fakedsplit" => Ok(TcpChainStepKind::FakeSplit),
        "fakeddisorder" => Ok(TcpChainStepKind::FakeDisorder),
        "hostfake" => Ok(TcpChainStepKind::HostFake),
        "oob" => Ok(TcpChainStepKind::Oob),
        "disoob" => Ok(TcpChainStepKind::Disoob),
        "tlsrec" => Ok(TcpChainStepKind::TlsRec),
        "tlsrandrec" => Ok(TcpChainStepKind::TlsRandRec),
        "ipfrag2" => Ok(TcpChainStepKind::IpFrag2),
        "fakerst" => Ok(TcpChainStepKind::FakeRst),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown tcpChainSteps kind: {value}"))),
    }
}

fn normalize_tlsrandrec_step_field(value: i32, default: i32) -> i32 {
    if value > 0 {
        value
    } else {
        default
    }
}

fn normalize_seqovl_overlap_size(value: i32) -> i32 {
    if value > 0 {
        value
    } else {
        SEQOVL_DEFAULT_OVERLAP_SIZE
    }
}

fn parse_seqovl_fake_mode(value: &str) -> Result<SeqOverlapFakeMode, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        SEQOVL_FAKE_MODE_PROFILE | "" => Ok(SeqOverlapFakeMode::Profile),
        SEQOVL_FAKE_MODE_RAND => Ok(SeqOverlapFakeMode::Rand),
        _ => Err(ProxyConfigError::InvalidConfig(
            "tcpChainSteps kind=seqovl fakeMode must be profile or rand".to_string(),
        )),
    }
}

fn parse_ip_id_mode(value: &str) -> Result<Option<IpIdMode>, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" => Ok(None),
        IP_ID_MODE_SEQ => Ok(Some(IpIdMode::Seq)),
        IP_ID_MODE_SEQGROUP => Ok(Some(IpIdMode::SeqGroup)),
        IP_ID_MODE_RND => Ok(Some(IpIdMode::Rnd)),
        IP_ID_MODE_ZERO => Ok(Some(IpIdMode::Zero)),
        _ => Err(ProxyConfigError::InvalidConfig(
            "fakePackets.ipIdMode must be seq, seqgroup, rnd, zero, or empty".to_string(),
        )),
    }
}

fn parse_proxy_tcp_chain(
    steps: &[crate::types::ProxyUiTcpChainStep],
    field_name: &str,
) -> Result<Vec<TcpChainStep>, ProxyConfigError> {
    let activation_field_name = format!("{field_name}.activationFilter");
    let mut parsed = Vec::with_capacity(steps.len());
    for step in steps {
        let kind = parse_tcp_chain_step_kind(&step.kind)?;
        let offset = parse_offset_expr_field(Some(step.marker.as_str()), || "0".to_string(), field_name)?;
        if kind == TcpChainStepKind::HostFake && offset.base.is_adaptive() {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "Adaptive markers are not supported for {field_name} kind=hostfake"
            )));
        }
        let midhost_offset = Some(str::trim(step.midhost_marker.as_str()))
            .filter(|value| !value.is_empty())
            .map(ripdpi_config::parse_offset_expr)
            .transpose()
            .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} midhostMarker")))?;
        if kind == TcpChainStepKind::HostFake && midhost_offset.is_some_and(|value| value.base.is_adaptive()) {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "Adaptive markers are not supported for {field_name} midhostMarker"
            )));
        }
        let fake_host_template = Some(str::trim(step.fake_host_template.as_str()))
            .filter(|value| !value.is_empty())
            .map(ripdpi_config::normalize_fake_host_template)
            .transpose()
            .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} fakeHostTemplate")))?;
        let tcp_flags_set = Some(str::trim(step.tcp_flags_set.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlags: {err}")))?;
        let tcp_flags_unset = Some(str::trim(step.tcp_flags_unset.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlagsUnset: {err}")))?;
        let tcp_flags_orig_set = Some(str::trim(step.tcp_flags_orig_set.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlagsOrig: {err}")))?;
        let tcp_flags_orig_unset = Some(str::trim(step.tcp_flags_orig_unset.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlagsOrigUnset: {err}")))?;
        validate_tcp_flag_masks(
            kind,
            tcp_flags_set,
            tcp_flags_unset,
            tcp_flags_orig_set,
            tcp_flags_orig_unset,
            field_name,
        )
        .map_err(|err| ProxyConfigError::InvalidConfig(err.to_string()))?;
        let (overlap_size, seqovl_fake_mode) = match kind {
            TcpChainStepKind::SeqOverlap => {
                let overlap_size = normalize_seqovl_overlap_size(step.overlap_size);
                if !(1..=32).contains(&overlap_size) {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "{field_name} kind=seqovl overlapSize must be in 1..=32"
                    )));
                }
                (overlap_size, parse_seqovl_fake_mode(&step.fake_mode)?)
            }
            _ => {
                if step.overlap_size != 0 {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "{field_name} kind={} must not declare overlapSize",
                        step.kind
                    )));
                }
                if !step.fake_mode.trim().is_empty() && !step.fake_mode.eq_ignore_ascii_case(SEQOVL_FAKE_MODE_PROFILE) {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "{field_name} kind={} must not declare fakeMode",
                        step.kind
                    )));
                }
                (0, SeqOverlapFakeMode::Profile)
            }
        };
        let (fragment_count, min_fragment_size, max_fragment_size) = match kind {
            TcpChainStepKind::TlsRandRec => (
                normalize_tlsrandrec_step_field(step.fragment_count, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT),
                normalize_tlsrandrec_step_field(step.min_fragment_size, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE),
                normalize_tlsrandrec_step_field(step.max_fragment_size, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE),
            ),
            _ => {
                if step.fragment_count != 0 || step.min_fragment_size != 0 || step.max_fragment_size != 0 {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "tlsrandrec fragment fields are only supported for {field_name} kind=tlsrandrec"
                    )));
                }
                (0, 0, 0)
            }
        };
        let activation_filter =
            parse_proxy_activation_filter(step.activation_filter.as_ref(), &activation_field_name, true)?;
        let ipv6_ext = parse_ipv6_extension_profile(&step.ipv6_extension_profile)?;
        parsed.push(TcpChainStep {
            kind,
            offset,
            activation_filter,
            midhost_offset,
            fake_host_template,
            tcp_flags_set,
            tcp_flags_unset,
            tcp_flags_orig_set,
            tcp_flags_orig_unset,
            overlap_size,
            seqovl_fake_mode,
            fragment_count,
            min_fragment_size,
            max_fragment_size,
            inter_segment_delay_ms: step.inter_segment_delay_ms.min(100),
            ip_frag_disorder: false,
            ipv6_hop_by_hop: ipv6_ext.hop_by_hop,
            ipv6_dest_opt: ipv6_ext.dest_opt,
            ipv6_dest_opt2: ipv6_ext.dest_opt2,
            ipv6_routing: false,
            ipv6_frag_next_override: None,
        });
    }
    Ok(parsed)
}

fn validate_tcp_chain(steps: &[TcpChainStep]) -> Result<(), ProxyConfigError> {
    let mut saw_send_step = false;
    let mut saw_ipfrag2 = false;
    let mut saw_seqovl = false;
    let mut send_step_count = 0usize;
    let mut multidisorder_count = 0usize;
    for (index, step) in steps.iter().enumerate() {
        if step.kind.is_tls_prelude() {
            if saw_send_step {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{} must be declared before tcp send steps",
                    match step.kind {
                        TcpChainStepKind::TlsRec => "tlsrec",
                        TcpChainStepKind::TlsRandRec => "tlsrandrec",
                        _ => unreachable!(),
                    }
                )));
            }
        } else {
            saw_send_step = true;
            if step.kind == TcpChainStepKind::SeqOverlap {
                if saw_seqovl {
                    return Err(ProxyConfigError::InvalidConfig(
                        "seqovl must appear at most once per tcp chain".to_string(),
                    ));
                }
                if send_step_count != 0 {
                    return Err(ProxyConfigError::InvalidConfig("seqovl must be the first tcp send step".to_string()));
                }
                if !(1..=32).contains(&step.overlap_size) {
                    return Err(ProxyConfigError::InvalidConfig("seqovl overlapSize must be in 1..=32".to_string()));
                }
                saw_seqovl = true;
            }
            if step.kind == TcpChainStepKind::MultiDisorder {
                multidisorder_count += 1;
            } else if multidisorder_count != 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "multidisorder must be the only tcp send step family".to_string(),
                ));
            }
            if step.kind == TcpChainStepKind::IpFrag2 {
                saw_ipfrag2 = true;
                if index + 1 != steps.len() {
                    return Err(ProxyConfigError::InvalidConfig("ipfrag2 must be the only tcp send step".to_string()));
                }
            } else if saw_ipfrag2 {
                return Err(ProxyConfigError::InvalidConfig("ipfrag2 must be the only tcp send step".to_string()));
            }
            send_step_count += 1;
        }

        if matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder) && index + 1 != steps.len()
        {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "{} must be the last tcp send step",
                match step.kind {
                    TcpChainStepKind::FakeSplit => "fakedsplit",
                    TcpChainStepKind::FakeDisorder => "fakeddisorder",
                    _ => unreachable!(),
                }
            )));
        }
    }
    if multidisorder_count > 0 {
        if send_step_count != multidisorder_count {
            return Err(ProxyConfigError::InvalidConfig(
                "multidisorder must be the only tcp send step family".to_string(),
            ));
        }
        if multidisorder_count < 2 {
            return Err(ProxyConfigError::InvalidConfig("multidisorder must declare at least two markers".to_string()));
        }
    }
    Ok(())
}

pub fn parse_udp_chain_step_kind(value: &str) -> Result<UdpChainStepKind, ProxyConfigError> {
    match value {
        "fake_burst" => Ok(UdpChainStepKind::FakeBurst),
        "dummyprepend" | "dummy_prepend" => Ok(UdpChainStepKind::DummyPrepend),
        "quicsnisplit" | "quic_sni_split" => Ok(UdpChainStepKind::QuicSniSplit),
        "quicfakeversion" | "quic_fake_version" => Ok(UdpChainStepKind::QuicFakeVersion),
        "quiccryptosplit" | "quic_crypto_split" => Ok(UdpChainStepKind::QuicCryptoSplit),
        "quicpaddingladder" | "quic_padding_ladder" => Ok(UdpChainStepKind::QuicPaddingLadder),
        "quiccidchurn" | "quic_cid_churn" => Ok(UdpChainStepKind::QuicCidChurn),
        "quicpacketnumbergap" | "quic_packet_number_gap" => Ok(UdpChainStepKind::QuicPacketNumberGap),
        "quicversionnegotiationdecoy" | "quic_version_negotiation_decoy" => {
            Ok(UdpChainStepKind::QuicVersionNegotiationDecoy)
        }
        "quicmultiinitialrealistic" | "quic_multi_initial_realistic" => Ok(UdpChainStepKind::QuicMultiInitialRealistic),
        "ipfrag2_udp" => Ok(UdpChainStepKind::IpFrag2Udp),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown udpChainSteps kind: {value}"))),
    }
}

fn validate_udp_chain(steps: &[UdpChainStep]) -> Result<(), ProxyConfigError> {
    if steps.iter().any(|step| step.kind == UdpChainStepKind::IpFrag2Udp) && steps.len() != 1 {
        return Err(ProxyConfigError::InvalidConfig("ipfrag2_udp must be the only udp chain step".to_string()));
    }
    Ok(())
}

pub fn parse_quic_initial_mode(value: &str) -> Result<QuicInitialMode, ProxyConfigError> {
    match value.trim().to_lowercase().as_str() {
        "disabled" => Ok(QuicInitialMode::Disabled),
        "route" => Ok(QuicInitialMode::Route),
        "route_and_cache" => Ok(QuicInitialMode::RouteAndCache),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown quicInitialMode: {value}"))),
    }
}

pub fn parse_quic_fake_profile(value: &str) -> Result<QuicFakeProfile, ProxyConfigError> {
    match value.trim().to_lowercase().as_str() {
        "disabled" | "" => Ok(QuicFakeProfile::Disabled),
        "compat_default" => Ok(QuicFakeProfile::CompatDefault),
        "realistic_initial" => Ok(QuicFakeProfile::RealisticInitial),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown quicFakeProfile: {value}"))),
    }
}

pub fn parse_http_fake_profile(value: &str) -> Result<HttpFakeProfile, ProxyConfigError> {
    parse_http_fake_profile_id(value)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Unknown httpFakeProfile: {value}")))
}

pub fn parse_tls_fake_profile(value: &str) -> Result<TlsFakeProfile, ProxyConfigError> {
    parse_tls_fake_profile_id(value)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Unknown tlsFakeProfile: {value}")))
}

pub fn parse_udp_fake_profile(value: &str) -> Result<UdpFakeProfile, ProxyConfigError> {
    parse_udp_fake_profile_id(value)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Unknown udpFakeProfile: {value}")))
}

pub fn parse_desync_mode(value: &str) -> Result<DesyncMode, ProxyConfigError> {
    match value {
        "none" => Ok(DesyncMode::None),
        "split" => Ok(DesyncMode::Split),
        "disorder" => Ok(DesyncMode::Disorder),
        "fake" => Ok(DesyncMode::Fake),
        "oob" => Ok(DesyncMode::Oob),
        "disoob" => Ok(DesyncMode::Disoob),
        _ => Err(ProxyConfigError::InvalidConfig("Unknown desyncMethod".to_string())),
    }
}

fn parse_hosts(hosts: Option<&str>) -> Result<Vec<String>, ProxyConfigError> {
    let hosts = hosts.unwrap_or_default();
    ripdpi_config::parse_hosts_spec(hosts)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid hosts list".to_string()))
}

fn parse_offset_expr_field<F>(marker: Option<&str>, legacy: F, field_name: &str) -> Result<OffsetExpr, ProxyConfigError>
where
    F: FnOnce() -> String,
{
    let spec = marker.map(str::trim).filter(|value| !value.is_empty()).map_or_else(legacy, ToOwned::to_owned);
    ripdpi_config::parse_offset_expr(&spec)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")))
}
