use crate::types::{ObservationKind, ProbeObservation, ProbeResult, TcpObservationFact};

use super::common::{base_observation, detail_value, tcp_status};

pub(crate) fn build_tcp_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Tcp);
    observation.tcp = Some(TcpObservationFact {
        provider: detail_value(result, "provider").unwrap_or(result.target.as_str()).to_string(),
        status: tcp_status(&result.outcome),
        selected_sni: detail_value(result, "selectedSni").map(str::to_string),
        bytes_sent: detail_value(result, "bytesSent").and_then(|value| value.parse::<usize>().ok()),
        responses_seen: detail_value(result, "responsesSeen").and_then(|value| value.parse::<usize>().ok()),
        freeze_threshold_bytes: detail_value(result, "freezeThresholdBytes")
            .and_then(|value| value.parse::<usize>().ok()),
        port: detail_value(result, "port").and_then(|v| v.parse::<u16>().ok()),
        alt_port: detail_value(result, "altPort").and_then(|v| v.parse::<u16>().ok()),
        alt_port_status: detail_value(result, "altPortStatus").map(str::to_string),
        tcp_block_method: detail_value(result, "tcpBlockMethod").filter(|v| *v != "none").map(str::to_string),
        observed_window_size: detail_value(result, "observedWindowSize").and_then(|v| v.parse::<u32>().ok()),
        rst_timing_ms: detail_value(result, "rstTimingMs").and_then(|v| v.parse::<u64>().ok()),
        syn_ack_latency_ms: detail_value(result, "synAckLatencyMs").and_then(|v| v.parse::<u64>().ok()),
        rst_origin: detail_value(result, "rstOrigin").filter(|v| *v != "unknown").map(str::to_string),
    });
    observation.evidence.push(detail_value(result, "attempts").unwrap_or_default().to_string());
    observation
}
