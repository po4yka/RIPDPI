use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::routing::note_block_signal_for_failure;
use crate::runtime::state::RuntimeState;

pub(crate) fn record_block_signal(state: &RuntimeState, domain: &str, failure: &RuntimeClassifiedFailure) {
    note_block_signal_for_failure(state, Some(domain), failure, None);
}
