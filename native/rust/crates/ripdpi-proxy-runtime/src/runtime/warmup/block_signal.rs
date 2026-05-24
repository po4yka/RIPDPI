use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::routing::note_block_signal_for_failure;
use crate::runtime::state::RuntimeState;

pub(crate) fn record_block_signal(state: &RuntimeState, domain: &str, failure: &RuntimeClassifiedFailure) {
    note_block_signal_for_failure(state, Some(domain), failure, None);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::failure::{RuntimeFailureAction, RuntimeFailureClass, RuntimeFailureStage};

    #[test]
    fn record_block_signal_accepts_classified_failure() {
        let state = RuntimeState::test(RuntimeConfig::default());
        let failure = RuntimeClassifiedFailure::new(
            RuntimeFailureClass::TcpReset,
            RuntimeFailureStage::FirstResponse,
            RuntimeFailureAction::RetryWithMatchingGroup,
            "reset",
        );

        record_block_signal(&state, "example.com", &failure);
    }
}
