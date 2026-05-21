//! TDD test file for TcpFatHeaderProbe.
//!
//! Each `#[test]` exercises one row of the verdict matrix specified in the
//! goal. The `equivalence` test pins that the probe covers exactly the same
//! `ProbeResult.outcome` statuses the previous scheduled TCP fat-header path
//! produced.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_probes::probes::tcp_fat_header::{
    TcpFatHeaderProbe, TcpFatHeaderStatus, TCP_FAT_HEADER_PROBE_ID,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

fn expected_verdict(status: TcpFatHeaderStatus) -> ProbeVerdict {
    match status {
        TcpFatHeaderStatus::Success => ProbeVerdict::Pass,
        TcpFatHeaderStatus::ThresholdCutoff => ProbeVerdict::Fail { class: "tcp-16kb-blocked".to_string() },
        TcpFatHeaderStatus::FreezeAfterThreshold => {
            ProbeVerdict::Fail { class: "tcp-freeze-after-threshold".to_string() }
        }
        TcpFatHeaderStatus::Reset => ProbeVerdict::Fail { class: "tcp-reset".to_string() },
        TcpFatHeaderStatus::Timeout => ProbeVerdict::Fail { class: "tcp-timeout".to_string() },
        TcpFatHeaderStatus::ConnectFailed => ProbeVerdict::Fail { class: "tcp-connect-failed".to_string() },
        TcpFatHeaderStatus::HandshakeFailed => ProbeVerdict::Fail { class: "tls-handshake-failed".to_string() },
    }
}

#[test]
fn id_and_family_are_correct() {
    let probe = TcpFatHeaderProbe::new("example.com:443".to_string(), TcpFatHeaderStatus::Success);
    assert_eq!(probe.id(), TCP_FAT_HEADER_PROBE_ID);
    assert_eq!(probe.family(), ProbeTaskFamily::Tcp);
    assert_eq!(probe.endpoint(), "example.com:443");
}

#[test]
fn every_status_maps_to_expected_verdict() {
    for &status in TcpFatHeaderStatus::ALL {
        let probe = TcpFatHeaderProbe::new("example.com:443".to_string(), status);
        let outcome = probe.run(&ProbeContext::empty());
        assert_eq!(outcome.probe_id, TCP_FAT_HEADER_PROBE_ID);
        assert_eq!(outcome.family, ProbeTaskFamily::Tcp);
        assert_eq!(outcome.verdict, expected_verdict(status), "unexpected verdict for {status:?}");
    }
}

#[test]
fn equivalence_scheduled_outcome_strings_are_pinned() {
    // Pins that the probe covers exactly the same `ProbeResult.outcome`
    // statuses the previous scheduled TCP fat-header path produced.
    for &status in TcpFatHeaderStatus::ALL {
        let expected = match status {
            TcpFatHeaderStatus::Success => "tcp_fat_header_ok",
            TcpFatHeaderStatus::ThresholdCutoff => "tcp_16kb_blocked",
            TcpFatHeaderStatus::FreezeAfterThreshold => "tcp_freeze_after_threshold",
            TcpFatHeaderStatus::Reset => "tcp_reset",
            TcpFatHeaderStatus::Timeout => "tcp_timeout",
            TcpFatHeaderStatus::ConnectFailed => "tcp_connect_failed",
            TcpFatHeaderStatus::HandshakeFailed => "tls_handshake_failed",
        };
        assert_eq!(status.scheduled_outcome(), expected, "scheduled_outcome drift for {status:?}");
    }
}
