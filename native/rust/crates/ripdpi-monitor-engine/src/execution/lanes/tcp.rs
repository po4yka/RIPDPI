mod domain_probe;

use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::thread;
use std::time::Duration;

use ripdpi_monitor_adapter::proxy_config::ProxyRuntimeContext;
use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{StrategyCandidateSpec, target_probe_pause_ms};
use crate::tls::tls_key_log_callback_for_path;
use crate::types::DomainTarget;
use crate::util::stable_probe_hash;

use crate::execution::runtime::{CandidateRuntimeLauncher, probe_tcp_runtime_transport, run_candidate_warmup};
use crate::execution::scoring::{
    CandidateExecution, CandidateScore, build_candidate_execution, cancelled_candidate_execution,
    failed_candidate_execution, not_applicable_candidate_execution,
};

use self::domain_probe::probe_domain_chunk;

pub fn execute_tcp_candidate(
    runtime_launcher: &dyn CandidateRuntimeLauncher,
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    keylog_path: Option<&str>,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 3, "No HTTP or HTTPS targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_tcp_runtime_transport(runtime_launcher, spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            let key_log = keylog_path.map(tls_key_log_callback_for_path);
            run_candidate_warmup(spec, &transport, targets, tls_verifier, key_log.as_ref());
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
            for (chunk_index, chunk) in ordered_targets.chunks(PARALLEL_DOMAIN_BATCH_SIZE).enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
                if chunk_index > 0 {
                    // Inter-chunk pause: use the first target in the chunk as the key.
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &chunk[0].host)));
                }
                let chunk_results = probe_domain_chunk(chunk, &transport, spec, tls_verifier, key_log.as_ref());
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
        Err(err) => failed_candidate_execution(spec, targets.len() * 2, 3, err.to_string()),
    }
}
