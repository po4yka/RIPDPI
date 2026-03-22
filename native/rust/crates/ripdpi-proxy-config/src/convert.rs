use std::net::IpAddr;
use std::str::FromStr;

use ciadpi_config::{
    parse_http_fake_profile as parse_http_fake_profile_id, parse_tls_fake_profile as parse_tls_fake_profile_id,
    parse_udp_fake_profile as parse_udp_fake_profile_id, ActivationFilter, DesyncGroup, DesyncMode, NumericRange,
    OffsetExpr, QuicFakeProfile, QuicInitialMode, RuntimeConfig, StartupEnv, TcpChainStep, TcpChainStepKind,
    UdpChainStep, UdpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
};
use ciadpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};
use ripdpi_packets::{IS_HTTP, IS_HTTPS, IS_UDP, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL};
use serde_json::Value;

use crate::presets;
use crate::types::{
    ProxyConfigError, ProxyConfigPayload, ProxyRuntimeContext, ProxyUiActivationFilter, ProxyUiConfig,
    ProxyUiNumericRange, RuntimeConfigEnvelope, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SNI_MODE_RANDOMIZED, HOSTS_BLACKLIST, HOSTS_DISABLE, HOSTS_WHITELIST, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT,
    TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE,
};

fn sanitize_runtime_context(runtime_context: Option<ProxyRuntimeContext>) -> Option<ProxyRuntimeContext> {
    let mut runtime_context = runtime_context?;
    runtime_context.encrypted_dns = runtime_context.encrypted_dns.and_then(|mut value| {
        value.protocol = value.protocol.trim().to_ascii_lowercase();
        value.host = value.host.trim().to_string();
        if value.host.is_empty() {
            return None;
        }
        value.port = if value.port == 0 { 443 } else { value.port };
        value.tls_server_name =
            value.tls_server_name.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty());
        value.bootstrap_ips = value
            .bootstrap_ips
            .into_iter()
            .map(|entry| entry.trim().to_string())
            .filter(|entry| !entry.is_empty())
            .collect();
        value.doh_url = value.doh_url.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty());
        value.dnscrypt_provider_name =
            value.dnscrypt_provider_name.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty());
        value.dnscrypt_public_key =
            value.dnscrypt_public_key.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty());
        value.resolver_id = value.resolver_id.map(|entry| entry.trim().to_string()).filter(|entry| !entry.is_empty());
        Some(value)
    });
    runtime_context.encrypted_dns.as_ref()?;
    Some(runtime_context)
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
    let filter = ActivationFilter { round, payload_size, stream_bytes };
    Ok((!filter.is_unbounded()).then_some(filter))
}

pub fn normalize_fake_tls_sni_mode(value: &str) -> &'static str {
    match value.trim().to_ascii_lowercase().as_str() {
        FAKE_TLS_SNI_MODE_RANDOMIZED => FAKE_TLS_SNI_MODE_RANDOMIZED,
        _ => FAKE_TLS_SNI_MODE_FIXED,
    }
}

pub fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, ProxyConfigError> {
    let value = serde_json::from_str::<Value>(json)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))?;
    validate_ui_payload_shape(&value)?;
    serde_json::from_value::<ProxyConfigPayload>(value)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))
}

pub fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, ProxyConfigError> {
    runtime_config_envelope_from_payload(payload).map(|envelope| envelope.config)
}

pub fn runtime_config_envelope_from_payload(
    payload: ProxyConfigPayload,
) -> Result<RuntimeConfigEnvelope, ProxyConfigError> {
    match payload {
        ProxyConfigPayload::CommandLine { args, runtime_context } => Ok(RuntimeConfigEnvelope {
            config: runtime_config_from_command_line(args)?,
            runtime_context: sanitize_runtime_context(runtime_context),
        }),
        ProxyConfigPayload::Ui { strategy_preset, mut config, runtime_context } => {
            if let Some(preset_id) = strategy_preset.as_deref().map(str::to_owned) {
                presets::apply_preset(&preset_id, &mut config)?;
            }
            Ok(RuntimeConfigEnvelope {
                config: runtime_config_from_ui(config)?,
                runtime_context: sanitize_runtime_context(runtime_context),
            })
        }
    }
}

