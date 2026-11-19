use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use ripdpi_diagnostics_contracts::{
    ProbeDetail, ProbeResult, TlsHopAttempt, TlsHopComparisonConfig, TlsHopComparisonEvidence, TlsHopComparisonLocal,
};
use ripdpi_diagnostics_tls::tls::hop_ladder::{
    AttemptOutcome, LadderConfig, LadderLimit, LadderReport, LadderSetupError, LadderStop, LadderVerdict, SniRole,
    SocketLadderProbe, UnsupportedReason, run_ladder,
};
use ripdpi_diagnostics_transport::transport::{
    TargetAddress, TransportConfig, first_routable_address, resolve_addresses_with_cancel,
};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

pub const TLS_HOP_EVIDENCE_KEY: &str = "tlsHopEvidence";
/// Local-history detail with SNIs and per-attempt timings; the ordinary export drops it.
pub const TLS_HOP_LOCAL_KEY: &str = "tlsHopLocal";

/// Run the control/test SNI TTL ladder against the one endpoint the user selected, on the direct path only.
pub fn run_tls_hop_comparison(
    config: &TlsHopComparisonConfig,
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
) -> ProbeResult {
    // Acquire observes the scan owner's published cancellation flag.
    let cancelled = || cancel.load(Ordering::Acquire);
    let (evidence, attempts) = if !raw_path || !matches!(transport, TransportConfig::Direct { .. }) {
        (stopped("UNSUPPORTED", "proxy_path"), Vec::new())
    } else if config.validate().is_err() {
        (stopped("INCONCLUSIVE", "invalid_config"), Vec::new())
    } else if cancelled() {
        (stopped("CANCELLED", "cancelled"), Vec::new())
    } else {
        let target = TargetAddress::Host(config.host.clone());
        match resolve_addresses_with_cancel(&target, TlsHopComparisonConfig::PORT, &cancelled) {
            Err(_) if cancelled() => (stopped("CANCELLED", "cancelled"), Vec::new()),
            Ok(addresses) if !addresses.is_empty() => {
                match SocketLadderProbe::new(
                    first_routable_address(&addresses).unwrap_or(addresses[0]),
                    &config.control_sni,
                    &config.test_sni,
                ) {
                    Ok(mut probe) => {
                        let mut ladder = LadderConfig::default();
                        if let Some(deadline) = active_scan_io_deadline() {
                            ladder.total_deadline =
                                ladder.total_deadline.min(deadline.saturating_duration_since(Instant::now()));
                        }
                        summarize(run_ladder(&mut probe, ladder, &cancelled))
                    }
                    Err(LadderSetupError::InvalidSni(_)) => (stopped("INCONCLUSIVE", "invalid_sni"), Vec::new()),
                    Err(LadderSetupError::Unsupported(reason)) => {
                        (stopped("UNSUPPORTED", unsupported_reason(reason)), Vec::new())
                    }
                }
            }
            _ => (stopped("INCONCLUSIVE", "dns_error"), Vec::new()),
        }
    };
    let local =
        TlsHopComparisonLocal { control_sni: config.control_sni.clone(), test_sni: config.test_sni.clone(), attempts };
    ProbeResult {
        probe_type: "tls_hop_comparison".into(),
        target: config.host.clone(),
        outcome: match evidence.status.as_str() {
            "DIVERGENCE" => "tls_hop_divergence",
            "UNSUPPORTED" => "tls_hop_unsupported",
            "CANCELLED" => "tls_hop_cancelled",
            _ => "tls_hop_inconclusive",
        }
        .into(),
        details: vec![
            ProbeDetail { key: TLS_HOP_EVIDENCE_KEY.into(), value: evidence.to_detail_json().unwrap_or_default() },
            ProbeDetail { key: TLS_HOP_LOCAL_KEY.into(), value: local.to_detail_json().unwrap_or_default() },
        ],
    }
}

fn stopped(status: &str, reason: &str) -> TlsHopComparisonEvidence {
    TlsHopComparisonEvidence { status: status.into(), reason: Some(reason.into()), ..Default::default() }
}

fn unsupported_reason(reason: UnsupportedReason) -> &'static str {
    match reason {
        UnsupportedReason::Protect => "protect",
        UnsupportedReason::TtlSetter => "ttl_setter",
        UnsupportedReason::Linger => "linger",
    }
}

