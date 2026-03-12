use std::net::IpAddr;
use std::str::FromStr;

use ciadpi_config::{
    DesyncGroup, DesyncMode, OffsetExpr, PartSpec, QuicFakeProfile, QuicInitialMode, RuntimeConfig, StartupEnv,
    TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND,
    FM_RNDSNI, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
};
use ciadpi_packets::{IS_HTTP, IS_HTTPS, IS_UDP, MH_DMIX, MH_HMIX, MH_SPACE};
use serde::{Deserialize, Serialize};

const HOSTS_DISABLE: &str = "disable";
const HOSTS_BLACKLIST: &str = "blacklist";
const HOSTS_WHITELIST: &str = "whitelist";
const TLS_RANDREC_DEFAULT_FRAGMENT_COUNT: i32 = 4;
const TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE: i32 = 16;
const TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE: i32 = 96;
pub const FAKE_TLS_SNI_MODE_FIXED: &str = "fixed";
pub const FAKE_TLS_SNI_MODE_RANDOMIZED: &str = "randomized";
pub const QUIC_FAKE_PROFILE_DISABLED: &str = "disabled";

#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum ProxyConfigError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ProxyConfigPayload {
    CommandLine { args: Vec<String> },
    Ui(ProxyUiConfig),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiTcpChainStep {
    pub kind: String,
    pub marker: String,
    #[serde(default)]
    pub midhost_marker: Option<String>,
    #[serde(default)]
    pub fake_host_template: Option<String>,
    #[serde(default)]
    pub fragment_count: i32,
    #[serde(default)]
    pub min_fragment_size: i32,
    #[serde(default)]
    pub max_fragment_size: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiUdpChainStep {
    pub kind: String,
    pub count: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiConfig {
    pub ip: String,
    pub port: i32,
    pub max_connections: i32,
    pub buffer_size: i32,
    pub default_ttl: i32,
    pub custom_ttl: bool,
    pub no_domain: bool,
    pub desync_http: bool,
    pub desync_https: bool,
    pub desync_udp: bool,
    pub desync_method: String,
    #[serde(default)]
    pub split_marker: Option<String>,
    #[serde(default)]
    pub tcp_chain_steps: Vec<ProxyUiTcpChainStep>,
    #[serde(default)]
    pub split_position: i32,
    #[serde(default)]
    pub split_at_host: bool,
    pub fake_ttl: i32,
    pub fake_sni: String,
    #[serde(default)]
    pub fake_tls_use_original: bool,
    #[serde(default)]
    pub fake_tls_randomize: bool,
    #[serde(default)]
    pub fake_tls_dup_session_id: bool,
    #[serde(default)]
    pub fake_tls_pad_encap: bool,
    #[serde(default)]
    pub fake_tls_size: i32,
    #[serde(default = "default_fake_tls_sni_mode")]
    pub fake_tls_sni_mode: String,
    pub oob_char: u8,
    pub host_mixed_case: bool,
    pub domain_mixed_case: bool,
    pub host_remove_spaces: bool,
    pub tls_record_split: bool,
    #[serde(default)]
    pub tls_record_split_marker: Option<String>,
    #[serde(default)]
    pub tls_record_split_position: i32,
    #[serde(default)]
    pub tls_record_split_at_sni: bool,
    pub hosts_mode: String,
    pub hosts: Option<String>,
    pub tcp_fast_open: bool,
    pub udp_fake_count: i32,
    #[serde(default)]
    pub udp_chain_steps: Vec<ProxyUiUdpChainStep>,
    pub drop_sack: bool,
    #[serde(default)]
    pub fake_offset_marker: Option<String>,
    pub fake_offset: i32,
    #[serde(default)]
    pub quic_initial_mode: Option<String>,
    #[serde(default = "default_true")]
    pub quic_support_v1: bool,
    #[serde(default = "default_true")]
    pub quic_support_v2: bool,
    #[serde(default = "default_quic_fake_profile")]
    pub quic_fake_profile: String,
    #[serde(default)]
    pub quic_fake_host: String,
    #[serde(default)]
    pub host_autolearn_enabled: bool,
    #[serde(default = "default_host_autolearn_penalty_ttl_secs")]
    pub host_autolearn_penalty_ttl_secs: i64,
    #[serde(default = "default_host_autolearn_max_hosts")]
    pub host_autolearn_max_hosts: usize,
    #[serde(default)]
    pub host_autolearn_store_path: Option<String>,
}

fn default_true() -> bool {
    true
}

fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

fn default_quic_fake_profile() -> String {
    QUIC_FAKE_PROFILE_DISABLED.to_string()
}

fn default_host_autolearn_penalty_ttl_secs() -> i64 {
    HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS
}

fn default_host_autolearn_max_hosts() -> usize {
    HOST_AUTOLEARN_DEFAULT_MAX_HOSTS
}

pub fn normalize_fake_tls_sni_mode(value: &str) -> &'static str {
    match value.trim().to_ascii_lowercase().as_str() {
        FAKE_TLS_SNI_MODE_RANDOMIZED => FAKE_TLS_SNI_MODE_RANDOMIZED,
        _ => FAKE_TLS_SNI_MODE_FIXED,
    }
}

pub fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, ProxyConfigError> {
    serde_json::from_str::<ProxyConfigPayload>(json)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))
}