fn validate_ui_payload_shape(value: &Value) -> Result<(), ProxyConfigError> {
    let Some(object) = value.as_object() else {
        return Ok(());
    };
    if object.get("kind").and_then(Value::as_str) != Some("ui") {
        return Ok(());
    }

    const GROUPED_UI_KEYS: &[&str] = &[
        "listen",
        "protocols",
        "chains",
        "fakePackets",
        "parserEvasions",
        "quic",
        "hosts",
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

    if let Some(legacy_key) = object.keys().find(|key| LEGACY_FLAT_UI_KEYS.contains(&key.as_str())) {
        return Err(ProxyConfigError::InvalidConfig(format!(
            "Legacy flat UI config JSON is not supported: {legacy_key}"
        )));
    }
    if object.keys().all(|key| !GROUPED_UI_KEYS.contains(&key.as_str())) {
        return Err(ProxyConfigError::InvalidConfig(
            "Grouped UI config JSON must include at least one nested section".to_string(),
        ));
    }
    Ok(())
}

pub fn runtime_config_from_command_line(mut args: Vec<String>) -> Result<RuntimeConfig, ProxyConfigError> {
    if args.first().is_some_and(|value| !value.starts_with('-')) {
        args.remove(0);
    }

    let parsed = ciadpi_config::parse_cli(&args, &StartupEnv::default())
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid command-line proxy config: {}", err.option)))?;

    match parsed {
        ciadpi_config::ParseResult::Run(config) => Ok(config),
        _ => Err(ProxyConfigError::InvalidConfig(
            "Command-line proxy config must resolve to a runnable config".to_string(),
        )),
    }
}

pub fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, ProxyConfigError> {
    let ProxyUiConfig {
        listen, protocols, chains, fake_packets, parser_evasions, quic, hosts, host_autolearn, ws_tunnel,
    } = payload;

    let listen_ip =
        IpAddr::from_str(&listen.ip).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy IP".to_string()))?;
    let mut config = RuntimeConfig::default();
    config.listen.listen_ip = listen_ip;
    config.listen.listen_port =
        u16::try_from(listen.port).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()))?;
    if config.listen.listen_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()));
    }
    if listen.max_connections <= 0 {
        return Err(ProxyConfigError::InvalidConfig("maxConnections must be positive".to_string()));
    }
    config.max_open = listen.max_connections;
    config.buffer_size = usize::try_from(listen.buffer_size)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid bufferSize".to_string()))?;
    if config.buffer_size == 0 {
        return Err(ProxyConfigError::InvalidConfig("bufferSize must be positive".to_string()));
    }
    config.resolve = protocols.resolve_domains;
    config.tfo = listen.tcp_fast_open;
    config.quic_initial_mode = parse_quic_initial_mode(&quic.initial_mode)?;
    config.quic_support_v1 = quic.support_v1;
    config.quic_support_v2 = quic.support_v2;
    config.host_autolearn_enabled = host_autolearn.enabled;
    config.host_autolearn_penalty_ttl_secs = host_autolearn.penalty_ttl_hours.max(1).saturating_mul(3600);
    config.host_autolearn_max_hosts = host_autolearn.max_hosts.max(1);
    config.host_autolearn_store_path =
        host_autolearn.store_path.as_deref().map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned);
    config.network_scope_key = host_autolearn
        .network_scope_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    config.ws_tunnel_mode = match ws_tunnel.mode.as_deref() {
        Some("fallback") => ciadpi_config::WsTunnelMode::Fallback,
        Some("always") => ciadpi_config::WsTunnelMode::Always,
        Some("off" | _) => ciadpi_config::WsTunnelMode::Off,
        None => {
            if ws_tunnel.enabled { ciadpi_config::WsTunnelMode::Always } else { ciadpi_config::WsTunnelMode::Off }
        }
    };
    if listen.custom_ttl {
        let ttl = u8::try_from(listen.default_ttl)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid defaultTtl".to_string()))?;
        if ttl == 0 {
            return Err(ProxyConfigError::InvalidConfig(
                "defaultTtl must be positive when customTtl is enabled".to_string(),
            ));
        }
        config.default_ttl = ttl;
        config.custom_ttl = true;
    }

    let mut groups = Vec::new();
    if hosts.mode == HOSTS_WHITELIST {
        let mut whitelist = DesyncGroup::new(0);
        whitelist.filters.hosts = parse_hosts(hosts.entries.as_deref())?;
        groups.push(whitelist);
    }

    let mut group = DesyncGroup::new(groups.len());
    match hosts.mode.as_str() {
        HOSTS_DISABLE | HOSTS_WHITELIST => {}
        HOSTS_BLACKLIST => {
            group.filters.hosts = parse_hosts(hosts.entries.as_deref())?;
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
        group.auto_ttl = Some(ciadpi_config::AutoTtlConfig { delta, min_ttl, max_ttl });
        let fallback_ttl = if fake_packets.adaptive_fake_ttl_fallback > 0 {
            fake_packets.adaptive_fake_ttl_fallback
        } else if fake_packets.fake_ttl > 0 {
            fake_packets.fake_ttl
        } else {
            ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
        };
        group.ttl = Some(
            u8::try_from(fallback_ttl)
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlFallback".to_string()))?,
        );
    } else if fake_packets.fake_ttl > 0 {
        group.ttl = Some(
            u8::try_from(fake_packets.fake_ttl)
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid fakeTtl".to_string()))?,
        );
    }
    group.http_fake_profile = parse_http_fake_profile(&fake_packets.http_fake_profile)?;
    group.tls_fake_profile = parse_tls_fake_profile(&fake_packets.tls_fake_profile)?;
    group.udp_fake_profile = parse_udp_fake_profile(&fake_packets.udp_fake_profile)?;
    group.drop_sack = fake_packets.drop_sack;
    group.proto = (u32::from(protocols.desync_http) * IS_HTTP)
        | (u32::from(protocols.desync_https) * IS_HTTPS)
        | (u32::from(protocols.desync_udp) * IS_UDP);
    if let Some(filter) =
        parse_proxy_activation_filter(chains.group_activation_filter.as_ref(), "chains.groupActivationFilter")?
    {
        group.set_activation_filter(filter);
    }
    group.quic_fake_profile = parse_quic_fake_profile(&quic.fake_profile)?;
    group.quic_fake_host = {
        let host = quic.fake_host.trim();
        if host.is_empty() {
            None
        } else {
            ciadpi_config::normalize_quic_fake_host(host).ok()
        }
    };
    group.mod_http = (u32::from(parser_evasions.host_mixed_case) * MH_HMIX)
        | (u32::from(parser_evasions.domain_mixed_case) * MH_DMIX)
        | (u32::from(parser_evasions.host_remove_spaces) * MH_SPACE)
        | (u32::from(parser_evasions.http_method_eol) * MH_METHODEOL)
        | (u32::from(parser_evasions.http_unix_eol) * MH_UNIXEOL);

    for step in &chains.tcp_steps {
        let kind = parse_tcp_chain_step_kind(&step.kind)?;
        let offset = parse_offset_expr_field(Some(step.marker.as_str()), || "0".to_string(), "chains.tcpSteps")?;
        if kind == TcpChainStepKind::HostFake && offset.base.is_adaptive() {
            return Err(ProxyConfigError::InvalidConfig(
                "Adaptive markers are not supported for tcpChainSteps kind=hostfake".to_string(),
            ));
        }
        let midhost_offset = Some(str::trim(step.midhost_marker.as_str()))
            .filter(|value| !value.is_empty())
            .map(ciadpi_config::parse_offset_expr)
            .transpose()
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid tcpChainSteps midhostMarker".to_string()))?;
        if kind == TcpChainStepKind::HostFake && midhost_offset.is_some_and(|value| value.base.is_adaptive()) {
            return Err(ProxyConfigError::InvalidConfig(
                "Adaptive markers are not supported for tcpChainSteps midhostMarker".to_string(),
            ));
        }
        let fake_host_template = Some(str::trim(step.fake_host_template.as_str()))
            .filter(|value| !value.is_empty())
            .map(ciadpi_config::normalize_fake_host_template)
            .transpose()
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid tcpChainSteps fakeHostTemplate".to_string()))?;
        let (fragment_count, min_fragment_size, max_fragment_size) = match kind {
            TcpChainStepKind::TlsRandRec => (
                normalize_tlsrandrec_step_field(step.fragment_count, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT),
                normalize_tlsrandrec_step_field(step.min_fragment_size, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE),
                normalize_tlsrandrec_step_field(step.max_fragment_size, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE),
            ),
            _ => {
                if step.fragment_count != 0 || step.min_fragment_size != 0 || step.max_fragment_size != 0 {
                    return Err(ProxyConfigError::InvalidConfig(
                        "tlsrandrec fragment fields are only supported for tcpChainSteps kind=tlsrandrec".to_string(),
                    ));
                }
                (0, 0, 0)
            }
        };
        let activation_filter =
            parse_proxy_activation_filter(step.activation_filter.as_ref(), "chains.tcpSteps.activationFilter")?;
        group.tcp_chain.push(TcpChainStep {
            kind,
            offset,
            activation_filter,
            midhost_offset,
            fake_host_template,
            fragment_count,
            min_fragment_size,
            max_fragment_size,
        });
    }
    validate_tcp_chain(&group.tcp_chain)?;

    for step in &chains.udp_steps {
        if step.count < 0 {
            return Err(ProxyConfigError::InvalidConfig("udpChainSteps count must be non-negative".to_string()));
        }
        group.udp_chain.push(UdpChainStep {
            kind: parse_udp_chain_step_kind(&step.kind)?,
            count: step.count,
            activation_filter: parse_proxy_activation_filter(
                step.activation_filter.as_ref(),
                "chains.udpSteps.activationFilter",
            )?,
        });
    }

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
        if fake_offset.base.is_adaptive() {
            return Err(ProxyConfigError::InvalidConfig(
                "Adaptive markers are not supported for fakeOffsetMarker".to_string(),
            ));
        }
        group.fake_offset = Some(fake_offset);
        if fake_packets.fake_tls_use_original {
            group.fake_mod |= FM_ORIG;
        }
        if fake_packets.fake_tls_randomize {
            group.fake_mod |= FM_RAND;
        }
        if fake_packets.fake_tls_dup_session_id {
            group.fake_mod |= FM_DUPSID;
        }
        if fake_packets.fake_tls_pad_encap {
            group.fake_mod |= FM_PADENCAP;
        }
        if fake_tls_sni_mode == FAKE_TLS_SNI_MODE_RANDOMIZED {
            group.fake_mod |= FM_RNDSNI;
        } else {
            group.fake_sni_list.push(fake_packets.fake_sni);
        }
        group.fake_tls_size = fake_packets.fake_tls_size;
    }

    if has_oob_step {
        group.oob_data = Some(fake_packets.oob_char);
    }

    group.sync_legacy_views_from_chains();

    let action_proto = group.proto;
    groups.push(group);
    if action_proto != 0 {
        groups.push(DesyncGroup::new(groups.len()));
    }

    config.groups = groups;
    if !matches!(config.listen.bind_ip, IpAddr::V6(_)) {
        config.ipv6 = false;
    }
    if config.host_autolearn_enabled && config.host_autolearn_store_path.is_none() {
        return Err(ProxyConfigError::InvalidConfig(
            "hostAutolearn.storePath is required when hostAutolearn.enabled is true".to_string(),
        ));
    }

    Ok(config)
}

