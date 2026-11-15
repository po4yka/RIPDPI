use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;
use crate::types::{ProbeDetail, ProbeResult, TelegramTarget};

use super::dc::{TelegramDcResult, deadline_abort_reason, telegram_dc_probe_with_abort};
use super::scoring::{classify_telegram_verdict, compute_telegram_quality_score};
use super::transfer::{TelegramTransferResult, telegram_download_probe_with_abort, telegram_upload_probe_with_abort};
use super::ws_tunnel::{
    TelegramWsProbeResult, telegram_transport_path, telegram_ws_endpoint, telegram_ws_tunnel_probe_with_abort_callback,
    ws_open_failure_stage,
};

pub fn run_telegram_probe(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeResult {
    run_telegram_probe_with_abort(target, transport, key_log, &deadline_abort_reason)
}

pub fn run_telegram_probe_with_abort(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) -> ProbeResult {
    let dl = if should_abort().is_some() {
        unattempted_transfer()
    } else {
        telegram_download_probe_with_abort(target, transport, key_log, should_abort)
    };
    let ul = if should_abort().is_some() {
        unattempted_transfer()
    } else {
        telegram_upload_probe_with_abort(target, transport, key_log, should_abort)
    };
    let dc = telegram_dc_probe_with_abort(target, transport, should_abort);
    let ws = if should_abort().is_some() {
        unattempted_ws()
    } else {
        telegram_ws_tunnel_probe_with_abort_callback(transport, key_log, should_abort)
    };
    build_telegram_result(&dl, &ul, &dc, &ws, transport, should_abort())
}

fn unattempted_transfer() -> TelegramTransferResult {
    TelegramTransferResult {
        status: "not_run".into(),
        avg_bps: 0,
        peak_bps: 0,
        bytes_total: 0,
        duration_ms: 0,
        error: None,
        measures_throughput: false,
    }
}

fn unattempted_ws() -> TelegramWsProbeResult {
    TelegramWsProbeResult {
        status: "not_run".into(),
        abort_reason: None,
        open_ms: None,
        error: None,
        http_status: None,
        peer: None,
    }
}

fn build_telegram_result(
    dl: &TelegramTransferResult,
    ul: &TelegramTransferResult,
    dc: &TelegramDcResult,
    ws: &TelegramWsProbeResult,
    transport: &TransportConfig,
    abort_reason: Option<&'static str>,
) -> ProbeResult {
    let verdict = abort_reason
        .or(dc.abort_reason)
        .or(ws.abort_reason)
        .unwrap_or_else(|| classify_telegram_verdict(&dl.status, &ul.status, dc.reachable, dc.planned, &ws.status));
    let quality_score = if matches!(verdict, "cancelled" | "deadline_exceeded") {
        None
    } else {
        compute_telegram_quality_score(&dl.status, &ul.status, dc.reachable, dc.total, dc.planned, &ws.status)
    };

    let mut details = vec![detail("verdict", verdict)];
    if let Some(score) = quality_score {
        details.extend([
            detail("qualityScore", score.to_string()),
            detail("qualityScoreBasis", "availability_deficit_pct"),
        ]);
    }
    if let Some(open_ms) = ws.open_ms {
        details.push(detail("wsTunnelOpenMs", open_ms.to_string()));
    }
    if let Some(status) = ws.http_status {
        details.push(detail("wsTunnelHttpStatus", status.to_string()));
    }
    if let Some(stage) = ws_open_failure_stage(&ws.status) {
        details.push(detail("wsTunnelFailureStage", stage));
    }
    if let Some(peer) = ws.peer {
        details.push(detail("wsTunnelPeer", peer.to_string()));
    }
    details.extend([
        detail("wsTunnelEndpoint", telegram_ws_endpoint()),
        detail("wsTunnelPath", telegram_transport_path(transport)),
        detail("wsTunnelStatus", &ws.status),
        detail("dcReachable", dc.reachable.to_string()),
        detail("dcTotal", dc.total.to_string()),
        detail("dcPlanned", dc.planned.to_string()),
        detail("dcResults", dc.results.join("|")),
    ]);
    if let Some(error) = &ws.error {
        details.push(detail("wsTunnelError", error));
    }
    if let Some(reason) = dc.abort_reason {
        details.push(detail("dcAbortReason", reason));
    }
    append_transfer_details(&mut details, "download", dl);
    append_transfer_details(&mut details, "upload", ul);
    ProbeResult {
        probe_type: "telegram_availability".into(),
        target: "telegram.org".into(),
        outcome: verdict.into(),
        details,
    }
}

fn append_transfer_details(details: &mut Vec<ProbeDetail>, prefix: &str, transfer: &TelegramTransferResult) {
    details.push(detail(format!("{prefix}Status"), &transfer.status));
    if transfer.status != "not_run" {
        details.extend([
            detail(format!("{prefix}Bytes"), transfer.bytes_total.to_string()),
            detail(format!("{prefix}DurationMs"), transfer.duration_ms.to_string()),
        ]);
    }
    if transfer.measures_throughput {
        details.extend([
            detail(format!("{prefix}AvgBps"), transfer.avg_bps.to_string()),
            detail(format!("{prefix}PeakBps"), transfer.peak_bps.to_string()),
        ]);
    }
    if let Some(error) = &transfer.error {
        details.push(detail(format!("{prefix}Error"), error));
    }
}

fn detail(key: impl Into<String>, value: impl Into<String>) -> ProbeDetail {
    ProbeDetail { key: key.into(), value: value.into() }
}
