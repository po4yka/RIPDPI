use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::types::{IpFamilyEvidence, IpFamilyProbeConfig, ProbeDetail, ProbeResult};
use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, with_scan_io_deadline};
use ripdpi_diagnostics_dns::dns::{Nat64Discovery, discover_network_nat64};
use ripdpi_diagnostics_transport::transport::{TransportConfig, connect_direct_address};

/// Independent numeric destination probes, with no hostname or cross-family fallback.
/// Each operation owns its socket and observes cancellation before and after bounded blocking I/O.
pub fn run_ip_family_probes(
    config: &IpFamilyProbeConfig,
    servers: &[String],
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
) -> Vec<ProbeResult> {
    ["IPV4", "IPV6", "NAT64"]
        .into_iter()
        .map(|family| {
            let started = Instant::now();
            let mut evidence = IpFamilyEvidence {
                version: 1,
                family: family.into(),
                stage: "TCP_CONNECT".into(),
                status: "NOT_OBSERVED".into(),
                reason: None,
                duration_ms: None,
                destination_address: None,
                destination_port: config.port,
                attempt_count: 0,
                path_scope: "RAW_PATH".into(),
                prefix: None,
                prefix_length: None,
                discovery_status: (family == "NAT64").then(|| "NOT_RUN".into()),
                resolver_source: (family == "NAT64").then(|| "NETWORK_SNAPSHOT".into()),
            };
            if !raw_path || !matches!(transport, TransportConfig::Direct { route_experiment: None }) {
                evidence.path_scope = "UNSUPPORTED_PROXY".into();
                fail(&mut evidence, "UNSUPPORTED", "unsupported_path");
            } else if config.validate().is_err() {
                fail(&mut evidence, "INVALID", "invalid_config");
            } else {
                let own_deadline = started + Duration::from_millis(config.timeout_ms);
                let deadline = active_scan_io_deadline().map_or(own_deadline, |outer| outer.min(own_deadline));
                with_scan_io_deadline(Some(deadline), || execute(config, servers, cancel, &mut evidence));
            }
            evidence.duration_ms = Some(started.elapsed().as_millis().min(120_000) as u64);
            result(evidence)
        })
        .collect()
}
fn execute(config: &IpFamilyProbeConfig, servers: &[String], cancel: &AtomicBool, evidence: &mut IpFamilyEvidence) {
    execute_with(config, servers, cancel, evidence, discover_network_nat64, |address| {
        connect_direct_address(address).map(drop)
    });
}
fn execute_with(
    config: &IpFamilyProbeConfig,
    servers: &[String],
    cancel: &AtomicBool,
    evidence: &mut IpFamilyEvidence,
    discover: impl FnOnce(&[String], &AtomicBool) -> Nat64Discovery,
    mut connect_peer: impl FnMut(std::net::SocketAddr) -> std::io::Result<()>,
) {
    if stop(cancel, evidence) {
        return;
    }
    if evidence.family == "NAT64" {
        evidence.stage = "DNS64_DISCOVERY".into();
        let discovery = discover(servers, cancel);
        evidence.attempt_count = discovery.attempts;
        evidence.discovery_status = Some(discovery.status.into());
        if stop(cancel, evidence) {
            return;
        }
        if discovery.prefixes.is_empty() {
            let status = if discovery.status == "INVALID" { "INVALID" } else { "NOT_OBSERVED" };
            fail(evidence, status, discovery.reason.unwrap_or("no_prefix"));
            return;
        }
        for prefix in discovery.prefixes {
            let attempts_before = evidence.attempt_count;
            let address = config.ipv4_address.parse().ok().and_then(|ip| prefix.synthesize(ip)).map(IpAddr::V6);
            connect(address, config, cancel, evidence, &mut connect_peer);
            if evidence.attempt_count > attempts_before {
                evidence.prefix = Some(prefix.address.to_string());
                evidence.prefix_length = Some(prefix.length);
            }
            if evidence.status == "REACHABLE" || stop(cancel, evidence) {
                return;
            }
        }
    } else {
        let raw = if evidence.family == "IPV4" { &config.ipv4_address } else { &config.ipv6_address };
        connect(raw.parse().ok(), config, cancel, evidence, &mut connect_peer);
    }
}
fn connect(
    address: Option<IpAddr>,
    config: &IpFamilyProbeConfig,
    cancel: &AtomicBool,
    evidence: &mut IpFamilyEvidence,
    connect_peer: &mut impl FnMut(std::net::SocketAddr) -> std::io::Result<()>,
) {
    if stop(cancel, evidence) {
        return;
    }
    let Some(address) = address else {
        fail(evidence, "INVALID", "invalid_config");
        return;
    };
    if matches!(address,IpAddr::V6(v6) if v6.to_ipv4_mapped().is_some()) {
        fail(evidence, "INVALID", "invalid_dns64_response");
        return;
    }
    evidence.stage = "TCP_CONNECT".into();
    evidence.destination_address = Some(address.to_string());
    evidence.attempt_count += 1;
    let connected = connect_peer(std::net::SocketAddr::new(address, config.port));
    if stop(cancel, evidence) {
        return;
    }
    match connected {
        Ok(_) => {
            evidence.status = "REACHABLE".into();
            evidence.reason = None;
        }
        Err(error) => {
            let (status, reason) = match error.kind() {
                std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock => ("TIMEOUT", "timeout"),
                std::io::ErrorKind::ConnectionRefused => ("FAILED", "connection_refused"),
                std::io::ErrorKind::NetworkUnreachable => ("FAILED", "network_unreachable"),
                std::io::ErrorKind::HostUnreachable => ("FAILED", "host_unreachable"),
                std::io::ErrorKind::PermissionDenied => ("FAILED", "permission_denied"),
                _ => ("FAILED", "io_error"),
            };
            fail(evidence, status, reason);
        }
    }
}
fn stop(cancel: &AtomicBool, evidence: &mut IpFamilyEvidence) -> bool {
    if cancel.load(Ordering::Acquire) {
        fail(evidence, "CANCELLED", "cancelled");
        true
    } else if active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline) {
        fail(evidence, "TIMEOUT", "deadline_exceeded");
        true
    } else {
        false
    }
}
fn fail(evidence: &mut IpFamilyEvidence, status: &str, reason: &str) {
    evidence.status = status.into();
    evidence.reason = Some(reason.into());
}
fn result(evidence: IpFamilyEvidence) -> ProbeResult {
    let outcome = match evidence.status.as_str() {
        "REACHABLE" => "ip_family_reachable",
        "FAILED" | "TIMEOUT" if evidence.stage == "TCP_CONNECT" && evidence.attempt_count > 0 => {
            "ip_family_unavailable"
        }
        "CANCELLED" => "ip_family_cancelled",
        _ => "ip_family_inconclusive",
    };
    ProbeResult {
        probe_type: "ip_family".into(),
        target: evidence.family.clone(),
        outcome: outcome.into(),
        details: vec![ProbeDetail {
            key: "ipFamilyEvidence".into(),
            value: evidence.to_detail_json().unwrap_or_default(),
        }],
    }
}

#[cfg(test)]
mod tests;
