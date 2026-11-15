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
    run_with_checkpoints(target, transport, key_log, should_abort, None)
}

pub fn run_telegram_probe_with_abort_and_checkpoint(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
    on_checkpoint: &mut dyn FnMut(ProbeResult),
) -> ProbeResult {
    run_with_checkpoints(target, transport, key_log, should_abort, Some(on_checkpoint))
}

fn run_with_checkpoints(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
    mut on_checkpoint: Option<&mut dyn FnMut(ProbeResult)>,
) -> ProbeResult {
    let mut dl = unattempted_transfer();
    let mut ul = unattempted_transfer();
    let mut dc = TelegramDcResult { planned: target.dc_endpoints.len(), ..TelegramDcResult::default() };
    let ws_pending = unattempted_ws();
    if let Some(reason) = should_abort() {
        return build_telegram_result(&dl, &ul, &dc, &ws_pending, transport, Some(reason));
    }
    if let Err(reason) = super::validate_telegram_target(target) {
        return ProbeResult {
            probe_type: "telegram_availability".into(),
            target: "telegram.org".into(),
            outcome: "unknown".into(),
            details: vec![detail("targetValidationError", reason)],
        };
    }

    let mut publish = |dl: &TelegramTransferResult, ul: &TelegramTransferResult, dc: &TelegramDcResult| {
        if let Some(callback) = on_checkpoint.as_mut() {
            callback(build_telegram_result(dl, ul, dc, &ws_pending, transport, should_abort()));
        }
    };

    if should_abort().is_none() {
        dl = telegram_download_probe_with_abort(target, transport, key_log, should_abort);
        publish(&dl, &ul, &dc);
    }
    if should_abort().is_none() {
        ul = telegram_upload_probe_with_abort(target, transport, key_log, should_abort);
        publish(&dl, &ul, &dc);
    }
    if should_abort().is_none() {
        dc = telegram_dc_probe_with_abort(target, transport, should_abort);
        if dc.total > 0 {
            publish(&dl, &ul, &dc);
        }
    }
    let ws = if should_abort().is_some() {
        ws_pending
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

#[cfg(test)]
mod partial_tests;
