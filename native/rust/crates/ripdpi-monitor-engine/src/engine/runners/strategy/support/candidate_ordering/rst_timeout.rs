use std::time::Duration;

use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass};

pub(in crate::engine::runners::strategy) fn compute_rst_adaptive_timeout(
    baseline_failure: &ClassifiedFailure,
) -> Option<Duration> {
    if !matches!(baseline_failure.class, FailureClass::TcpReset) {
        return None;
    }
    Some(Duration::from_millis(1500))
}