pub fn parse_tcp_chain_step_kind(value: &str) -> Result<TcpChainStepKind, ProxyConfigError> {
    match value {
        "split" => Ok(TcpChainStepKind::Split),
        "disorder" => Ok(TcpChainStepKind::Disorder),
        "fake" => Ok(TcpChainStepKind::Fake),
        "fakedsplit" => Ok(TcpChainStepKind::FakeSplit),
        "fakeddisorder" => Ok(TcpChainStepKind::FakeDisorder),
        "hostfake" => Ok(TcpChainStepKind::HostFake),
        "oob" => Ok(TcpChainStepKind::Oob),
        "disoob" => Ok(TcpChainStepKind::Disoob),
        "tlsrec" => Ok(TcpChainStepKind::TlsRec),
        "tlsrandrec" => Ok(TcpChainStepKind::TlsRandRec),
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

fn validate_tcp_chain(steps: &[TcpChainStep]) -> Result<(), ProxyConfigError> {
    let mut saw_send_step = false;
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
    Ok(())
}

pub fn parse_udp_chain_step_kind(value: &str) -> Result<UdpChainStepKind, ProxyConfigError> {
    match value {
        "fake_burst" => Ok(UdpChainStepKind::FakeBurst),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown udpChainSteps kind: {value}"))),
    }
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
    ciadpi_config::parse_hosts_spec(hosts)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid hosts list".to_string()))
}

fn parse_offset_expr_field<F>(marker: Option<&str>, legacy: F, field_name: &str) -> Result<OffsetExpr, ProxyConfigError>
where
    F: FnOnce() -> String,
{
    let spec = marker.map(str::trim).filter(|value| !value.is_empty()).map_or_else(legacy, ToOwned::to_owned);
    ciadpi_config::parse_offset_expr(&spec)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")))
}
