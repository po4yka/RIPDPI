use crate::types::{ObservationKind, ProbeObservation, ProbeResult, TelegramObservationFact};

use super::common::{base_observation, detail_list, detail_value, telegram_transfer_status, telegram_verdict};

const PROBE_TYPE: &str = "telegram_availability";

pub(crate) fn build_observation(result: &ProbeResult) -> Option<ProbeObservation> {
    (result.probe_type == PROBE_TYPE).then(|| build_telegram_observation(result))
}

pub(crate) fn build_telegram_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Telegram);
    observation.telegram = Some(TelegramObservationFact {
        verdict: telegram_verdict(detail_value(result, "verdict").unwrap_or(result.outcome.as_str())),
        quality_score: detail_value(result, "qualityScore")
            .and_then(|value| value.parse::<i32>().ok())
            .unwrap_or_default(),
        download_status: telegram_transfer_status(detail_value(result, "downloadStatus").unwrap_or("error")),
        upload_status: telegram_transfer_status(detail_value(result, "uploadStatus").unwrap_or("error")),
        dc_reachable: detail_value(result, "dcReachable").and_then(|value| value.parse::<usize>().ok()).unwrap_or(0),
        dc_total: detail_value(result, "dcTotal").and_then(|value| value.parse::<usize>().ok()).unwrap_or(0),
        download_avg_bps: detail_u64(result, "downloadAvgBps"),
        download_peak_bps: detail_u64(result, "downloadPeakBps"),
        upload_avg_bps: detail_u64(result, "uploadAvgBps"),
        upload_peak_bps: detail_u64(result, "uploadPeakBps"),
        dc_results: detail_list(result, "dcResults"),
        ws_tunnel_status: super::common::telegram_ws_tunnel_status(
            detail_value(result, "wsTunnelStatus").unwrap_or("unknown"),
        ),
        ws_tunnel_rtt_ms: detail_u64(result, "wsTunnelRttMs"),
        ws_tunnel_error: optional_detail(result, "wsTunnelError"),
    });
    observation
}

fn detail_u64(result: &ProbeResult, key: &str) -> u64 {
    detail_value(result, key).and_then(|value| value.parse::<u64>().ok()).unwrap_or(0)
}

fn optional_detail(result: &ProbeResult, key: &str) -> Option<String> {
    detail_value(result, key)
        .filter(|value| !value.is_empty() && *value != "none" && *value != "unknown")
        .map(str::to_string)
}
