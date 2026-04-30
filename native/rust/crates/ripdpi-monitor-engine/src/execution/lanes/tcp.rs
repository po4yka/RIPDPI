use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use ripdpi_proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{target_probe_pause_ms, StrategyCandidateSpec};
use crate::types::DomainTarget;
use crate::util::stable_probe_hash;

use super::http::run_http_strategy_probe;
use super::https::run_https_strategy_probe;
use crate::execution::runtime::{probe_runtime_transport, run_candidate_warmup, CandidateRuntimeLauncher};
use crate::execution::scoring::{
    build_candidate_execution, cancelled_candidate_execution, failed_candidate_execution,
    not_applicable_candidate_execution, CandidateExecution, CandidateScore, ProbeSample,
};

pub fn execute_tcp_candidate(
    runtime_launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 3, "No HTTP or HTTPS targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(runtime_launcher, spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            run_candidate_warmup(spec, &transport, targets, tls_verifier);
            if cancel.load(Ordering::Acquire) {
                drop(runtime);
                return cancelled_candidate_execution(spec, CandidateScore::default(), 3);
            }
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));

            // Test domains in parallel batches to reduce per-candidate probe time.
            // Batch size of 3 keeps concurrency safe (different destinations, no DPI
            // state collision) while cutting wall-clock time from ~15-20s to ~6-8s.
            const PARALLEL_DOMAIN_BATCH_SIZE: usize = 3;
            let chunks: Vec<&[DomainTarget]> = ordered_targets.chunks(PARALLEL_DOMAIN_BATCH_SIZE).collect();
            for (chunk_index, chunk) in chunks.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
                if chunk_index > 0 {
                    // Inter-chunk pause: use the first target in the chunk as the key.
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &chunk[0].host)));
                }
                // Run HTTP + HTTPS for each domain in this chunk concurrently.
                let chunk_results: Vec<Vec<ProbeSample>> = thread::scope(|s| {
                    chunk
                        .iter()
                        .map(|target| {
                            let transport = transport.clone();
                            s.spawn(move || {
                                let samples = vec![
                                    run_http_strategy_probe(&transport, target, spec),
                                    run_https_strategy_probe(&transport, target, spec, tls_verifier),
                                ];
                                samples
                            })
                        })
                        .collect::<Vec<_>>()
                        .into_iter()
                        .map(|handle| handle.join().unwrap_or_default())
                        .collect()
                });
                for samples in chunk_results {
                    for sample in samples {
                        score.add(sample);
                    }
                }
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
            }
            drop(runtime);
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "tcp",
            )
            .record(probe_started.elapsed().as_secs_f64());
            build_candidate_execution(spec, score, 3)
        }
        Err(err) => failed_candidate_execution(spec, targets.len() * 2, 3, err),
    }
}
