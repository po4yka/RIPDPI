use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::engine::runtime::{CollectedStageOutcome, CollectedStep, ExecutionPlan, RunnerArtifacts};
use crate::types::ProbeResult;

pub(super) trait ConnectivityProbeFamily {
    type Target: Clone;

    const PHASE: &'static str;
    const ARTIFACT_SOURCE: &'static str;

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target>;
    fn message(target: &Self::Target) -> String;
    #[rustfmt::skip]
    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        probe_context: &ProbeExecutionContext,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult;
}

pub(super) fn collect_family_steps<F: ConnectivityProbeFamily>(
    plan: &ExecutionPlan,
    cancel: &AtomicBool,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> CollectedStageOutcome {
    let targets = F::targets(plan);
    let mut steps = Vec::with_capacity(targets.len());
    for target in targets {
        if cancel.load(Ordering::Acquire) {
            return CollectedStageOutcome::Cancelled(steps);
        }
        let message = F::message(&target);
        let probe = F::run_probe(&target, plan, &plan.probe_context, cancel, tls_verifier);
        let outcome = probe.outcome.clone();
        let artifacts = RunnerArtifacts::from_probe(probe, F::ARTIFACT_SOURCE, &plan.request.path_mode);
        steps.push(CollectedStep {
            phase: F::PHASE,
            latest_probe_target: Some(message.clone()),
            message,
            latest_probe_outcome: Some(outcome),
            artifacts,
        });
        if cancel.load(Ordering::Acquire) {
            return CollectedStageOutcome::Cancelled(steps);
        }
    }
    CollectedStageOutcome::Completed(steps)
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    use rustls::client::danger::ServerCertVerifier;

    use super::{ConnectivityProbeFamily, collect_family_steps};
    use crate::connectivity::ProbeExecutionContext;
    use crate::engine::runtime::{CollectedStageOutcome, ExecutionPlan};
    use crate::transport::direct_transport;
    use crate::types::{DiagnosticProfileFamily, ProbeDetail, ProbeResult, ScanKind, ScanPathMode, ScanRequest};

    // ---------------------------------------------------------------------------
    // Fixture: a stub probe family backed by a per-target counter so tests can
    // observe exactly how many probes were executed.
    // ---------------------------------------------------------------------------

    static PROBE_CALL_COUNT: AtomicUsize = AtomicUsize::new(0);

    struct CountingFamily;

    impl ConnectivityProbeFamily for CountingFamily {
        type Target = String;

        const PHASE: &'static str = "test";
        const ARTIFACT_SOURCE: &'static str = "test_probe";

        fn targets(_plan: &ExecutionPlan) -> Vec<Self::Target> {
            vec!["target-a".to_string(), "target-b".to_string()]
        }

        fn message(target: &Self::Target) -> String {
            format!("probe {target}")
        }

        fn run_probe(
            target: &Self::Target,
            _plan: &ExecutionPlan,
            _probe_context: &ProbeExecutionContext,
            _cancel: &AtomicBool,
            _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
        ) -> ProbeResult {
            PROBE_CALL_COUNT.fetch_add(1, Ordering::Relaxed);
            ProbeResult {
                probe_type: "test_probe".to_string(),
                target: target.clone(),
                outcome: "ok".to_string(),
                details: vec![ProbeDetail { key: "fixture".to_string(), value: "true".to_string() }],
            }
        }
    }

    fn fixture_plan() -> ExecutionPlan {
        ExecutionPlan {
            session_id: "test-cancel".to_string(),
            request: ScanRequest {
                profile_id: "test".to_string(),
                display_name: "Test".to_string(),
                path_mode: ScanPathMode::RawPath,
                kind: ScanKind::Connectivity,
                family: DiagnosticProfileFamily::General,
                region_tag: None,
                manual_only: false,
                pack_refs: Vec::new(),
                proxy_host: None,
                proxy_port: None,
                probe_tasks: Vec::new(),
                domain_targets: Vec::new(),
                dns_targets: Vec::new(),
                tcp_targets: Vec::new(),
                quic_targets: Vec::new(),
                service_targets: Vec::new(),
                circumvention_targets: Vec::new(),
                throughput_targets: Vec::new(),
                whitelist_sni: Vec::new(),
                telegram_target: None,
                strategy_probe: None,
                confirm_good_dpi_evidence: None,
                network_snapshot: None,
                route_probe: None,
                scan_deadline_ms: None,
                diagnostic_tls_keylog_path: None,
            },
            started_at: 0,
            total_steps: 2,
            transport: direct_transport(),
            probe_context: crate::connectivity::ProbeExecutionContext::new(direct_transport()),
            stage_order: Vec::new(),
            strategy: None,
        }
    }

    /// G3-a: cancel pre-set → `Cancelled` immediately, zero `run_probe` calls.
    #[test]
    fn cancel_propagates_before_any_probe_runs() {
        PROBE_CALL_COUNT.store(0, Ordering::Relaxed);
        let plan = fixture_plan();
        let cancel = AtomicBool::new(true); // pre-cancelled

        let outcome = collect_family_steps::<CountingFamily>(&plan, &cancel, None);

        assert!(
            matches!(outcome, CollectedStageOutcome::Cancelled(ref steps) if steps.is_empty()),
            "expected Cancelled with no steps when cancel is pre-set"
        );
        assert_eq!(PROBE_CALL_COUNT.load(Ordering::Relaxed), 0, "no probes should run when cancel is pre-set");
    }

    /// G3-b: cancel set after the first probe → `Cancelled` after exactly one probe call.
    ///
    /// `CountingFamily` has two targets ("target-a", "target-b").  We use a
    /// wrapper that sets the cancel flag inside `run_probe` on the first call.
    #[test]
    fn cancel_propagates_after_first_probe_runs() {
        static CANCEL_AFTER_FIRST_CALL_COUNT: AtomicUsize = AtomicUsize::new(0);
        static CANCEL_FLAG_PTR: std::sync::atomic::AtomicPtr<AtomicBool> =
            std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

        struct CancelAfterFirstFamily;

        impl ConnectivityProbeFamily for CancelAfterFirstFamily {
            type Target = String;

            const PHASE: &'static str = "test";
            const ARTIFACT_SOURCE: &'static str = "test_probe";

            fn targets(_plan: &ExecutionPlan) -> Vec<Self::Target> {
                vec!["target-a".to_string(), "target-b".to_string()]
            }

            fn message(target: &Self::Target) -> String {
                format!("probe {target}")
            }

            fn run_probe(
                _target: &Self::Target,
                _plan: &ExecutionPlan,
                probe_context: &ProbeExecutionContext,
                _cancel: &AtomicBool,
                _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
            ) -> ProbeResult {
                assert!(probe_context.approved_primary_resolver().is_none());
                let count = CANCEL_AFTER_FIRST_CALL_COUNT.fetch_add(1, Ordering::Relaxed);
                if count == 0 {
                    // First call: set the cancel flag so the post-probe check
                    // fires and returns Cancelled before the second target runs.
                    let ptr = CANCEL_FLAG_PTR.load(Ordering::Acquire);
                    if !ptr.is_null() {
                        // SAFETY: `ptr` was published (line below in the test body) from
                        // `&cancel as *const AtomicBool as *mut AtomicBool`, where `cancel`
                        // is a stack-local owned by the enclosing test. `run_probe` is invoked
                        // synchronously from `collect_family_steps`, which is called while that
                        // stack frame is still live, so `cancel` is not yet dropped: the pointer
                        // is non-null (checked above), properly aligned (derived from a live
                        // `&AtomicBool`), and points to an initialized `AtomicBool`. No `&mut`
                        // alias exists — the only other access is the shared `&cancel` borrow
                        // inside `collect_family_steps`, and `AtomicBool::store` synchronizes
                        // concurrent access — so dereferencing `ptr` to a shared reference is
                        // sound. The single unsafe op is the raw-pointer deref `*ptr`.
                        unsafe { (*ptr).store(true, Ordering::Release) };
                    }
                }
                ProbeResult {
                    probe_type: "test_probe".to_string(),
                    target: "target-a".to_string(),
                    outcome: "ok".to_string(),
                    details: Vec::new(),
                }
            }
        }

        CANCEL_AFTER_FIRST_CALL_COUNT.store(0, Ordering::Relaxed);
        let plan = fixture_plan();
        let cancel = AtomicBool::new(false);
        // Share the cancel flag with the probe via the atomic pointer.
        CANCEL_FLAG_PTR.store(&cancel as *const AtomicBool as *mut AtomicBool, Ordering::Release);

        let outcome = collect_family_steps::<CancelAfterFirstFamily>(&plan, &cancel, None);

        // Reset pointer so it doesn't dangle after this test.
        CANCEL_FLAG_PTR.store(std::ptr::null_mut(), Ordering::Release);

        assert!(
            matches!(outcome, CollectedStageOutcome::Cancelled(ref steps) if steps.len() == 1),
            "expected Cancelled with exactly one step collected"
        );
        assert_eq!(
            CANCEL_AFTER_FIRST_CALL_COUNT.load(Ordering::Relaxed),
            1,
            "exactly one probe should run before cancellation"
        );
    }
}
