use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub fn classify_quic_probe(outcome: &str, error: Option<&str>) -> Option<ClassifiedFailure> {
    if matches!(outcome, "quic_initial_response" | "quic_response") {
        return None;
    }
    let summary = error.filter(|value| !value.is_empty()).unwrap_or(outcome);
    Some(
        ClassifiedFailure::new(
            FailureClass::QuicBreakage,
            FailureStage::QuicProbe,
            FailureAction::DiagnosticsOnly,
            format!("QUIC probe failed: {summary}"),
        )
        .with_tag("outcome", outcome.to_string()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quic_breakage_only_flags_failed_outcomes() {
        assert!(classify_quic_probe("quic_response", None).is_none());

        let classified = classify_quic_probe("quic_empty", Some("no datagram response")).expect("quic breakage");
        assert_eq!(classified.class, FailureClass::QuicBreakage);
        assert_eq!(classified.action, FailureAction::DiagnosticsOnly);
    }

    #[test]
    fn quic_probe_returns_none_for_initial_response() {
        assert!(classify_quic_probe("quic_initial_response", None).is_none());
    }

    #[test]
    fn quic_probe_uses_outcome_when_error_is_empty() {
        let f = classify_quic_probe("quic_timeout", Some("")).expect("should classify");
        assert!(f.evidence.summary.contains("quic_timeout"));
    }

    #[test]
    fn quic_probe_uses_error_when_present() {
        let f = classify_quic_probe("quic_timeout", Some("read timeout")).expect("should classify");
        assert!(f.evidence.summary.contains("read timeout"));
    }

    #[test]
    fn quic_probe_uses_outcome_when_error_is_none() {
        let f = classify_quic_probe("quic_timeout", None).expect("should classify");
        assert!(f.evidence.summary.contains("quic_timeout"));
        assert!(f.evidence.tags.iter().any(|t| t == "outcome=quic_timeout"));
    }
}
