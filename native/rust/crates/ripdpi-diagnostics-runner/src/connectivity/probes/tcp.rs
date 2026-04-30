use crate::fat_header::*;
use crate::transport::TransportConfig;
use crate::types::{ProbeDetail, ProbeResult, TcpTarget};

pub fn run_tcp_probe(target: &TcpTarget, whitelist_sni: &[String], transport: &TransportConfig) -> ProbeResult {
    let base_host_header =
        target.host_header.clone().or_else(|| target.sni.clone()).unwrap_or_else(|| target.provider.clone());
    let mut attempted_candidates = Vec::new();

    let initial_candidate = target.sni.clone().unwrap_or_default();
    let initial = run_fat_header_attempt(target, transport, &initial_candidate, &base_host_header);
    attempted_candidates.push(format!(
        "{}:{}",
        if initial_candidate.is_empty() { "<empty>" } else { initial_candidate.as_str() },
        fat_status_label(&initial.status)
    ));

    // Single retry on connect failure to distinguish transient from consistent unreachability.
    let mut probe_retry_count: usize = 0;
    let effective_initial = if initial.status == FatHeaderStatus::ConnectFailed {
        probe_retry_count = 1;
        let retry = run_fat_header_attempt(target, transport, &initial_candidate, &base_host_header);
        attempted_candidates.push(format!(
            "{}:retry:{}",
            if initial_candidate.is_empty() { "<empty>" } else { initial_candidate.as_str() },
            fat_status_label(&retry.status)
        ));
        retry
    } else {
        initial.clone()
    };

    let mut outcome = classify_fat_header_outcome(&effective_initial.status).to_string();
    let mut winning_sni = None;
    let mut final_observation = effective_initial.clone();

    let tried_whitelist_candidates =
        effective_initial.status != FatHeaderStatus::Success && target.sni.is_some() && !whitelist_sni.is_empty();
    if tried_whitelist_candidates {
        for candidate in whitelist_sni {
            let candidate_result = run_fat_header_attempt(target, transport, candidate, candidate);
            attempted_candidates.push(format!("{}:{}", candidate, fat_status_label(&candidate_result.status)));
            final_observation = candidate_result.clone();
            if candidate_result.status == FatHeaderStatus::Success || candidate_result.responses_seen > 0 {
                outcome = "whitelist_sni_ok".to_string();
                winning_sni = Some(candidate.clone());
                break;
            }
        }
        if winning_sni.is_none() {
            outcome = "whitelist_sni_failed".to_string();
        }
    }

    let tcp_block_method = classify_tcp_block_method(&final_observation.status);
    let rst_origin = classify_rst_origin(final_observation.syn_ack_latency_ms, final_observation.rst_timing_ms);
    // For window-cap outcomes, use bytes_sent at cutoff as the observed window size
    // since actual TCP window size is not available from userspace sockets.
    let observed_window_size = match final_observation.status {
        FatHeaderStatus::ThresholdCutoff | FatHeaderStatus::FreezeAfterThreshold => {
            Some(final_observation.bytes_sent as u32)
        }
        _ => final_observation.observed_window_size,
    };

    let mut details = vec![
        ProbeDetail { key: "provider".to_string(), value: target.provider.clone() },
        ProbeDetail { key: "attempts".to_string(), value: attempted_candidates.join("|") },
        ProbeDetail {
            key: "selectedSni".to_string(),
            value: winning_sni.unwrap_or_else(|| {
                if initial_candidate.is_empty() {
                    "<empty>".to_string()
                } else {
                    initial_candidate
                }
            }),
        },
        ProbeDetail { key: "asn".to_string(), value: target.asn.clone().unwrap_or_else(|| "unknown".to_string()) },
        ProbeDetail { key: "bytesSent".to_string(), value: final_observation.bytes_sent.to_string() },
        ProbeDetail { key: "responsesSeen".to_string(), value: final_observation.responses_seen.to_string() },
        ProbeDetail {
            key: "lastError".to_string(),
            value: final_observation.error.unwrap_or_else(|| "none".to_string()),
        },
        ProbeDetail { key: "probeRetryCount".to_string(), value: probe_retry_count.to_string() },
        ProbeDetail { key: "tcpBlockMethod".to_string(), value: tcp_block_method.to_string() },
        ProbeDetail {
            key: "synAckLatencyMs".to_string(),
            value: final_observation.syn_ack_latency_ms.map_or_else(String::new, |v| v.to_string()),
        },
        ProbeDetail {
            key: "rstTimingMs".to_string(),
            value: final_observation.rst_timing_ms.map_or_else(String::new, |v| v.to_string()),
        },
        ProbeDetail { key: "rstOrigin".to_string(), value: rst_origin.to_string() },
        ProbeDetail {
            key: "observedWindowSize".to_string(),
            value: observed_window_size.map_or_else(String::new, |v| v.to_string()),
        },
    ];
    details.push(ProbeDetail { key: "port".to_string(), value: target.port.to_string() });
    if final_observation.status == FatHeaderStatus::FreezeAfterThreshold {
        details.push(ProbeDetail {
            key: "freezeThresholdBytes".to_string(),
            value: final_observation.bytes_sent.to_string(),
        });
    }
    // When the main port fails and an alternative port is configured, probe the
    // alt port to detect port-specific policing (e.g. middlebox targeting port 443).
    if let Some(alt_port) = target.alt_port {
        if matches!(
            final_observation.status,
            FatHeaderStatus::ThresholdCutoff
                | FatHeaderStatus::FreezeAfterThreshold
                | FatHeaderStatus::Reset
                | FatHeaderStatus::Timeout
        ) {
            let alt_target = TcpTarget { port: alt_port, alt_port: None, ..target.clone() };
            let alt_host = target.host_header.as_deref().or(target.sni.as_deref()).unwrap_or("localhost");
            let alt_sni = target.sni.as_deref().unwrap_or("");
            let alt_obs = run_fat_header_attempt(&alt_target, transport, alt_sni, alt_host);
            details.push(ProbeDetail { key: "altPort".to_string(), value: alt_port.to_string() });
            details.push(ProbeDetail {
                key: "altPortStatus".to_string(),
                value: fat_status_label(&alt_obs.status).to_string(),
            });
            details.push(ProbeDetail { key: "altPortBytesSent".to_string(), value: alt_obs.bytes_sent.to_string() });
            details.push(ProbeDetail {
                key: "altPortResponsesSeen".to_string(),
                value: alt_obs.responses_seen.to_string(),
            });
        }
    }

    ProbeResult {
        probe_type: "tcp_fat_header".to_string(),
        target: format!("{}:{} ({})", target.ip, target.port, target.provider),
        outcome,
        details,
    }
}
