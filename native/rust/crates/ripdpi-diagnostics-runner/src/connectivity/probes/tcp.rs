use crate::connectivity::adapters::fat_header::{
    FatHeaderObservation, FatHeaderStatus, classify_fat_header_outcome, classify_tcp_block_method, fat_status_label,
    run_fat_header_attempt_with_key_log,
};
use crate::connectivity::adapters::tls::TlsKeyLogCallback;
use crate::connectivity::adapters::transport::{TargetAddress, TransportConfig};
use ripdpi_diagnostics_contracts::util::FAT_HEADER_MAX_ALTERNATES;
use ripdpi_diagnostics_tls::tls::make_server_name;
use rustls::pki_types::ServerName;
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::types::{ProbeDetail, ProbeResult, TcpTarget};

pub fn run_tcp_probe(
    target: &TcpTarget,
    whitelist_sni: &[String],
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeResult {
    run_tcp_probe_with_cancel(target, whitelist_sni, transport, key_log, &AtomicBool::new(false))
}

/// Like [`run_tcp_probe`], but a set `cancel` flag stops the connect retry, the whitelist SNI
/// candidates and the alternative-port probe; only the observations already made are reported.
pub fn run_tcp_probe_with_cancel(
    target: &TcpTarget,
    whitelist_sni: &[String],
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    cancel: &AtomicBool,
) -> ProbeResult {
    let stopped = || {
        cancel.load(Ordering::Acquire)
            || ripdpi_diagnostics_contracts::util::active_scan_io_deadline()
                .is_some_and(|deadline| std::time::Instant::now() >= deadline)
    };
    let initial_candidate = target.sni.as_deref().unwrap_or_default();
    let base_host_header = target.host_header.as_deref().or(target.sni.as_deref()).unwrap_or(&target.provider);
    let run_attempt = |target: &TcpTarget, sni: &str| {
        run_fat_header_attempt_with_key_log(target, transport, sni, base_host_header, key_log, Some(cancel))
    };

    let mut baseline = run_attempt(target, initial_candidate);
    let mut attempts = vec![attempt_detail(initial_candidate, "initial", target.port, &baseline)];
    let mut probe_retry_count = 0;
    if baseline.status == FatHeaderStatus::ConnectFailed && baseline.failure_stage == Some("tcp_connect") && !stopped()
    {
        probe_retry_count = 1;
        baseline = run_attempt(target, initial_candidate);
        attempts.push(attempt_detail(initial_candidate, "retry", target.port, &baseline));
    }

    let mut winner = None;
    if !initial_candidate.is_empty() && comparable_sni_failure(&baseline) && !stopped() {
        for name in distinct_sni_candidates(target, initial_candidate, whitelist_sni) {
            if stopped() {
                break;
            }
            let alternate = run_attempt(target, &name);
            attempts.push(attempt_detail(&name, "alternate_sni", target.port, &alternate));
            if alternate.status == FatHeaderStatus::Success {
                winner = Some((name, alternate));
                break;
            }
        }
    }

    let (selected_sni, selected) = match &winner {
        Some((name, observation)) => (name.as_str(), observation),
        None => (initial_candidate, &baseline),
    };
    let alt_attempt =
        target.alt_port.filter(|_| baseline.failure_stage == Some("response_read") && !stopped()).map(|port| {
            let alt_target = TcpTarget { port, alt_port: None, ..target.clone() };
            let observation = run_attempt(&alt_target, initial_candidate);
            attempts.push(attempt_detail(initial_candidate, "alternate_port", port, &observation));
            (port, observation)
        });

    let tcp_block_method = classify_tcp_block_method(&selected.status);

    let mut details = vec![
        ProbeDetail { key: "provider".to_string(), value: target.provider.clone() },
        ProbeDetail { key: "attempts".to_string(), value: attempts.join("|") },
        ProbeDetail { key: "selectedSni".to_string(), value: sni_label(selected_sni).to_string() },
        ProbeDetail { key: "asn".to_string(), value: target.asn.clone().unwrap_or_else(|| "unknown".to_string()) },
        ProbeDetail { key: "bytesSent".to_string(), value: selected.bytes_sent.to_string() },
        ProbeDetail { key: "responsesSeen".to_string(), value: selected.responses_seen.to_string() },
        ProbeDetail { key: "lastError".to_string(), value: selected.error.as_deref().unwrap_or("none").to_string() },
        ProbeDetail { key: "probeRetryCount".to_string(), value: probe_retry_count.to_string() },
        ProbeDetail { key: "tcpBlockMethod".to_string(), value: tcp_block_method.to_string() },
        ProbeDetail { key: "tcpAttentionConfidence".to_string(), value: tcp_attention_confidence(&selected.status) },
        ProbeDetail {
            key: "tcpAttentionReason".to_string(),
            value: selected.failure_stage.unwrap_or(tcp_block_method).to_string(),
        },
        ProbeDetail {
            key: "transportOpenMs".to_string(),
            value: selected.transport_open_ms.map_or_else(String::new, |v| v.to_string()),
        },
        ProbeDetail {
            key: "rstTimingMs".to_string(),
            value: selected.rst_timing_ms.map_or_else(String::new, |v| v.to_string()),
        },
    ];
    details.extend([
        ProbeDetail { key: "byteAccounting".to_string(), value: "application_write".to_string() },
        ProbeDetail { key: "requestsPlanned".to_string(), value: selected.requests_planned.to_string() },
        ProbeDetail { key: "requestsWritten".to_string(), value: selected.requests_written.to_string() },
        ProbeDetail { key: "bytesAtLastResponse".to_string(), value: selected.bytes_at_last_response.to_string() },
        ProbeDetail { key: "thresholdCovered".to_string(), value: selected.threshold_covered.to_string() },
    ]);
    if let Some(status) = selected.http_status {
        details.push(ProbeDetail { key: "httpStatusCode".to_string(), value: status.to_string() });
    }
    details.push(ProbeDetail { key: "port".to_string(), value: target.port.to_string() });
    if let Some(stage) = selected.failure_stage {
        details.push(ProbeDetail { key: "failureStage".to_string(), value: stage.to_string() });
    }
    if let Some((alt_port, alt_obs)) = alt_attempt {
        details.push(ProbeDetail { key: "altPort".to_string(), value: alt_port.to_string() });
        details.push(ProbeDetail {
            key: "altPortStatus".to_string(),
            value: fat_status_label(&alt_obs.status).to_string(),
        });
        details.push(ProbeDetail { key: "altPortBytesSent".to_string(), value: alt_obs.bytes_sent.to_string() });
        details
            .push(ProbeDetail { key: "altPortResponsesSeen".to_string(), value: alt_obs.responses_seen.to_string() });
    }

    ProbeResult {
        probe_type: "tcp_fat_header".to_string(),
        target: format!("{}:{} ({})", target.ip, target.port, target.provider),
        outcome: tcp_probe_outcome(&selected.status, winner.is_some()).to_string(),
        details,
    }
}

fn comparable_sni_failure(observation: &FatHeaderObservation) -> bool {
    matches!(observation.failure_stage, Some("tls_handshake" | "request_write" | "response_read" | "http_response"))
}

fn tcp_probe_outcome(status: &FatHeaderStatus, alternate_succeeded: bool) -> &'static str {
    if alternate_succeeded { "whitelist_sni_ok" } else { classify_fat_header_outcome(status) }
}

