use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub fn classify_connection_freeze(bytes_received: usize, stall_windows: u32, window_ms: u32) -> ClassifiedFailure {
    ClassifiedFailure::new(
        FailureClass::ConnectionFreeze,
        FailureStage::Relay,
        FailureAction::RetryWithMatchingGroup,
        format!("Connection froze: {bytes_received} bytes over {stall_windows} stalled windows ({window_ms}ms each)"),
    )
    .with_tag("bytesReceived", bytes_received.to_string())
    .with_tag("stallWindows", stall_windows.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_freeze_has_correct_class_stage_and_action() {
        let f = classify_connection_freeze(1024, 3, 5000);
        assert_eq!(f.class, FailureClass::ConnectionFreeze);
        assert_eq!(f.stage, FailureStage::Relay);
        assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
        assert!(f.evidence.summary.contains("1024 bytes"));
        assert!(f.evidence.tags.iter().any(|t| t == "bytesReceived=1024"));
        assert!(f.evidence.tags.iter().any(|t| t == "stallWindows=3"));
    }

    #[test]
    fn connection_freeze_with_zero_bytes() {
        let f = classify_connection_freeze(0, 1, 1000);
        assert_eq!(f.class, FailureClass::ConnectionFreeze);
        assert!(f.evidence.summary.contains("0 bytes"));
    }
}
