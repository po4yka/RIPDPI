use std::io;

use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub fn classify_strategy_execution_failure(
    stage: FailureStage,
    action: &str,
    kind: io::ErrorKind,
    errno: Option<i32>,
    summary: impl Into<String>,
) -> Option<ClassifiedFailure> {
    if stage != FailureStage::FirstWrite {
        return None;
    }
    if !is_strategy_execution_kind(kind) && !errno.is_some_and(is_strategy_execution_errno) {
        return None;
    }
    Some(
        ClassifiedFailure::new(
            FailureClass::StrategyExecutionFailure,
            stage,
            FailureAction::RetryWithMatchingGroup,
            summary,
        )
        .with_tag("action", action.to_string())
        .with_tag("kind", format!("{kind:?}"))
        .with_tag("errno", errno.map_or_else(|| "none".to_string(), |value| value.to_string())),
    )
}

fn is_strategy_execution_errno(errno: i32) -> bool {
    errno == libc::EINVAL
        || errno == libc::ENOPROTOOPT
        || errno == libc::EOPNOTSUPP
        || errno == libc::ENOTSUP
        || errno == libc::EPERM
        || errno == libc::EACCES
        || errno == libc::EROFS
}

fn is_strategy_execution_kind(kind: io::ErrorKind) -> bool {
    kind == io::ErrorKind::Unsupported
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strategy_execution_failures_retry_only_for_first_write_capability_errors() {
        let failure = classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "set_ttl",
            io::ErrorKind::InvalidInput,
            Some(libc::EINVAL),
            "desync action=set_ttl: Invalid argument (os error 22)",
        )
        .expect("first-write EINVAL should retry");

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.summary.contains("desync action=set_ttl"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "action=set_ttl"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "kind=InvalidInput"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == &format!("errno={}", libc::EINVAL)));
    }

    #[test]
    fn strategy_execution_failures_retry_for_unsupported_desync_actions() {
        let failure = classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "await_writable",
            io::ErrorKind::Unsupported,
            None,
            "desync action=await_writable: only supported on Linux/Android",
        )
        .expect("unsupported first-write action should retry");

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.tags.iter().any(|tag| tag == "action=await_writable"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "kind=Unsupported"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "errno=none"));
    }

    #[test]
    fn strategy_execution_failures_retry_for_android_read_only_ttl_errors() {
        let failure = classify_strategy_execution_failure(
            FailureStage::FirstWrite,
            "write_disorder",
            io::ErrorKind::ReadOnlyFilesystem,
            Some(libc::EROFS),
            "desync action=write_disorder fallback=split: Read-only file system (os error 30)",
        )
        .expect("first-write EROFS should retry");

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.tags.iter().any(|tag| tag == "action=write_disorder"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "kind=ReadOnlyFilesystem"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == &format!("errno={}", libc::EROFS)));
    }

    #[test]
    fn strategy_execution_failures_ignore_other_stages_and_non_capability_errors() {
        assert!(
            classify_strategy_execution_failure(
                FailureStage::Connect,
                "set_ttl",
                io::ErrorKind::InvalidInput,
                Some(libc::EINVAL),
                "desync action=set_ttl: Invalid argument (os error 22)",
            )
            .is_none()
        );
        assert!(
            classify_strategy_execution_failure(
                FailureStage::FirstWrite,
                "set_ttl",
                io::ErrorKind::ConnectionReset,
                Some(libc::ECONNRESET),
                "desync action=set_ttl: Connection reset by peer (os error 54)",
            )
            .is_none()
        );
        assert!(
            classify_strategy_execution_failure(
                FailureStage::FirstWrite,
                "set_ttl",
                io::ErrorKind::ConnectionReset,
                None,
                "desync action=set_ttl: Connection reset by peer",
            )
            .is_none()
        );
    }

    #[test]
    fn strategy_execution_recognizes_all_capability_errnos() {
        let errnos =
            [libc::EINVAL, libc::ENOPROTOOPT, libc::EOPNOTSUPP, libc::ENOTSUP, libc::EPERM, libc::EACCES, libc::EROFS];
        for errno in errnos {
            let f = classify_strategy_execution_failure(
                FailureStage::FirstWrite,
                "test_action",
                io::ErrorKind::Other,
                Some(errno),
                format!("errno {errno}"),
            )
            .unwrap_or_else(|| panic!("errno {errno} should be classified as strategy execution failure"));
            assert_eq!(f.class, FailureClass::StrategyExecutionFailure);
        }
    }

    #[test]
    fn strategy_execution_rejects_non_capability_errno_without_matching_kind() {
        assert!(
            classify_strategy_execution_failure(
                FailureStage::FirstWrite,
                "test_action",
                io::ErrorKind::Other,
                Some(libc::ECONNRESET),
                "not a capability error",
            )
            .is_none()
        );
    }

    #[test]
    fn strategy_execution_rejects_no_errno_and_non_matching_kind() {
        assert!(
            classify_strategy_execution_failure(
                FailureStage::FirstWrite,
                "test_action",
                io::ErrorKind::Other,
                None,
                "no errno, no matching kind",
            )
            .is_none()
        );
    }
}
