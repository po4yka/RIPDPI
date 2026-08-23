use std::io::{self, Write};

use ripdpi_monitor_adapter::proxy_config::{ProxyConfigPayload, parse_proxy_config_json};

use crate::types::{ScanKind, ScanPathMode};
use ripdpi_diagnostics_contracts::{DIAGNOSTICS_ENGINE_SCHEMA_VERSION, EngineScanRequestWire};

pub const MAX_REQUEST_WIRE_BYTES: usize = 4 * 1024 * 1024;
const MAX_PROFILE_ID_BYTES: usize = 256;
const MAX_DISPLAY_NAME_BYTES: usize = 1024;
const MAX_TARGETS_PER_KIND: usize = 256;
const MAX_TOTAL_TARGETS: usize = 1024;
const MAX_PROBE_TASKS: usize = 1024;
const MAX_THROUGHPUT_RUNS: usize = 8;
const MAX_THROUGHPUT_WINDOW_BYTES: usize = 64 * 1024 * 1024;
const MAX_TOTAL_THROUGHPUT_BYTES: usize = 512 * 1024 * 1024;
const MAX_FAT_HEADER_REQUESTS: usize = 64;
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

    let mut total_throughput_bytes = 0usize;
    for target in &request.throughput_targets {
        if !(1..=MAX_THROUGHPUT_RUNS).contains(&target.runs) {
            return Err(format!("throughput runs must be between 1 and {MAX_THROUGHPUT_RUNS}"));
        }
        if !(1..=MAX_THROUGHPUT_WINDOW_BYTES).contains(&target.window_bytes) {
            return Err(format!("throughput windowBytes must be between 1 and {MAX_THROUGHPUT_WINDOW_BYTES}"));
        }
        let target_bytes =
            target.window_bytes.checked_mul(target.runs).ok_or_else(|| "throughput work size overflow".to_string())?;
        total_throughput_bytes = total_throughput_bytes
            .checked_add(target_bytes)
            .ok_or_else(|| "total throughput work size overflow".to_string())?;
    }
    if total_throughput_bytes > MAX_TOTAL_THROUGHPUT_BYTES {
        return Err(format!("total throughput work exceeds {MAX_TOTAL_THROUGHPUT_BYTES} bytes"));
    }

    let mut total_fat_header_requests = 0usize;
    for target in &request.tcp_targets {
        let requests = target.fat_header_requests.unwrap_or(1);
        if !(1..=MAX_FAT_HEADER_REQUESTS).contains(&requests) {
            return Err(format!("fatHeaderRequests must be between 1 and {MAX_FAT_HEADER_REQUESTS}"));
        }
        total_fat_header_requests = total_fat_header_requests
            .checked_add(requests)
            .ok_or_else(|| "total fatHeaderRequests overflow".to_string())?;
    }
    if total_fat_header_requests > MAX_TOTAL_FAT_HEADER_REQUESTS {
        return Err(format!("total fatHeaderRequests exceeds {MAX_TOTAL_FAT_HEADER_REQUESTS}"));
    }

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

    if let Some(deadline_ms) = request.scan_deadline_ms
        && !(MIN_SCAN_DEADLINE_MS..=MAX_SCAN_DEADLINE_MS).contains(&deadline_ms)
    {
        return Err(format!("scanDeadlineMs must be between {MIN_SCAN_DEADLINE_MS} and {MAX_SCAN_DEADLINE_MS}"));
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
