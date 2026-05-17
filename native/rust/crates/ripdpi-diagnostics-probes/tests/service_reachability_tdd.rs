//! TDD test file for ServiceReachabilityProbe.
//!
//! Written before the implementation exists. Each `#[test]` exercises
//! one row of the verdict matrix specified in the goal.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;
use ripdpi_diagnostics_probes::service_reachability::{
    LayerStatus, ServiceLayer, ServiceLayerOutcome, ServiceReachabilityProbe, SERVICE_REACHABILITY_PROBE_ID,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

fn ok(layer: ServiceLayer) -> ServiceLayerOutcome {
    ServiceLayerOutcome { layer, status: LayerStatus::Ok }
}

fn failed(layer: ServiceLayer, detail: &str) -> ServiceLayerOutcome {
    ServiceLayerOutcome { layer, status: LayerStatus::Failed { detail: detail.to_string() } }
}

fn setup_err(layer: ServiceLayer, detail: &str) -> ServiceLayerOutcome {
    ServiceLayerOutcome { layer, status: LayerStatus::SetupError { detail: detail.to_string() } }
}

#[test]
fn all_layers_ok_yields_pass() {
    let probe = ServiceReachabilityProbe::new(
        "youtube".to_string(),
        vec![ok(ServiceLayer::Dns), ok(ServiceLayer::Tcp), ok(ServiceLayer::Tls), ok(ServiceLayer::Http)],
    );
    let outcome = probe.run(&ProbeContext::empty());
    assert_eq!(outcome.probe_id, SERVICE_REACHABILITY_PROBE_ID);
    assert_eq!(outcome.family, ProbeTaskFamily::Service);
    assert_eq!(outcome.verdict, ProbeVerdict::Pass);
}

#[test]
fn empty_layers_yields_inconclusive() {
    let probe = ServiceReachabilityProbe::new("youtube".to_string(), vec![]);
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("no layer"), "reason: {reason}"),
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}

#[test]
fn all_setup_errors_yields_inconclusive() {
    let probe = ServiceReachabilityProbe::new(
        "youtube".to_string(),
        vec![setup_err(ServiceLayer::Dns, "no resolver"), setup_err(ServiceLayer::Tcp, "no dialer")],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Inconclusive { reason } => assert!(reason.contains("setup error"), "reason: {reason}"),
        other => panic!("expected Inconclusive, got {other:?}"),
    }
}

#[test]
fn single_layer_failed_yields_fail_with_specific_class() {
    let probe = ServiceReachabilityProbe::new(
        "youtube".to_string(),
        vec![
            ok(ServiceLayer::Dns),
            failed(ServiceLayer::Tcp, "ECONNREFUSED"),
            ok(ServiceLayer::Tls),
            ok(ServiceLayer::Http),
        ],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "service-tcp-blocked"),
        other => panic!("expected Fail{{service-tcp-blocked}}, got {other:?}"),
    }
}

#[test]
fn multiple_layers_failed_yields_multi_layer_blocked() {
    let probe = ServiceReachabilityProbe::new(
        "youtube".to_string(),
        vec![
            failed(ServiceLayer::Tcp, "ECONNREFUSED"),
            failed(ServiceLayer::Tls, "handshake failed"),
            ok(ServiceLayer::Http),
        ],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "service-multi-layer-blocked"),
        other => panic!("expected Fail{{service-multi-layer-blocked}}, got {other:?}"),
    }
}

#[test]
fn dns_failed_yields_dns_blocked_class() {
    let probe = ServiceReachabilityProbe::new("youtube".to_string(), vec![failed(ServiceLayer::Dns, "NXDOMAIN")]);
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "service-dns-blocked"),
        other => panic!("expected Fail{{service-dns-blocked}}, got {other:?}"),
    }
}

#[test]
fn http_failed_yields_http_blocked_class() {
    let probe = ServiceReachabilityProbe::new(
        "youtube".to_string(),
        vec![ok(ServiceLayer::Dns), ok(ServiceLayer::Tcp), ok(ServiceLayer::Tls), failed(ServiceLayer::Http, "503")],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "service-http-blocked"),
        other => panic!("expected Fail{{service-http-blocked}}, got {other:?}"),
    }
}

#[test]
fn setup_error_does_not_count_as_failed_layer() {
    // One layer failed, others setup-errored. Should still be single-layer Fail,
    // not multi-layer.
    let probe = ServiceReachabilityProbe::new(
        "youtube".to_string(),
        vec![failed(ServiceLayer::Tcp, "ECONNREFUSED"), setup_err(ServiceLayer::Tls, "no client")],
    );
    match probe.run(&ProbeContext::empty()).verdict {
        ProbeVerdict::Fail { class } => assert_eq!(class, "service-tcp-blocked"),
        other => panic!("expected Fail{{service-tcp-blocked}}, got {other:?}"),
    }
}
