use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

mod report;

use crate::connectivity::{build_network_environment_probe, push_event};
use crate::engine::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

const PHASE: &str = "environment";
const ARTIFACT_SOURCE: &str = "network_environment";

pub(in crate::engine::runners) struct EnvironmentRunner;

impl ExecutionStageRunner for EnvironmentRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Environment
    }

    fn phase(&self) -> &'static str {
        PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.request.network_snapshot.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(snapshot) = plan.request.network_snapshot.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let probe = build_network_environment_probe(Some(snapshot)).expect("snapshot probe");
        let artifacts = RunnerArtifacts::from_probe(probe.clone(), ARTIFACT_SOURCE, &plan.request.path_mode);
        runtime.record_step(
            plan,
            self.phase(),
            "Collected network environment".to_string(),
            Some(probe.target.clone()),
            Some(probe.outcome.clone()),
            None,
            artifacts,
        );
        let warn = |shared: &_, msg: String| {
            push_event(
                shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "warn",
                msg,
            );
        };
        if snapshot.transport == "none" && !snapshot.vpn_service_was_active {
            warn(&runtime.shared, "OS reports no network; aborting scan".to_string());
            report::finish_offline_scan(plan, runtime);
            return RunnerOutcome::Finished;
        }
        if !snapshot.validated && !snapshot.captive_portal {
            warn(&runtime.shared, "OS reports unvalidated network; probe results may be unreliable".to_string());
        }
        RunnerOutcome::Completed
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicBool;
    use std::sync::{Arc, Mutex};

    use ripdpi_monitor_adapter::proxy_config::NetworkSnapshot;

    use super::EnvironmentRunner;
    use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageRunner, RunnerOutcome};
    use crate::transport::direct_transport;
    use crate::types::{
        DiagnosticProfileFamily, ScanCompletionKind, ScanKind, ScanPathMode, ScanRequest, ScanTerminationReason,
        SharedState,
    };

    fn base_request(snapshot: Option<NetworkSnapshot>) -> ScanRequest {
        ScanRequest {
            profile_id: "env-test".to_string(),
            display_name: "Env test".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::Connectivity,
            family: DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            in_path_route: None,
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
            network_snapshot: snapshot,
            route_probe: None,
            scan_deadline_ms: None,
            diagnostic_tls_keylog_path: None,
        }
    }

    fn fixture_plan(snapshot: Option<NetworkSnapshot>) -> ExecutionPlan {
        ExecutionPlan {
            session_id: "env-session".to_string(),
            request: base_request(snapshot),
            started_at: 0,
            total_steps: 4,
            transport: direct_transport(),
            probe_context: crate::connectivity::ProbeExecutionContext::new(direct_transport()),
            stage_order: Vec::new(),
            strategy: None,
        }
    }

    fn make_runtime() -> ExecutionRuntime {
        let shared = Arc::new(Mutex::new(SharedState::default()));
        let cancel = Arc::new(AtomicBool::new(false));
        ExecutionRuntime::new(shared, cancel)
    }

    /// G4-a: `network_snapshot is None` → `Completed`, no probe recorded.
    #[test]
    fn environment_short_circuits_when_snapshot_absent() {
        let plan = fixture_plan(None);
        let mut runtime = make_runtime();
        let runner = EnvironmentRunner;

        let outcome = runner.run(&plan, &mut runtime, None);

        assert!(matches!(outcome, RunnerOutcome::Completed), "expected Completed when no snapshot is present");
        assert_eq!(runtime.completed_steps, 0, "no steps should be recorded");
        assert!(runtime.final_report.is_none(), "no report should be set");
    }

    /// G4-b: `transport == "none" && !vpn_service_was_active` → `Finished`,
    /// `finish_with_report` called (final_report is Some).
    #[test]
    fn environment_short_circuits_no_network_aborts_scan() {
        let snapshot = NetworkSnapshot {
            transport: "none".to_string(),
            validated: false,
            vpn_service_was_active: false,
            ..Default::default()
        };
        let plan = fixture_plan(Some(snapshot));
        let mut runtime = make_runtime();
        let runner = EnvironmentRunner;

        let outcome = runner.run(&plan, &mut runtime, None);

        assert!(
            matches!(outcome, RunnerOutcome::Finished),
            "expected Finished when OS reports no network and VPN was inactive"
        );
        let report = runtime.final_report.expect("finish_with_report must publish a no-network report");
        assert_eq!(report.completion_kind, ScanCompletionKind::Terminated);
        assert_eq!(report.termination_reason, Some(ScanTerminationReason::NetworkUnavailable));
        assert!(report.diagnoses.iter().any(|diagnosis| diagnosis.code == "network_unavailable"));
    }

    /// G4-c: `transport == "none" && vpn_service_was_active` → `Completed`,
    /// no abort (VPN tunnel down is not a missing physical network).
    #[test]
    fn environment_completes_when_vpn_was_active_despite_no_transport() {
        let snapshot = NetworkSnapshot {
            transport: "none".to_string(),
            validated: false,
            vpn_service_was_active: true,
            ..Default::default()
        };
        let plan = fixture_plan(Some(snapshot));
        let mut runtime = make_runtime();
        let runner = EnvironmentRunner;

        let outcome = runner.run(&plan, &mut runtime, None);

        assert!(
            matches!(outcome, RunnerOutcome::Completed),
            "expected Completed when VPN was active (tunnel down, not missing network)"
        );
        assert!(runtime.final_report.is_none(), "scan should not be aborted");
    }

    /// G4-d: `!validated && !captive_portal` → `Completed`, warn emitted via
    /// tracing (not observable in SharedState), but no abort.
    #[test]
    fn environment_completes_with_unvalidated_network_warning() {
        let snapshot = NetworkSnapshot {
            transport: "wifi".to_string(),
            validated: false,
            captive_portal: false,
            vpn_service_was_active: false,
            ..Default::default()
        };
        let plan = fixture_plan(Some(snapshot));
        let mut runtime = make_runtime();
        let runner = EnvironmentRunner;

        let outcome = runner.run(&plan, &mut runtime, None);

        assert!(
            matches!(outcome, RunnerOutcome::Completed),
            "expected Completed for unvalidated network (warn only, no abort)"
        );
        assert!(runtime.final_report.is_none(), "unvalidated network must not abort the scan");
        assert_eq!(runtime.completed_steps, 1, "environment probe step must be recorded");
    }
}
