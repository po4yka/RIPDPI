use crate::types::StrategyProbeResponseStage;

pub(super) fn quic_sample_quality(outcome: &str) -> usize {
    if outcome == "quic_initial_response" { 4 } else { 0 }
}

pub(in crate::execution) fn sample_response_stage(success: bool, outcome: &str) -> StrategyProbeResponseStage {
    if success
        || matches!(
            outcome,
            "quic_initial_ack"
                | "quic_initial_other"
                | "quic_initial_close"
                | "quic_retry"
                | "quic_version_negotiation"
        )
    {
        StrategyProbeResponseStage::ResponseObserved
    } else {
        StrategyProbeResponseStage::ResponseNotObserved
    }
}
