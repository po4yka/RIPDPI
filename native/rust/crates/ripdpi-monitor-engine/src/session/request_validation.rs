use std::io::{self, Write};
use std::net::IpAddr;

use ripdpi_diagnostics_contracts::util::MAX_ROUTE_EXPERIMENT_ATTEMPTS;
use ripdpi_proxy_config::{ProxyConfigPayload, parse_proxy_config_json};

use crate::types::{
    ECH_TRANSFER_COMPARISON_LEGS, ECH_TRANSFER_COMPARISON_PROFILE_ID, ProbeTaskFamily, ScanKind, ScanPathMode,
    VERIFIED_DOWNLOAD_PROFILE_ID, VERIFIED_UPLOAD_PROFILE_ID,
};
use ripdpi_diagnostics_contracts::util::{
    FAT_HEADER_MAX_ALTERNATES, FAT_HEADER_MAX_REQUESTS, FAT_HEADER_REQUESTS, is_valid_fat_header_authority,
};
use ripdpi_diagnostics_contracts::{DIAGNOSTICS_ENGINE_SCHEMA_VERSION, EngineScanRequestWire};
use ripdpi_tls_profiles::normalize_server_name;

pub const MAX_REQUEST_WIRE_BYTES: usize = 4 * 1024 * 1024;
const MAX_PROFILE_ID_BYTES: usize = 256;
const MAX_DISPLAY_NAME_BYTES: usize = 1024;
const MAX_TARGETS_PER_KIND: usize = 256;
const MAX_TOTAL_TARGETS: usize = 1024;
const MAX_PROBE_TASKS: usize = 1024;
const MAX_THROUGHPUT_RUNS: usize = 8;
const MAX_THROUGHPUT_WINDOW_BYTES: usize = 64 * 1024 * 1024;
const MAX_TOTAL_THROUGHPUT_BYTES: usize = 512 * 1024 * 1024;
const MAX_FAT_HEADER_WHITELIST_SNI: usize = 256;
const MAX_TOTAL_FAT_HEADER_REQUESTS: usize = 4096;
const MAX_STRATEGY_CANDIDATES: usize = 128;
const MIN_SCAN_DEADLINE_MS: u64 = 1_000;
const MAX_SCAN_DEADLINE_MS: u64 = 600_000;
const MAX_PROXY_CONFIG_BYTES: usize = 1024 * 1024;

struct RequestSizeWriter {
    written: usize,
}

impl Write for RequestSizeWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let next =
            self.written.checked_add(buf.len()).ok_or_else(|| io::Error::other("diagnostics request size overflow"))?;
        if next > MAX_REQUEST_WIRE_BYTES {
            return Err(io::Error::other(format!("diagnostics request size exceeds {MAX_REQUEST_WIRE_BYTES} bytes")));
        }
        self.written = next;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub fn validate_scan_request(request: &EngineScanRequestWire) -> Result<(), String> {
    if request.schema_version != DIAGNOSTICS_ENGINE_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported diagnostics schema {} (expected {})",
            request.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION
        ));
    }
    validate_resource_limits(request)?;
    if let Some(target) = &request.telegram_target {
        crate::telegram::validate_telegram_target(target)?;
    }
    for (family, configured, field) in [
        (ProbeTaskFamily::SelectiveAvailability, request.selective_matrix.is_some(), "selectiveMatrix"),
        (ProbeTaskFamily::Http3, request.http3_probe.is_some(), "http3Probe"),
        (ProbeTaskFamily::Pmtu, request.pmtu_probe.is_some(), "pmtuProbe"),
        (ProbeTaskFamily::TlsHopComparison, request.tls_hop_comparison.is_some(), "tlsHopComparison"),
        (ProbeTaskFamily::QuicVersionMatrix, request.quic_version_matrix.is_some(), "quicVersionMatrix"),
        (ProbeTaskFamily::IpFamily, request.ip_family_probe.is_some(), "ipFamilyProbe"),
    ] {
        if configured != request.probe_tasks.iter().any(|task| task.family == family) {
            return Err(format!("{field} requires a matching configuration and explicit probe task"));
        }
    }
    if let Some(config) = &request.selective_matrix {
        if request.kind != ScanKind::Connectivity {
            return Err("selectiveMatrix requires a connectivity scan".into());
        }
        config.validate().map_err(str::to_string)?;
    }
    if let Some(config) = &request.http3_probe {
        if request.kind != ScanKind::Connectivity {
            return Err("http3Probe requires a connectivity scan".into());
        }
        config.validate().map_err(str::to_owned)?;
    }
    if let Some(config) = &request.pmtu_probe {
        if request.kind != ScanKind::Connectivity {
            return Err("pmtuProbe requires a connectivity scan".into());
        }
        config.validate().map_err(str::to_owned)?;
    }
    if let Some(config) = &request.tls_hop_comparison {
        if request.kind != ScanKind::Connectivity {
            return Err("tlsHopComparison requires a connectivity scan".into());
        }
        config.validate().map_err(str::to_owned)?;
    }
    if let Some(config) = &request.quic_version_matrix {
        if request.kind != ScanKind::Connectivity {
            return Err("quicVersionMatrix requires a connectivity scan".into());
        }
        config.validate().map_err(str::to_owned)?;
    }
    if let Some(config) = &request.ip_family_probe {
        if request.kind != ScanKind::Connectivity {
            return Err("ipFamilyProbe requires a connectivity scan".into());
        }
        config.validate().map_err(str::to_string)?;
    }
    if request.kind == ScanKind::StrategyProbe {
        let strategy_probe = request
            .strategy_probe
            .as_ref()
            .ok_or_else(|| "strategy_probe scan requires strategyProbe request".to_string())?;
        let Some(config_json) = strategy_probe.base_proxy_config_json.as_deref() else {
            return Err("strategy_probe scan requires baseProxyConfigJson".to_string());
        };
        match parse_proxy_config_json(config_json).map_err(|err| err.to_string())? {
            ProxyConfigPayload::Ui { .. } => {}
            ProxyConfigPayload::CommandLine { .. } => {
                return Err("strategy_probe scans only support UI proxy config".to_string());
            }
        }
    }

    if let Some(route) = request.in_path_route.as_ref() {
        if request.path_mode != ScanPathMode::InPath {
            return Err("inPathRoute is only valid for IN_PATH diagnostics".to_string());
        }
        if !matches!(route.host.as_str(), "127.0.0.1" | "::1") || route.port == 0 {
            return Err("inPathRoute requires a valid loopback listener".to_string());
        }
        if !route.credentials.is_valid() {
            return Err("inPathRoute requires bounded SOCKS5 credentials".to_string());
        }
        return Ok(());
    }
    match (request.proxy_host.as_deref(), request.proxy_port) {
        (Some(host), Some(port)) if !host.trim().is_empty() && port > 0 => Ok(()),
        (None, None) if request.path_mode == ScanPathMode::RawPath => Ok(()),
        _ => Err("IN_PATH diagnostics require proxyHost/proxyPort".to_string()),
    }
}

