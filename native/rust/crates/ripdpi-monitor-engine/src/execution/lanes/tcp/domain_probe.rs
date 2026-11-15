use std::sync::atomic::AtomicBool;
use std::thread;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::{runtime::candidate_abort_reason, scoring::ProbeSample};
use crate::tls::ProbeStreamOptions;
use crate::types::DomainTarget;
use crate::{CandidateAttemptCorrelationId, CandidateProbeRuntime};

use super::super::http::run_http_strategy_probe;
use super::super::https::run_https_strategy_probe;

pub(super) fn probe_domain_chunk(
    chunk: &[DomainTarget],
    runtime: &dyn CandidateProbeRuntime,
    generation: u64,
    ordinal_base: u64,
    spec: &StrategyCandidateSpec,
    options: &ProbeStreamOptions<'_>,
    cancel: &AtomicBool,
) -> Result<Vec<Vec<ProbeSample>>, ProbeWorkerPanicked> {
    let deadline = ripdpi_diagnostics_contracts::util::active_scan_io_deadline();
    let attempts = chunk
        .iter()
        .enumerate()
        .map(|(index, target)| {
            let http_token =
                CandidateAttemptCorrelationId::evaluated(generation, ordinal_base + (index as u64 * 2) + 1);
            let https_token =
                CandidateAttemptCorrelationId::evaluated(generation, ordinal_base + (index as u64 * 2) + 2);
            let http_transport =
                http_token.as_ref().map_or_else(|| runtime.transport(), |token| runtime.transport_for_attempt(token));
            let https_transport =
                https_token.as_ref().map_or_else(|| runtime.transport(), |token| runtime.transport_for_attempt(token));
            (target, http_token, http_transport, https_token, https_transport)
        })
        .collect::<Vec<_>>();
    thread::scope(|s| {
        let joined = attempts
            .into_iter()
            .map(|(target, http_token, http_transport, https_token, https_transport)| {
                s.spawn(move || {
                    ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                        let should_abort = || candidate_abort_reason(cancel);
                        if should_abort().is_some() {
                            return Vec::new();
                        }
                        let mut http = run_http_strategy_probe(&http_transport, target, spec);
                        http.attempt_token = http_token;
                        if should_abort().is_some() {
                            return vec![http];
                        }
                        let mut https =
                            run_https_strategy_probe(&https_transport, target, spec, options, &should_abort);
                        https.attempt_token = https_token;
                        vec![http, https]
                    })
                })
            })
            .collect::<Vec<_>>()
            .into_iter()
            .map(std::thread::ScopedJoinHandle::join)
            .collect::<Vec<_>>();
        collect_probe_worker_results(joined)
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct ProbeWorkerPanicked;

fn collect_probe_worker_results(
    joined: Vec<thread::Result<Vec<ProbeSample>>>,
) -> Result<Vec<Vec<ProbeSample>>, ProbeWorkerPanicked> {
    joined.into_iter().collect::<Result<Vec<_>, _>>().map_err(|_| ProbeWorkerPanicked)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn panicking_probe_worker_makes_the_whole_chunk_incomplete() {
        let panic: Box<dyn std::any::Any + Send> = Box::new("probe panic");

        assert!(matches!(collect_probe_worker_results(vec![Ok(Vec::new()), Err(panic)]), Err(ProbeWorkerPanicked)));
    }
}