pub fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, ProxyConfigError> {
    match payload {
        ProxyConfigPayload::CommandLine { args } => runtime_config_from_command_line(args),
        ProxyConfigPayload::Ui(config) => runtime_config_from_ui(config),
    }
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
    let listen_ip =
        IpAddr::from_str(&payload.ip).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy IP".to_string()))?;
    let mut config = RuntimeConfig::default();
    config.listen.listen_ip = listen_ip;
    config.listen.listen_port =
        u16::try_from(payload.port).map_err(|_| ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()))?;
    if config.listen.listen_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid proxy port".to_string()));
    }
    if payload.max_connections <= 0 {
        return Err(ProxyConfigError::InvalidConfig("maxConnections must be positive".to_string()));
    }
    config.max_open = payload.max_connections;
    config.buffer_size = usize::try_from(payload.buffer_size)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid bufferSize".to_string()))?;
    if config.buffer_size == 0 {
        return Err(ProxyConfigError::InvalidConfig("bufferSize must be positive".to_string()));
    }
    if payload.udp_fake_count < 0 {
        return Err(ProxyConfigError::InvalidConfig("udpFakeCount must be non-negative".to_string()));
    }
    config.resolve = !payload.no_domain;
    config.tfo = payload.tcp_fast_open;
    config.quic_initial_mode =
        parse_quic_initial_mode(payload.quic_initial_mode.as_deref().unwrap_or("route_and_cache"))?;
    config.quic_support_v1 = payload.quic_support_v1;
    config.quic_support_v2 = payload.quic_support_v2;
    config.host_autolearn_enabled = payload.host_autolearn_enabled;
    config.host_autolearn_penalty_ttl_secs = payload.host_autolearn_penalty_ttl_secs.max(1);
    config.host_autolearn_max_hosts = payload.host_autolearn_max_hosts.max(1);
    config.host_autolearn_store_path = payload
        .host_autolearn_store_path
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    if payload.custom_ttl {
        let ttl = u8::try_from(payload.default_ttl)
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
    if payload.hosts_mode == HOSTS_WHITELIST {
        let mut whitelist = DesyncGroup::new(0);
        whitelist.filters.hosts = parse_hosts(payload.hosts.as_deref())?;
        groups.push(whitelist);
    }

    let mut group = DesyncGroup::new(groups.len());
    match payload.hosts_mode.as_str() {
        HOSTS_DISABLE | HOSTS_WHITELIST => {}
        HOSTS_BLACKLIST => {
            group.filters.hosts = parse_hosts(payload.hosts.as_deref())?;
        }
        _ => return Err(ProxyConfigError::InvalidConfig("Unknown hostsMode".to_string())),
    }

    if payload.fake_ttl > 0 {
        group.ttl =
            Some(u8::try_from(payload.fake_ttl).map_err(|_| ProxyConfigError::InvalidConfig("Invalid fakeTtl".to_string()))?);
    }
    group.drop_sack = payload.drop_sack;
    group.proto = (u32::from(payload.desync_http) * IS_HTTP)
        | (u32::from(payload.desync_https) * IS_HTTPS)
        | (u32::from(payload.desync_udp) * IS_UDP);
    group.quic_fake_profile = parse_quic_fake_profile(&payload.quic_fake_profile)?;
    group.quic_fake_host = {
        let host = payload.quic_fake_host.trim();
        if host.is_empty() {
            None
        } else {
            ciadpi_config::normalize_quic_fake_host(host).ok()
        }
    };
    group.mod_http = (u32::from(payload.host_mixed_case) * MH_HMIX)
        | (u32::from(payload.domain_mixed_case) * MH_DMIX)
        | (u32::from(payload.host_remove_spaces) * MH_SPACE);

    if !payload.tcp_chain_steps.is_empty() {
        for step in &payload.tcp_chain_steps {
            let kind = parse_tcp_chain_step_kind(&step.kind)?;
            let offset = parse_offset_expr_field(Some(step.marker.as_str()), || "0".to_string(), "tcpChainSteps")?;
            let midhost_offset = step
                .midhost_marker
                .as_deref()
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(ciadpi_config::parse_offset_expr)
                .transpose()
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid tcpChainSteps midhostMarker".to_string()))?;
            let fake_host_template = step
                .fake_host_template
                .as_deref()
                .map(str::trim)
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
            group.tcp_chain.push(TcpChainStep {
                kind,
                offset,
                midhost_offset,
                fake_host_template,
                fragment_count,
                min_fragment_size,
                max_fragment_size,
            });
        }
    } else {
        let part_offset = parse_offset_expr_field(
            payload.split_marker.as_deref(),
            || legacy_marker_expression(payload.split_position, payload.split_at_host),
            "splitMarker",
        )?;
        let desync_mode = parse_desync_mode(&payload.desync_method)?;
        if desync_mode != DesyncMode::None {
            group.parts.push(PartSpec { mode: desync_mode, offset: part_offset });
            if let Some(kind) = TcpChainStepKind::from_mode(desync_mode) {
                group.tcp_chain.push(TcpChainStep::new(kind, part_offset));
            }
        }

        if payload.tls_record_split {
            let expr = parse_offset_expr_field(
                payload.tls_record_split_marker.as_deref(),
                || legacy_marker_expression(payload.tls_record_split_position, payload.tls_record_split_at_sni),
                "tlsRecordSplitMarker",
            )?;
            group.tls_records.push(expr);
            group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, expr));
        }
    }

    if !payload.udp_chain_steps.is_empty() {
        for step in &payload.udp_chain_steps {
            if step.count < 0 {
                return Err(ProxyConfigError::InvalidConfig("udpChainSteps count must be non-negative".to_string()));
            }
            group.udp_chain.push(UdpChainStep { kind: parse_udp_chain_step_kind(&step.kind)?, count: step.count });
        }
    } else {
        group.udp_fake_count = payload.udp_fake_count;
        if payload.udp_fake_count > 0 {
            group.udp_chain.push(UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: payload.udp_fake_count });
        }
    }

    let has_fake_step = group.effective_tcp_chain().iter().any(|step| matches!(step.kind, TcpChainStepKind::Fake));
    let has_oob_step = group
        .effective_tcp_chain()
        .iter()
        .any(|step| matches!(step.kind, TcpChainStepKind::Oob | TcpChainStepKind::Disoob));

    if has_fake_step {
        let fake_tls_sni_mode = normalize_fake_tls_sni_mode(&payload.fake_tls_sni_mode);
        group.fake_offset = Some(parse_offset_expr_field(
            payload.fake_offset_marker.as_deref(),
            || payload.fake_offset.to_string(),
            "fakeOffsetMarker",
        )?);
        if payload.fake_tls_use_original {
            group.fake_mod |= FM_ORIG;
        }
        if payload.fake_tls_randomize {
            group.fake_mod |= FM_RAND;
        }
        if payload.fake_tls_dup_session_id {
            group.fake_mod |= FM_DUPSID;
        }
        if payload.fake_tls_pad_encap {
            group.fake_mod |= FM_PADENCAP;
        }
        if fake_tls_sni_mode == FAKE_TLS_SNI_MODE_RANDOMIZED {
            group.fake_mod |= FM_RNDSNI;
        } else {
            group.fake_sni_list.push(payload.fake_sni);
        }
        group.fake_tls_size = payload.fake_tls_size;
    }

    if has_oob_step {
        group.oob_data = Some(payload.oob_char);
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
            "hostAutolearnStorePath is required when hostAutolearnEnabled is true".to_string(),
        ));
    }

    Ok(config)
}

