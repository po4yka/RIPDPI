use crate::engine::report::{build_report, connectivity_summary};
use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime};

pub(super) fn finish_offline_scan(plan: &ExecutionPlan, runtime: &mut ExecutionRuntime) {
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
}
