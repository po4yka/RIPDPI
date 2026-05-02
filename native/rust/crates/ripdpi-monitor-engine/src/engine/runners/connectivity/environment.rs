use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{build_network_environment_probe, push_event};
use crate::engine::report::{build_report, connectivity_summary};
use crate::engine::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(in crate::engine::runners) struct EnvironmentRunner;

impl ExecutionStageRunner for EnvironmentRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Environment
    }

    fn phase(&self) -> &'static str {
        "environment"
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
        let artifacts = RunnerArtifacts::from_probe(probe.clone(), "network_environment", &plan.request.path_mode);
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
            runtime.finish_with_report(build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                connectivity_summary(&runtime.results, &plan.request.path_mode),
                runtime.results.clone(),
                runtime.observations.clone(),
                None,
                None,
            ));
            return RunnerOutcome::Finished;
        }
        if !snapshot.validated && !snapshot.captive_portal {
            warn(&runtime.shared, "OS reports unvalidated network; probe results may be unreliable".to_string());
        }
        RunnerOutcome::Completed
    }
}