pub fn parse_tcp_chain_step_kind(value: &str) -> Result<TcpChainStepKind, ProxyConfigError> {
    match value {
        "split" => Ok(TcpChainStepKind::Split),
        "disorder" => Ok(TcpChainStepKind::Disorder),
        "fake" => Ok(TcpChainStepKind::Fake),
        "hostfake" => Ok(TcpChainStepKind::HostFake),
        "oob" => Ok(TcpChainStepKind::Oob),
        "disoob" => Ok(TcpChainStepKind::Disoob),
        "tlsrec" => Ok(TcpChainStepKind::TlsRec),
        "tlsrandrec" => Ok(TcpChainStepKind::TlsRandRec),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown tcpChainSteps kind: {value}"))),
    }
}

fn normalize_tlsrandrec_step_field(value: i32, default: i32) -> i32 {
    if value > 0 { value } else { default }
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
    ciadpi_config::parse_hosts_spec(hosts).map_err(|_| ProxyConfigError::InvalidConfig("Invalid hosts list".to_string()))
}

fn parse_offset_expr_field<F>(marker: Option<&str>, legacy: F, field_name: &str) -> Result<OffsetExpr, ProxyConfigError>
where
    F: FnOnce() -> String,
{
    let spec = marker.map(str::trim).filter(|value| !value.is_empty()).map(ToOwned::to_owned).unwrap_or_else(legacy);
    ciadpi_config::parse_offset_expr(&spec)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")))
}

