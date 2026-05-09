use crate::runtime::routing::note_block_signal_for_failure;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeClassifiedFailure;

pub(crate) fn record_block_signal(state: &RuntimeState, domain: &str, failure: &RuntimeClassifiedFailure) {
    note_block_signal_for_failure(state, Some(domain), failure, None);
}
