use super::dc::TelegramDcResult;
use super::transfer::TelegramTransferResult;
use super::ws_tunnel::TelegramWsProbeResult;

pub(crate) fn classify_telegram_verdict(
    dl_status: &str,
    ul_status: &str,
    dc_reachable: usize,
    dc_total: usize,
    ws_status: &str,
) -> &'static str {
    if dl_status == "cancelled" || ul_status == "cancelled" || ws_status == "cancelled" {
        "cancelled"
    } else if dl_status == "deadline_exceeded" || ul_status == "deadline_exceeded" || ws_status == "deadline_exceeded" {
        "deadline_exceeded"
    } else if (dl_status == "blocked" || ul_status == "blocked") && dc_reachable == 0 {
        "blocked"
    } else if matches!(dl_status, "stalled" | "slow") || matches!(ul_status, "stalled" | "slow") {
        "slow"
    } else if dc_reachable < dc_total && dc_reachable > 0 {
        "partial"
    } else if dl_status == "ok" && ul_status == "ok" && ws_status == "ok" {
        "ok"
    } else {
        "error"
    }
}

/// Compute a composite quality score from Telegram probe sub-results.
///
/// Lower score = better quality. Returns `u64::MAX` if all probes failed.
///
/// Weights: download (3x), upload (2x), DC-reachability (1x per DC), WS tunnel (1x).
/// Penalizes failures with +2000ms per failed component (adapted from tglock's
/// `benchmark_telegram` scoring algorithm).
pub(crate) fn compute_telegram_quality_score(
    dl: &TelegramTransferResult,
    ul: &TelegramTransferResult,
    dc: &TelegramDcResult,
    ws: &TelegramWsProbeResult,
) -> u64 {
    const FAILURE_PENALTY_MS: u64 = 2000;
    const PARTIAL_PENALTY_MS: u64 = 1000;
    const DL_WEIGHT: u64 = 3;
    const UL_WEIGHT: u64 = 2;
    const WS_WEIGHT: u64 = 1;

    let transfer_score = |t: &TelegramTransferResult| -> u64 {
        match t.status.as_str() {
            "ok" => t.duration_ms,
            "slow" | "stalled" => t.duration_ms.saturating_add(PARTIAL_PENALTY_MS),
            "cancelled" | "deadline_exceeded" => PARTIAL_PENALTY_MS,
            _ => FAILURE_PENALTY_MS,
        }
    };

    let dl_score = transfer_score(dl) * DL_WEIGHT;
    let ul_score = transfer_score(ul) * UL_WEIGHT;

    let dc_total = dc.total.max(1) as u64;
    let dc_unreachable = dc.total.saturating_sub(dc.reachable) as u64;
    let dc_score = dc_unreachable * FAILURE_PENALTY_MS;
    let dc_weight = dc_total;

    let ws_score = if ws.status == "ok" { ws.rtt_ms } else { FAILURE_PENALTY_MS } * WS_WEIGHT;

    let total_weighted = dl_score + ul_score + dc_score + ws_score;
    let total_weight = DL_WEIGHT + UL_WEIGHT + dc_weight + WS_WEIGHT;

    if dl.status == "blocked" && ul.status == "blocked" && dc.reachable == 0 && ws.status != "ok" {
        return u64::MAX;
    }

    total_weighted / total_weight
}
