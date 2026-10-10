use crate::types::ScanRequest;

use super::super::runners::{PROBE_STAGE_REGISTRATIONS, registration_for_family};
use super::super::runtime::ExecutionStageId;

pub(super) fn connectivity_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    // Always-on stages — today only `Environment` — come first, in
    // registration order. Followed by either the probe-task-driven sequence
    // (user-supplied order, deduplicated) or the canonical registration
    // order for all selectable stages.
    let mut ordered: Vec<ExecutionStageId> = PROBE_STAGE_REGISTRATIONS
        .iter()
        .filter(|registration| registration.task_family_selector.is_none())
        .map(|registration| registration.stage_id.clone())
        .collect();

    if !request.probe_tasks.is_empty() {
        for task in &request.probe_tasks {
            if task.family == crate::types::ProbeTaskFamily::Pmtu && request.pmtu_probe.is_none() {
                continue;
            }
            if task.family == crate::types::ProbeTaskFamily::Http3 && request.http3_probe.is_none() {
                continue;
            }
            if task.family == crate::types::ProbeTaskFamily::IpFamily && request.ip_family_probe.is_none() {
                continue;
            }
            if let Some(registration) = registration_for_family(&task.family)
                && !ordered.contains(&registration.stage_id)
            {
                ordered.push(registration.stage_id.clone());
            }
        }
        return ordered;
    }

    for registration in PROBE_STAGE_REGISTRATIONS {
        if matches!(
            registration.stage_id,
            ExecutionStageId::SelectiveAvailability
                | ExecutionStageId::IpFamily
                | ExecutionStageId::Http3
                | ExecutionStageId::Pmtu
        ) {
            continue;
        }
        if registration.task_family_selector.is_some() && !ordered.contains(&registration.stage_id) {
            ordered.push(registration.stage_id.clone());
        }
    }
    ordered
}