fn validate_resource_limits(request: &EngineScanRequestWire) -> Result<(), String> {
    serde_json::to_writer(RequestSizeWriter { written: 0 }, request)
        .map_err(|error| format!("Invalid diagnostics request size: {error}"))?;
    validate_text("profileId", &request.profile_id, MAX_PROFILE_ID_BYTES)?;
    validate_text("displayName", &request.display_name, MAX_DISPLAY_NAME_BYTES)?;
    validate_count("probeTasks", request.probe_tasks.len(), MAX_PROBE_TASKS)?;

    let target_counts = [
        ("domainTargets", request.domain_targets.len()),
        ("dnsTargets", request.dns_targets.len()),
        ("tcpTargets", request.tcp_targets.len()),
        ("quicTargets", request.quic_targets.len()),
        ("serviceTargets", request.service_targets.len()),
        ("circumventionTargets", request.circumvention_targets.len()),
        ("throughputTargets", request.throughput_targets.len()),
    ];
    let mut total_targets = 0usize;
    for (label, count) in target_counts {
        validate_count(label, count, MAX_TARGETS_PER_KIND)?;
        total_targets =
            total_targets.checked_add(count).ok_or_else(|| "diagnostics target count overflow".to_string())?;
    }
    validate_count("total targets", total_targets, MAX_TOTAL_TARGETS)?;

    validate_verified_transfer_profile(request)?;
    let mut total_throughput_bytes = 0usize;
    for target in &request.throughput_targets {
        target.validate_download().map_err(str::to_string)?;
        target.validate_upload().map_err(str::to_string)?;
        if !(1..=MAX_THROUGHPUT_RUNS).contains(&target.runs) {
            return Err(format!("throughput runs must be between 1 and {MAX_THROUGHPUT_RUNS}"));
        }
        if !(1..=MAX_THROUGHPUT_WINDOW_BYTES).contains(&target.window_bytes) {
            return Err(format!("throughput windowBytes must be between 1 and {MAX_THROUGHPUT_WINDOW_BYTES}"));
        }
        let runs = if request.profile_id == ECH_TRANSFER_COMPARISON_PROFILE_ID {
            ECH_TRANSFER_COMPARISON_LEGS
        } else {
            target.runs
        };
        let target_bytes =
            target.window_bytes.checked_mul(runs).ok_or_else(|| "throughput work size overflow".to_string())?;
        total_throughput_bytes = total_throughput_bytes
            .checked_add(target_bytes)
            .ok_or_else(|| "total throughput work size overflow".to_string())?;
    }
    if total_throughput_bytes > MAX_TOTAL_THROUGHPUT_BYTES {
        return Err(format!("total throughput work exceeds {MAX_TOTAL_THROUGHPUT_BYTES} bytes"));
    }

    validate_fat_header_work(request)?;

    if let Some(strategy_probe) = &request.strategy_probe {
        if let Some(max_candidates) = strategy_probe.max_candidates
            && !(1..=MAX_STRATEGY_CANDIDATES).contains(&max_candidates)
        {
            return Err(format!("strategyProbe.maxCandidates must be between 1 and {MAX_STRATEGY_CANDIDATES}"));
        }
        if let Some(config_json) = &strategy_probe.base_proxy_config_json {
            validate_text("strategyProbe.baseProxyConfigJson", config_json, MAX_PROXY_CONFIG_BYTES)?;
        }
        if let Some(selection) = &strategy_probe.target_selection {
            validate_count("strategyProbe.domainHosts", selection.domain_hosts.len(), MAX_TARGETS_PER_KIND)?;
            validate_count("strategyProbe.quicHosts", selection.quic_hosts.len(), MAX_TARGETS_PER_KIND)?;
        }
    }

    if let Some(route) = &request.route_probe {
        validate_count("routeProbe.stableFlowAttempts", route.stable_flow_attempts, MAX_ROUTE_EXPERIMENT_ATTEMPTS)?;
        validate_count("routeProbe.diversityBuckets", route.diversity_buckets, MAX_ROUTE_EXPERIMENT_ATTEMPTS)?;
    }
    if let Some(deadline_ms) = request.scan_deadline_ms
        && !(MIN_SCAN_DEADLINE_MS..=MAX_SCAN_DEADLINE_MS).contains(&deadline_ms)
    {
        return Err(format!("scanDeadlineMs must be between {MIN_SCAN_DEADLINE_MS} and {MAX_SCAN_DEADLINE_MS}"));
    }
    Ok(())
}

