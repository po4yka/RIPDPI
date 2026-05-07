use ripdpi_proxy_runtime_adapter::failure::{ClassifiedFailure, FailureAction};

use crate::runtime::routing::policy::runtime_supports_trigger;
use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
    ripdpi_proxy_runtime_adapter::response_triggers::failure_trigger_mask(failure)
}

pub(super) fn route_advance_trigger(state: &RuntimeState, failure: &ClassifiedFailure) -> std::io::Result<Option<u32>> {
    let trigger = failure_trigger_mask(failure);
    if failure.action != FailureAction::RetryWithMatchingGroup
        || trigger == 0
        || !runtime_supports_trigger(state, trigger)?
    {
        return Ok(None);
    }
    Ok(Some(trigger))
}

pub(in crate::runtime) fn failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
    ripdpi_proxy_runtime_adapter::response_triggers::failure_penalizes_strategy(failure)
}
