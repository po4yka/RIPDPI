//! TDD test file for CircumventionReachabilityProbe.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_probes::circumvention_reachability::{
    CircumventionLayer, CircumventionLayerOutcome, CircumventionLayerStatus, CircumventionReachabilityProbe,
    CIRCUMVENTION_REACHABILITY_PROBE_ID,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

fn ok(layer: CircumventionLayer) -> CircumventionLayerOutcome {
    CircumventionLayerOutcome { layer, status: CircumventionLayerStatus::Ok }
}

fn failed(layer: CircumventionLayer, detail: &str) -> CircumventionLayerOutcome {
    CircumventionLayerOutcome { layer, status: CircumventionLayerStatus::Failed { detail: detail.to_string() } }
}

fn setup_err(layer: CircumventionLayer, detail: &str) -> CircumventionLayerOutcome {
    CircumventionLayerOutcome { layer, status: CircumventionLayerStatus::SetupError { detail: detail.to_string() } }
}

#[test]
fn all_layers_ok_yields_pass() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![
            ok(CircumventionLayer::Bootstrap),
            ok(CircumventionLayer::Tcp),
            ok(CircumventionLayer::TlsHandshake),
            ok(CircumventionLayer::Liveness),
        ],
    );
    let outcome = probe.run(&ProbeContext::empty());
    assert_eq!(outcome.probe_id, CIRCUMVENTION_REACHABILITY_PROBE_ID);
    assert_eq!(outcome.family, ProbeTaskFamily::Circumvention);
    assert_eq!(outcome.verdict, ProbeVerdict::Pass);
}

#[test]
fn empty_layers_yields_inconclusive() {
    let probe = CircumventionReachabilityProbe::new("vless-relay-a".to_string(), vec![]);
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("no layer"), "reason: {reason}"),
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}

#[test]
fn all_setup_errors_yields_inconclusive() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![setup_err(CircumventionLayer::Bootstrap, "no client"), setup_err(CircumventionLayer::Tcp, "no dialer")],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("setup error"), "reason: {reason}"),
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}

#[test]
fn tcp_failed_yields_tcp_blocked() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![ok(CircumventionLayer::Bootstrap), failed(CircumventionLayer::Tcp, "ECONNREFUSED")],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "circumvention-tcp-blocked"),
        other => panic!("expected Fail{{circumvention-tcp-blocked}}, got {other:?}"),
    }
}

#[test]
fn tls_handshake_failed_yields_tls_blocked() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![
            ok(CircumventionLayer::Bootstrap),
            ok(CircumventionLayer::Tcp),
            failed(CircumventionLayer::TlsHandshake, "alert: handshake_failure"),
        ],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "circumvention-tlshandshake-blocked"),
        other => panic!("expected Fail{{circumvention-tlshandshake-blocked}}, got {other:?}"),
    }
}

#[test]
fn liveness_failed_yields_liveness_blocked() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![
            ok(CircumventionLayer::Bootstrap),
            ok(CircumventionLayer::Tcp),
            ok(CircumventionLayer::TlsHandshake),
            failed(CircumventionLayer::Liveness, "no pong"),
        ],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "circumvention-liveness-blocked"),
        other => panic!("expected Fail{{circumvention-liveness-blocked}}, got {other:?}"),
    }
}

#[test]
fn multiple_layers_failed_yields_multi_layer_blocked() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![
            failed(CircumventionLayer::Tcp, "ECONNREFUSED"),
            failed(CircumventionLayer::TlsHandshake, "handshake failed"),
        ],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "circumvention-multi-layer-blocked"),
        other => panic!("expected Fail{{circumvention-multi-layer-blocked}}, got {other:?}"),
    }
}

#[test]
fn bootstrap_failed_yields_bootstrap_blocked() {
    let probe = CircumventionReachabilityProbe::new(
        "vless-relay-a".to_string(),
        vec![failed(CircumventionLayer::Bootstrap, "HTTP 403")],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "circumvention-bootstrap-blocked"),
        other => panic!("expected Fail{{circumvention-bootstrap-blocked}}, got {other:?}"),
    }
}