fn validate_verified_transfer_profile(request: &EngineScanRequestWire) -> Result<(), String> {
    let targets = request.throughput_targets.as_slice();
    let valid = match request.profile_id.as_str() {
        VERIFIED_DOWNLOAD_PROFILE_ID => {
            request.kind == ScanKind::Connectivity
                && matches!(targets, [target] if target.verification.as_ref().is_some_and(|proof| proof.ech_comparison_ip.is_none())
                    && target.upload_body_bytes.is_none())
        }
        ECH_TRANSFER_COMPARISON_PROFILE_ID => {
            request.kind == ScanKind::Connectivity
                && matches!(targets, [target] if target.verification.as_ref().is_some_and(|proof| proof.ech_comparison_ip.is_some())
                    && target.upload_body_bytes.is_none())
        }
        VERIFIED_UPLOAD_PROFILE_ID => {
            request.kind == ScanKind::Connectivity
                && matches!(targets, [target] if target.upload_body_bytes.is_some() && target.verification.is_none())
        }
        _ => targets.iter().all(|target| target.verification.is_none() && target.upload_body_bytes.is_none()),
    };
    if !valid {
        return Err("verified transfer requires one matching target in its dedicated connectivity profile".into());
    }
    Ok(())
}

fn validate_count(label: &str, count: usize, max: usize) -> Result<(), String> {
    if count > max {
        return Err(format!("{label} count {count} exceeds {max}"));
    }
    Ok(())
}

fn validate_text(label: &str, value: &str, max_bytes: usize) -> Result<(), String> {
    if value.len() > max_bytes {
        return Err(format!("{label} size {} exceeds {max_bytes} bytes", value.len()));
    }
    Ok(())
}

fn validate_fat_header_work(request: &EngineScanRequestWire) -> Result<(), String> {
    validate_count("whitelistSni", request.whitelist_sni.len(), MAX_FAT_HEADER_WHITELIST_SNI)?;
    for candidate in &request.whitelist_sni {
        if !normalize_server_name(candidate).is_ok_and(|name| name.parse::<IpAddr>().is_err()) {
            return Err("whitelistSni requires DNS names".to_string());
        }
    }

    let alternate_attempts = request.whitelist_sni.len().min(FAT_HEADER_MAX_ALTERNATES);
    let mut total_requests = 0;
    for target in &request.tcp_targets {
        if target.ip.parse::<IpAddr>().is_err() || target.port == 0 || target.alt_port == Some(0) {
            return Err("tcpTargets require a numeric IP address and nonzero ports".to_string());
        }
        if target.sni.as_deref().is_some_and(|name| normalize_server_name(name).is_err()) {
            return Err("tcpTargets.sni requires a valid TLS/DNS name or IP address".to_string());
        }
        if target.host_header.as_deref().is_some_and(|host| !is_valid_fat_header_authority(host)) {
            return Err("tcpTargets.hostHeader requires a valid HTTP authority".to_string());
        }

        let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS);
        if !(1..=FAT_HEADER_MAX_REQUESTS).contains(&requests) {
            return Err(format!("fatHeaderRequests must be between 1 and {FAT_HEADER_MAX_REQUESTS}"));
        }

        // Only a failed TCP connect can repeat. It sends no HEAD requests.
        let attempts =
            1 + usize::from(target.alt_port.is_some()) + if target.sni.is_some() { alternate_attempts } else { 0 };
        total_requests += requests * attempts;
    }
    if total_requests > MAX_TOTAL_FAT_HEADER_REQUESTS {
        return Err(format!("total fatHeaderRequests exceeds {MAX_TOTAL_FAT_HEADER_REQUESTS}"));
    }
    Ok(())
}
