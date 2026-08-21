use std::cell::Cell;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use super::dc::TelegramDcResult;
use super::scoring::{classify_telegram_verdict, compute_telegram_quality_score};
use super::transfer::TelegramTransferResult;
use super::ws_tunnel::{TelegramWsProbeResult, telegram_ws_tunnel_probe_with, telegram_ws_tunnel_probe_with_abort};

#[test]
fn verdict_blocked_when_both_transfers_blocked_and_no_dc() {
    assert_eq!(classify_telegram_verdict("blocked", "blocked", 0, 3, "ok"), "blocked");
}

#[test]
fn verdict_slow_when_download_stalled() {
    assert_eq!(classify_telegram_verdict("stalled", "ok", 3, 3, "ok"), "slow");
}

#[test]
fn verdict_partial_when_some_dc_unreachable() {
    assert_eq!(classify_telegram_verdict("ok", "ok", 2, 3, "ok"), "partial");
}

#[test]
fn verdict_ok_when_all_good() {
    assert_eq!(classify_telegram_verdict("ok", "ok", 3, 3, "ok"), "ok");
}

#[test]
fn verdict_error_when_unrecognized_state() {
    assert_eq!(classify_telegram_verdict("blocked", "ok", 3, 3, "ok"), "error");
}

#[test]
fn verdict_cancelled_when_any_subprobe_is_cancelled() {
    assert_eq!(classify_telegram_verdict("cancelled", "ok", 3, 3, "ok"), "cancelled");
}

#[test]
fn verdict_deadline_exceeded_when_any_subprobe_exceeds_deadline() {
    assert_eq!(classify_telegram_verdict("deadline_exceeded", "ok", 3, 3, "ok"), "deadline_exceeded");
}

#[test]
fn telegram_ws_probe_recovers_from_probe_panic() {
    let result = telegram_ws_tunnel_probe_with(
        || Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443)),
        |_resolved_addr| panic!("provider selection panic"),
    );

    assert_eq!(result.status, "unreachable");
    assert!(result.rtt_ms <= 1_000);
    assert_eq!(result.error.as_deref(), Some("panic during Telegram WS tunnel probe: provider selection panic"),);
}

fn make_transfer(status: &str, duration_ms: u64) -> TelegramTransferResult {
    TelegramTransferResult {
        status: status.to_string(),
        avg_bps: 0,
        peak_bps: 0,
        bytes_total: 0,
        duration_ms,
        error: None,
    }
}

fn make_dc(reachable: usize, total: usize) -> TelegramDcResult {
    TelegramDcResult { reachable, total, results: vec![] }
}

fn make_ws(status: &str, rtt_ms: u64) -> TelegramWsProbeResult {
    TelegramWsProbeResult { status: status.to_string(), rtt_ms, error: None }
}

#[test]
fn quality_score_low_for_perfect_results() {
    let dl = make_transfer("ok", 100);
    let ul = make_transfer("ok", 80);
    let dc = make_dc(3, 3);
    let ws = make_ws("ok", 50);

    let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
    // (100*3 + 80*2 + 0 + 50*1) / (3+2+3+1) = (300+160+0+50)/9 = 510/9 = 56
    assert_eq!(score, 56);
}

#[test]
fn quality_score_penalizes_blocked_download_heavily() {
    let dl = make_transfer("blocked", 0);
    let ul = make_transfer("ok", 80);
    let dc = make_dc(3, 3);
    let ws = make_ws("ok", 50);

    let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
    // (2000*3 + 80*2 + 0 + 50*1) / 9 = (6000+160+0+50)/9 = 6210/9 = 690
    assert_eq!(score, 690);
}

#[test]
fn quality_score_penalizes_unreachable_dcs() {
    let dl = make_transfer("ok", 100);
    let ul = make_transfer("ok", 80);
    let dc = make_dc(1, 3); // 2 unreachable
    let ws = make_ws("ok", 50);

    let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
    // (100*3 + 80*2 + 2*2000 + 50*1) / 9 = (300+160+4000+50)/9 = 4510/9 = 501
    assert_eq!(score, 501);
}

#[test]
fn quality_score_is_max_when_all_probes_fail() {
    let dl = make_transfer("blocked", 0);
    let ul = make_transfer("blocked", 0);
    let dc = make_dc(0, 3);
    let ws = make_ws("unreachable", 0);

    let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
    assert_eq!(score, u64::MAX);
}

#[test]
fn quality_score_reflects_latency_differences() {
    let fast_dl = make_transfer("ok", 50);
    let slow_dl = make_transfer("ok", 500);
    let ul = make_transfer("ok", 80);
    let dc = make_dc(3, 3);
    let ws = make_ws("ok", 50);

    let fast_score = compute_telegram_quality_score(&fast_dl, &ul, &dc, &ws);
    let slow_score = compute_telegram_quality_score(&slow_dl, &ul, &dc, &ws);
    assert!(fast_score < slow_score, "faster download should produce lower (better) score");
}

#[test]
fn quality_score_partial_penalty_for_slow_transfer() {
    let dl = make_transfer("slow", 200);
    let ul = make_transfer("ok", 80);
    let dc = make_dc(3, 3);
    let ws = make_ws("ok", 50);

    let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
    // (1200*3 + 80*2 + 0 + 50*1) / 9 = (3600+160+0+50)/9 = 3810/9 = 423
    assert_eq!(score, 423);
}

#[test]
fn telegram_ws_tunnel_probe_passes_resolved_addr_to_probe() {
    let expected = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 443);
    let captured_addr = Cell::new(None);

    let result = telegram_ws_tunnel_probe_with(
        || Ok(expected),
        |resolved_addr| {
            captured_addr.set(resolved_addr);
            Ok(())
        },
    );

    assert_eq!(captured_addr.get(), Some(expected));
    assert_eq!(result.status, "ok");
    assert!(result.error.is_none());
}

#[test]
fn telegram_ws_tunnel_probe_falls_back_to_unresolved_probe_when_lookup_fails() {
    let captured_addr = Cell::new(Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443)));

    let result = telegram_ws_tunnel_probe_with(
        || Err(io::Error::new(io::ErrorKind::TimedOut, "dns timed out")),
        |resolved_addr| {
            captured_addr.set(resolved_addr);
            Ok(())
        },
    );

    assert_eq!(captured_addr.get(), None);
    assert_eq!(result.status, "ok");
    assert!(result.error.is_none());
}

#[test]
fn telegram_ws_probe_stops_after_resolution_when_cancelled() {
    let probe_called = Cell::new(false);

    let result = telegram_ws_tunnel_probe_with_abort(
        || Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443)),
        |_target| {
            probe_called.set(true);
            Ok(())
        },
        &|| Some("cancelled"),
    );

    assert_eq!(result.status, "cancelled");
    assert_eq!(result.error.as_deref(), Some("probe_aborted:cancelled"));
    assert!(!probe_called.get());
}
