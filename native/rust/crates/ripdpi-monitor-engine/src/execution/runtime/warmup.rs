mod tls;

use std::sync::atomic::AtomicBool;

use crate::candidates::{CandidateWarmup, StrategyCandidateSpec};
use crate::http::try_http_request;
use crate::tls::ProbeStreamOptions;
use crate::transport::{TransportConfig, domain_connect_target};
use crate::types::DomainTarget;

pub fn run_candidate_warmup(
    spec: &StrategyCandidateSpec,
    transport: &TransportConfig,
    targets: &[DomainTarget],
    options: &ProbeStreamOptions<'_>,
    cancel: &AtomicBool,
) {
    if spec.warmup != CandidateWarmup::AdaptiveFakeTtl {
        return;
    }
    let should_abort = || super::candidate_abort_reason(cancel);
    for target in targets {
        if should_abort().is_some() {
            break;
        }
        let address = domain_connect_target(target);
        let http_port = target.http_port.unwrap_or(80);
        let https_port = target.https_port.unwrap_or(443);
        let _ = try_http_request(&address, http_port, transport, &target.host, &target.http_path, false);
        tls::warmup_tls13(transport, target, options, https_port, &should_abort);
    }
}
