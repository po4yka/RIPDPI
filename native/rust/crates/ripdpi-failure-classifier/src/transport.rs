use std::io;

use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub fn classify_transport_error(stage: FailureStage, error: &io::Error) -> ClassifiedFailure {
    let kind = error.kind();
    match kind {
        io::ErrorKind::ConnectionReset
        | io::ErrorKind::ConnectionAborted
        | io::ErrorKind::BrokenPipe
        | io::ErrorKind::UnexpectedEof => ClassifiedFailure::new(
            FailureClass::TcpReset,
            stage,
            FailureAction::RetryWithMatchingGroup,
            error.to_string(),
        )
        .with_tag("kind", format!("{kind:?}")),
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            error.to_string(),
        )
        .with_tag("kind", format!("{kind:?}")),
        io::ErrorKind::ConnectionRefused
        | io::ErrorKind::HostUnreachable
        | io::ErrorKind::NetworkUnreachable
        | io::ErrorKind::NotConnected
        | io::ErrorKind::AddrNotAvailable
        | io::ErrorKind::ReadOnlyFilesystem => ClassifiedFailure::new(
            FailureClass::ConnectFailure,
            stage,
            FailureAction::RetryWithMatchingGroup,
            error.to_string(),
        )
        .with_tag("kind", format!("{kind:?}")),
        _ => ClassifiedFailure::new(FailureClass::Unknown, stage, FailureAction::SurfaceOnly, error.to_string())
            .with_tag("kind", format!("{kind:?}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_errors_distinguish_reset_and_timeout() {
        let reset = io::Error::new(io::ErrorKind::ConnectionReset, "reset by peer");
        let timeout = io::Error::new(io::ErrorKind::TimedOut, "timed out");

        let reset_failure = classify_transport_error(FailureStage::Connect, &reset);
        let timeout_failure = classify_transport_error(FailureStage::Connect, &timeout);

        assert_eq!(reset_failure.class, FailureClass::TcpReset);
        assert_eq!(timeout_failure.class, FailureClass::SilentDrop);
    }

    #[test]
    fn transport_errors_classify_connect_failures() {
        let kinds = [
            io::ErrorKind::ConnectionRefused,
            io::ErrorKind::HostUnreachable,
            io::ErrorKind::NetworkUnreachable,
            io::ErrorKind::NotConnected,
            io::ErrorKind::AddrNotAvailable,
        ];
        for kind in kinds {
            let err = io::Error::new(kind, "test");
            let f = classify_transport_error(FailureStage::Connect, &err);
            assert_eq!(f.class, FailureClass::ConnectFailure, "expected ConnectFailure for {kind:?}");
            assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
        }
    }

    #[test]
    fn transport_errors_classify_unknown_kinds() {
        let err = io::Error::new(io::ErrorKind::PermissionDenied, "denied");
        let f = classify_transport_error(FailureStage::Connect, &err);
        assert_eq!(f.class, FailureClass::Unknown);
        assert_eq!(f.action, FailureAction::SurfaceOnly);
    }

    #[test]
    fn invalid_input_transport_errors_remain_unknown_outside_desync_strategy_path() {
        for stage in [FailureStage::Connect, FailureStage::FirstResponse] {
            let err = io::Error::from_raw_os_error(libc::EINVAL);
            let failure = classify_transport_error(stage, &err);
            assert_eq!(failure.class, FailureClass::Unknown);
            assert_eq!(failure.action, FailureAction::SurfaceOnly);
        }
    }

    #[test]
    fn transport_errors_classify_all_reset_subtypes() {
        let kinds = [
            io::ErrorKind::ConnectionReset,
            io::ErrorKind::ConnectionAborted,
            io::ErrorKind::BrokenPipe,
            io::ErrorKind::UnexpectedEof,
        ];
        for kind in kinds {
            let err = io::Error::new(kind, "test");
            let f = classify_transport_error(FailureStage::FirstWrite, &err);
            assert_eq!(f.class, FailureClass::TcpReset, "expected TcpReset for {kind:?}");
            assert_eq!(f.action, FailureAction::RetryWithMatchingGroup);
        }
    }

    #[test]
    fn transport_errors_classify_would_block_as_silent_drop() {
        let err = io::Error::new(io::ErrorKind::WouldBlock, "would block");
        let f = classify_transport_error(FailureStage::Connect, &err);
        assert_eq!(f.class, FailureClass::SilentDrop);
    }

    #[test]
    fn transport_error_preserves_stage() {
        let err = io::Error::new(io::ErrorKind::ConnectionReset, "reset");
        for stage in [FailureStage::Connect, FailureStage::FirstWrite, FailureStage::FirstResponse] {
            let f = classify_transport_error(stage, &err);
            assert_eq!(f.stage, stage, "stage should be preserved");
        }
    }

    #[test]
    fn transport_error_records_error_message_in_summary() {
        let err = io::Error::new(io::ErrorKind::ConnectionReset, "reset by peer");
        let f = classify_transport_error(FailureStage::Connect, &err);
        assert!(f.evidence.summary.contains("reset by peer"));
    }
}