fn distinct_sni_candidates(target: &TcpTarget, initial: &str, candidates: &[String]) -> Vec<String> {
    let Ok(ip) = target.ip.parse::<IpAddr>() else {
        return Vec::new();
    };
    let address = TargetAddress::Ip(ip);
    let initial = initial.trim_end_matches('.');
    let mut names = Vec::new();
    for candidate in candidates {
        if candidate.is_empty() {
            continue;
        }
        let Ok(ServerName::DnsName(name)) = make_server_name(candidate, &address) else {
            continue;
        };
        let name = name.as_ref().to_ascii_lowercase();
        if !name.eq_ignore_ascii_case(initial) && !names.contains(&name) {
            names.push(name);
            if names.len() == FAT_HEADER_MAX_ALTERNATES {
                break;
            }
        }
    }
    names
}

fn sni_label(sni: &str) -> &str {
    if sni.is_empty() { "<empty>" } else { sni }
}

fn attempt_detail(sni: &str, attempt: &str, port: u16, observation: &FatHeaderObservation) -> String {
    let reason = match observation.failure_stage {
        Some("administrative_stop" | "coverage") => observation.error.as_deref().unwrap_or("unknown"),
        _ => "none",
    };
    format!(
        "{}:{}:{attempt}:port={port},stage={},reason={reason},bytes={},bytesAtLastResponse={},responses={},written={},planned={},httpStatus={}",
        sni_label(sni),
        fat_status_label(&observation.status),
        observation.failure_stage.unwrap_or("none"),
        observation.bytes_sent,
        observation.bytes_at_last_response,
        observation.responses_seen,
        observation.requests_written,
        observation.requests_planned,
        observation.http_status.map_or_else(String::new, |status| status.to_string()),
    )
}

fn tcp_attention_confidence(status: &FatHeaderStatus) -> String {
    match status {
        FatHeaderStatus::Reset | FatHeaderStatus::Timeout | FatHeaderStatus::HandshakeFailed => "low",
        _ => "none",
    }
    .to_string()
}

#[cfg(test)]
mod tests;
