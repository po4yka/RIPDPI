use ripdpi_failure_classifier::ClassifiedFailure;

use crate::runtime::routing::note_block_signal_for_failure;
use crate::runtime::state::RuntimeState;

pub(crate) fn record_block_signal(state: &RuntimeState, domain: &str, failure: &ClassifiedFailure) {
    note_block_signal_for_failure(state, Some(domain), failure, None);
}