fn legacy_marker_expression(position: i32, use_host_marker: bool) -> String {
    if use_host_marker {
        marker_expression("host", position)
    } else {
        position.to_string()
    }
}

fn marker_expression(base: &str, delta: i32) -> String {
    match delta.cmp(&0) {
        std::cmp::Ordering::Equal => base.to_string(),
        std::cmp::Ordering::Greater => format!("{base}+{delta}"),
        std::cmp::Ordering::Less => format!("{base}{delta}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal_ui() -> ProxyUiConfig {
        ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: true,
            desync_method: "disorder".to_string(),
            split_marker: Some("host+1".to_string()),
            tcp_chain_steps: Vec::new(),
            split_position: 0,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.wikipedia.org".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: FAKE_TLS_SNI_MODE_FIXED.to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            drop_sack: false,
            fake_offset_marker: Some("0".to_string()),
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: QUIC_FAKE_PROFILE_DISABLED.to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
        }
    }

    #[test]
    fn ui_payload_parses_hostfake_and_quic_profile() {
        let mut ui = minimal_ui();
        ui.tcp_chain_steps.push(ProxyUiTcpChainStep {
            kind: "hostfake".to_string(),
            marker: "endhost+8".to_string(),
            midhost_marker: Some("midsld".to_string()),
            fake_host_template: Some("googlevideo.com".to_string()),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
        });
        ui.udp_chain_steps.push(ProxyUiUdpChainStep { kind: "fake_burst".to_string(), count: 3 });
        ui.quic_fake_profile = "realistic_initial".to_string();
        ui.quic_fake_host = "Example.COM.".to_string();

        let config = runtime_config_from_payload(ProxyConfigPayload::Ui(ui)).expect("runtime config");

        assert_eq!(config.groups[0].quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(config.groups[0].quic_fake_host.as_deref(), Some("example.com"));
        assert_eq!(config.groups[0].tcp_chain[0].kind, TcpChainStepKind::HostFake);
        assert_eq!(config.groups[0].udp_chain[0].count, 3);
    }

    #[test]
    fn command_line_payload_requires_runnable_config() {
        let err = runtime_config_from_payload(ProxyConfigPayload::CommandLine {
            args: vec!["ciadpi".to_string(), "--help".to_string()],
        })
        .expect_err("help should not produce runnable config");

        assert!(err.to_string().contains("runnable"));
    }

    #[test]
    fn invalid_quic_fake_profile_is_rejected() {
        let mut ui = minimal_ui();
        ui.quic_fake_profile = "bogus".to_string();

        let err = runtime_config_from_payload(ProxyConfigPayload::Ui(ui)).expect_err("invalid quic profile");

        assert!(err.to_string().contains("quicFakeProfile"));
    }
}
