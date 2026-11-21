use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::run_strategy_control_transfer;
use crate::engine::runtime::{ExecutionPlan, authenticated_in_path_transport_matches};
use crate::transport::TransportConfig;
use crate::types::{DomainTarget, ProbeDetail, ProbeResult, ScanPathMode};

pub(super) fn targets(plan: &ExecutionPlan) -> Vec<(DomainTarget, bool)> {
    let mut control_pending = plan.strategy.is_some()
        && plan.request.path_mode == ScanPathMode::InPath
        && plan
            .request
            .in_path_route
            .as_ref()
            .is_some_and(|route| authenticated_in_path_transport_matches(route, &plan.transport));
    plan.request
        .domain_targets
        .iter()
        .map(|target| {
            let check_transfer = control_pending && target.is_control;
            control_pending &= !check_transfer;
            (target.clone(), check_transfer)
        })
        .collect()
}

pub(super) fn append_evidence(
    result: &mut ProbeResult,
    target: &DomainTarget,
    transport: &TransportConfig,
    cancel: &AtomicBool,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) {
    if let Some(stage) = run_strategy_control_transfer(target, transport, cancel, tls_verifier) {
        if !result.details.iter().any(|detail| detail.key == "isControl") {
            result.details.push(ProbeDetail { key: "isControl".into(), value: "true".into() });
        }
        result.details.push(stage);
    }
}
