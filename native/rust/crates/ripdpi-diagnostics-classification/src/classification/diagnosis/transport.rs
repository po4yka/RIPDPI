use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub fn classify_transport_failure_text(text: &str, stage: FailureStage) -> Option<ClassifiedFailure> {
    let normalized = text.trim().to_ascii_lowercase();
    if normalized.is_empty() || normalized == "none" {
        return None;
    }
    if normalized.contains("alert") {
        return Some(ClassifiedFailure::new(
            FailureClass::TlsAlert,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("reset")
        || normalized.contains("broken pipe")
        || normalized.contains("aborted")
        || normalized.contains("unexpected eof")
    {
        return Some(ClassifiedFailure::new(
            FailureClass::TcpReset,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("timed out") || normalized.contains("timeout") || normalized.contains("would block") {
        return Some(ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    None
}
