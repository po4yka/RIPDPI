use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;
use crate::types::{ProbeDetail, ProbeResult, TelegramTarget};

use super::dc::{TelegramDcResult, deadline_abort_reason, telegram_dc_probe_with_abort};
use super::scoring::{classify_telegram_verdict, compute_telegram_quality_score};
use super::transfer::{TelegramTransferResult, telegram_download_probe_with_abort, telegram_upload_probe_with_abort};
use super::ws_tunnel::{TelegramWsProbeResult, telegram_ws_tunnel_probe_with_abort_callback};

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
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> ProbeResult {
    let dl = match should_abort() {
        Some(reason) => aborted_transfer(reason),
        None => telegram_download_probe_with_abort(target, transport, key_log, should_abort),
    };
    let ul = match should_abort() {
        Some(reason) => aborted_transfer(reason),
        None => telegram_upload_probe_with_abort(target, transport, key_log, should_abort),
    };
    let dc = telegram_dc_probe_with_abort(target, transport, should_abort);
    let ws = match should_abort() {
        Some(reason) => aborted_ws_probe(reason),
        None => telegram_ws_tunnel_probe_with_abort_callback(key_log, should_abort),
    };

    let verdict = classify_telegram_verdict(&dl.status, &ul.status, dc.reachable, dc.total, &ws.status);
    let quality_score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);

    ProbeResult {
        probe_type: "telegram_availability".to_string(),
        target: "telegram.org".to_string(),
        outcome: verdict.to_string(),
        details: build_telegram_details(verdict, quality_score, dl, ul, dc, ws),
    }
}

fn aborted_transfer(reason: &str) -> TelegramTransferResult {
    TelegramTransferResult::aborted(reason)
}

fn aborted_ws_probe(reason: &str) -> TelegramWsProbeResult {
    TelegramWsProbeResult::aborted(reason)
}

fn build_telegram_details(
    verdict: &str,
    quality_score: u64,
    dl: TelegramTransferResult,
    ul: TelegramTransferResult,
    dc: TelegramDcResult,
    ws: TelegramWsProbeResult,
) -> Vec<ProbeDetail> {
    vec![
        ProbeDetail { key: "verdict".to_string(), value: verdict.to_string() },
        ProbeDetail { key: "qualityScore".to_string(), value: quality_score.to_string() },
        ProbeDetail { key: "downloadStatus".to_string(), value: dl.status },
        ProbeDetail { key: "downloadAvgBps".to_string(), value: dl.avg_bps.to_string() },
        ProbeDetail { key: "downloadPeakBps".to_string(), value: dl.peak_bps.to_string() },
        ProbeDetail { key: "downloadBytes".to_string(), value: dl.bytes_total.to_string() },
        ProbeDetail { key: "downloadDurationMs".to_string(), value: dl.duration_ms.to_string() },
        ProbeDetail { key: "downloadError".to_string(), value: dl.error.unwrap_or_else(|| "none".to_string()) },
        ProbeDetail { key: "uploadStatus".to_string(), value: ul.status },
        ProbeDetail { key: "uploadAvgBps".to_string(), value: ul.avg_bps.to_string() },
        ProbeDetail { key: "uploadPeakBps".to_string(), value: ul.peak_bps.to_string() },
        ProbeDetail { key: "uploadBytes".to_string(), value: ul.bytes_total.to_string() },
        ProbeDetail { key: "uploadDurationMs".to_string(), value: ul.duration_ms.to_string() },
        ProbeDetail { key: "uploadError".to_string(), value: ul.error.unwrap_or_else(|| "none".to_string()) },
        ProbeDetail { key: "dcReachable".to_string(), value: dc.reachable.to_string() },
        ProbeDetail { key: "dcTotal".to_string(), value: dc.total.to_string() },
        ProbeDetail { key: "dcResults".to_string(), value: dc.results.join("|") },
        ProbeDetail { key: "wsTunnelStatus".to_string(), value: ws.status },
        ProbeDetail { key: "wsTunnelRttMs".to_string(), value: ws.rtt_ms.to_string() },
        ProbeDetail { key: "wsTunnelError".to_string(), value: ws.error.unwrap_or_else(|| "none".to_string()) },
    ]
}
