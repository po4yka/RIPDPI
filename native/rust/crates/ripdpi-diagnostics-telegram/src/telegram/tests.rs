use std::cell::Cell;

use crate::transport::{TransportConfig, direct_transport};

use super::scoring::{classify_telegram_verdict, compute_telegram_quality_score};
use super::ws_tunnel::{telegram_ws_tunnel_probe_with, telegram_ws_tunnel_probe_with_abort};

#[test]
fn verdict_partial_when_only_websocket_works() {
    assert_eq!(classify_telegram_verdict("blocked", "blocked", 0, 3, "ok"), "partial");
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
fn verdict_cannot_be_healthy_without_dc_evidence() {
    assert_eq!(classify_telegram_verdict("ok", "ok", 0, 3, "ok"), "partial");
    assert_eq!(classify_telegram_verdict("ok", "ok", 0, 0, "ok"), "partial");
    assert_eq!(classify_telegram_verdict("stalled", "ok", 0, 3, "ok"), "partial");
    assert_eq!(classify_telegram_verdict("not_run", "not_run", 0, 0, "not_run"), "unknown");
}

#[test]
fn verdict_ok_when_all_good() {
    assert_eq!(classify_telegram_verdict("ok", "ok", 3, 3, "ok"), "ok");
}

#[test]
fn verdict_partial_when_only_download_is_blocked() {
    assert_eq!(classify_telegram_verdict("blocked", "ok", 3, 3, "ok"), "partial");
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
    let result = telegram_ws_tunnel_probe_with(&direct_transport(), |_target| panic!("provider selection panic"));

    assert_eq!(result.status, "unreachable");
    assert!(result.open_ms.is_some_and(|value| value <= 1_000));
    assert_eq!(result.error.as_deref(), Some("panic during Telegram WS tunnel probe: provider selection panic"),);
}

#[test]
fn telegram_quality_score_covers_only_complete_measurements() {
    for (download, upload, reachable, attempted, planned, ws, expected) in [
        ("ok", "ok", 3, 3, 3, "ok", Some(0)),
        ("ok", "ok", 0, 3, 3, "ok", Some(15)),
        ("error", "error", 0, 3, 3, "unreachable", Some(100)),
        ("slow", "ok", 3, 3, 3, "ok", Some(22)),
        ("ok", "ok", 0, 0, 0, "ok", None),
        ("ok", "ok", 1, 1, 3, "ok", None),
        ("ok", "not_run", 3, 3, 3, "ok", None),
        ("ok", "ok", 3, 3, 3, "deadline_exceeded", None),
    ] {
        assert_eq!(compute_telegram_quality_score(download, upload, reachable, attempted, planned, ws), expected);
    }
}

#[test]
fn telegram_ws_tunnel_probe_leaves_resolution_to_the_scan_transport() {
    let socks5 = TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: 1080, credentials: None };
    let captured = std::cell::RefCell::new(None);

    let result = telegram_ws_tunnel_probe_with(&socks5, |target| {
        captured.replace(Some(target));
        Ok(())
    });

    let target = captured.into_inner().expect("probe called");
    assert_eq!(target.resolved_addr, None);
    assert!(matches!(target.transport, TransportConfig::Socks5 { port: 1080, .. }));
    assert_eq!(result.status, "ok");
    assert!(result.error.is_none());
}

#[test]
fn telegram_ws_probe_is_not_run_when_cancelled_before_start() {
    let probe_called = Cell::new(false);

    let result = telegram_ws_tunnel_probe_with_abort(
        |_target| {
            probe_called.set(true);
            Ok(())
        },
        &|| Some("cancelled"),
    );

    assert_eq!(result.status, "not_run");
    assert_eq!(result.error.as_deref(), Some("probe_aborted:cancelled"));
    assert!(!probe_called.get());
}

#[test]
fn telegram_ws_probe_discards_success_after_cancellation() {
    let cancelled = Cell::new(false);
    let result = telegram_ws_tunnel_probe_with_abort(
        |_| {
            cancelled.set(true);
            Ok(())
        },
        &|| cancelled.get().then_some("cancelled"),
    );

    assert_eq!(result.status, "cancelled");
    assert_eq!(result.error.as_deref(), Some("probe_aborted:cancelled"));
}

#[test]
fn telegram_ws_deadline_during_upgrade_is_not_a_network_failure() {
    use ripdpi_diagnostics_transport::ws_tls::WsProbeFailure;

    let result = telegram_ws_tunnel_probe_with(&direct_transport(), |_| {
        Err(std::io::Error::new(std::io::ErrorKind::TimedOut, WsProbeFailure::DeadlineExceeded))
    });

    assert_eq!(result.status, "deadline_exceeded");
    assert_eq!(result.abort_reason, Some("deadline_exceeded"));
}

#[test]
fn telegram_report_does_not_score_a_deadline_before_wss_starts() {
    let target = crate::types::TelegramTarget {
        media_url: String::new(),
        upload_ip: String::new(),
        upload_port: 443,
        dc_endpoints: Vec::new(),
        stall_timeout_ms: 10,
        total_timeout_ms: 10,
        upload_size_bytes: 1,
    };
    let evidence =
        super::run_telegram_probe_with_abort(&target, &direct_transport(), None, &|| Some("deadline_exceeded"));

    assert_eq!(evidence.outcome, "deadline_exceeded");
    assert!(evidence.details.iter().any(|item| item.key == "wsTunnelStatus" && item.value == "not_run"));
    assert!(evidence.details.iter().all(|item| !matches!(item.key.as_str(), "qualityScore" | "wsTunnelOpenMs")));
}

#[test]
fn telegram_records_missing_binary_as_profile_negotiation_after_http_101() {
    use ripdpi_diagnostics_transport::ws_tls::WsProbeFailure;

    let result = telegram_ws_tunnel_probe_with(&direct_transport(), |_| {
        Err(std::io::Error::new(std::io::ErrorKind::InvalidData, WsProbeFailure::SubprotocolNotNegotiated))
    });

    assert_eq!(result.status, "subprotocol_not_negotiated");
    assert_eq!(result.http_status, Some(101));
    assert_eq!(classify_telegram_verdict("ok", "ok", 3, 3, &result.status), "partial");
}