fn summarize(report: LadderReport) -> (TlsHopComparisonEvidence, Vec<TlsHopAttempt>) {
    let count = |role| report.attempts.iter().filter(|attempt| attempt.role == role).count() as u32;
    let mut evidence = TlsHopComparisonEvidence {
        status: "INCONCLUSIVE".into(),
        path_length: report.path_length,
        limits: report.limits.iter().map(|limit| limit_token(*limit).into()).collect(),
        stop: Some(
            match report.stop {
                LadderStop::Completed => "COMPLETED",
                LadderStop::Cancelled => "CANCELLED",
                LadderStop::DeadlineExceeded => "DEADLINE_EXCEEDED",
                LadderStop::NetworkChanged => "NETWORK_CHANGED",
                LadderStop::Unsupported(_) => "UNSUPPORTED",
            }
            .into(),
        ),
        control_attempts: count(SniRole::Control),
        test_attempts: count(SniRole::Test),
        ..Default::default()
    };
    match report.verdict {
        LadderVerdict::Divergence { first_hop, last_hop } => {
            evidence.status = "DIVERGENCE".into();
            evidence.first_hop = Some(first_hop);
            evidence.last_hop = Some(last_hop);
        }
        LadderVerdict::Unsupported(reason) => {
            evidence.status = "UNSUPPORTED".into();
            evidence.reason = Some(unsupported_reason(reason).into());
        }
        LadderVerdict::Inconclusive => {}
    }
    // A cancelled run keeps its attempts but claims no interval.
    if report.stop == LadderStop::Cancelled {
        evidence.status = "CANCELLED".into();
        evidence.first_hop = None;
        evidence.last_hop = None;
    }
    let attempts = report
        .attempts
        .iter()
        .map(|attempt| TlsHopAttempt {
            ttl: attempt.ttl,
            role: match attempt.role {
                SniRole::Control => "CONTROL",
                SniRole::Test => "TEST",
            }
            .into(),
            repetition: attempt.repetition,
            outcome: match attempt.outcome {
                AttemptOutcome::Rst => "RST",
                AttemptOutcome::Fin => "FIN",
                AttemptOutcome::TlsAlert => "TLS_ALERT",
                AttemptOutcome::ServerHello => "SERVER_HELLO",
                AttemptOutcome::Other => "OTHER",
                AttemptOutcome::Silence => "SILENCE",
                AttemptOutcome::HandshakeFailed => "HANDSHAKE_FAILED",
                AttemptOutcome::LocalError => "LOCAL_ERROR",
            }
            .into(),
            elapsed_ms: attempt.elapsed_ms,
        })
        .collect();
    (evidence, attempts)
}

fn limit_token(limit: LadderLimit) -> &'static str {
    match limit {
        LadderLimit::ControlNeverReachedServer => "control_never_reached_server",
        LadderLimit::ControlReactsLikeTest => "control_reacts_like_test",
        LadderLimit::ServerHelloNearClient => "server_hello_near_client",
        LadderLimit::NetworkChanged => "network_changed",
        LadderLimit::InconsistentRepetitions => "inconsistent_repetitions",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_diagnostics_tls::tls::hop_ladder::LadderAttempt;

    fn config() -> TlsHopComparisonConfig {
        TlsHopComparisonConfig {
            host: "203.0.113.7".into(),
            control_sni: "control.example".into(),
            test_sni: "test.example".into(),
        }
    }

    fn evidence(result: &ProbeResult) -> TlsHopComparisonEvidence {
        let detail = result.details.iter().find(|detail| detail.key == TLS_HOP_EVIDENCE_KEY).unwrap();
        TlsHopComparisonEvidence::from_detail_json(&detail.value).unwrap()
    }

    #[test]
    fn proxy_path_is_an_unsupported_row_without_io() {
        let result = run_tls_hop_comparison(
            &config(),
            &TransportConfig::Direct { route_experiment: None },
            false,
            &AtomicBool::new(false),
        );
        assert_eq!(result.probe_type, "tls_hop_comparison");
        assert_eq!(result.target, "203.0.113.7");
        assert_eq!(result.outcome, "tls_hop_unsupported");
        assert_eq!(evidence(&result).reason.as_deref(), Some("proxy_path"));
        assert_eq!(evidence(&result).first_hop, None);
    }

    #[test]
    fn cancelled_before_start_is_a_cancelled_row() {
        let result = run_tls_hop_comparison(
            &config(),
            &TransportConfig::Direct { route_experiment: None },
            true,
            &AtomicBool::new(true),
        );
        assert_eq!(result.outcome, "tls_hop_cancelled");
    }

    fn attempt(ttl: u8, role: SniRole, outcome: AttemptOutcome) -> LadderAttempt {
        LadderAttempt { ttl, role, repetition: 0, outcome, elapsed_ms: 1000 + u64::from(ttl) }
    }

    #[test]
    fn divergence_report_keeps_interval_in_evidence_and_timings_local() {
        let report = LadderReport {
            verdict: LadderVerdict::Divergence { first_hop: 5, last_hop: 5 },
            path_length: Some(9),
            limits: vec![LadderLimit::InconsistentRepetitions],
            attempts: vec![
                attempt(5, SniRole::Control, AttemptOutcome::Silence),
                attempt(5, SniRole::Test, AttemptOutcome::Rst),
                attempt(9, SniRole::Control, AttemptOutcome::ServerHello),
            ],
            stop: LadderStop::Completed,
        };
        let (evidence, attempts) = summarize(report);
        assert_eq!(evidence.status, "DIVERGENCE");
        assert_eq!((evidence.first_hop, evidence.last_hop, evidence.path_length), (Some(5), Some(5), Some(9)));
        assert_eq!(evidence.limits, ["inconsistent_repetitions"]);
        assert_eq!((evidence.control_attempts, evidence.test_attempts), (2, 1));
        assert!(!evidence.to_detail_json().unwrap().contains("elapsed"));
        assert_eq!(attempts[1].outcome, "RST");
        assert_eq!(attempts[1].elapsed_ms, 1005);
    }

    #[test]
    fn cancelled_report_withdraws_interval_but_keeps_attempts() {
        let report = LadderReport {
            verdict: LadderVerdict::Divergence { first_hop: 1, last_hop: 3 },
            path_length: Some(9),
            limits: Vec::new(),
            attempts: vec![attempt(3, SniRole::Test, AttemptOutcome::Rst)],
            stop: LadderStop::Cancelled,
        };
        let (evidence, attempts) = summarize(report);
        assert_eq!(evidence.status, "CANCELLED");
        assert_eq!(evidence.first_hop, None);
        assert_eq!(attempts.len(), 1);
    }
}
