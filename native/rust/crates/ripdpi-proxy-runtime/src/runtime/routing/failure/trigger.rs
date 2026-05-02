use ripdpi_config::{
    DETECT_CONNECT, DETECT_CONNECTION_FREEZE, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT,
    DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE,
};
use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass};

use crate::runtime::routing::policy::runtime_supports_trigger;
use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
    match failure.class {
        FailureClass::DnsTampering => DETECT_DNS_TAMPER,
        FailureClass::TcpReset => DETECT_TCP_RESET,
        FailureClass::SilentDrop => DETECT_SILENT_DROP,
        FailureClass::TlsAlert => DETECT_TLS_ALERT,
        FailureClass::HttpBlockpage => DETECT_HTTP_BLOCKPAGE,
        FailureClass::QuicBreakage => 0,
        FailureClass::Redirect => DETECT_HTTP_LOCAT,
        FailureClass::TlsHandshakeFailure => DETECT_TLS_HANDSHAKE_FAILURE,
        FailureClass::ConnectFailure => DETECT_CONNECT,
        FailureClass::StrategyExecutionFailure => DETECT_CONNECT,
        FailureClass::ConnectionFreeze => DETECT_CONNECTION_FREEZE,
        FailureClass::Unknown => 0,
        // Capability-skipped runs were never actually emitted; they emit no
        // wire-visible block signals and must not trigger block detection.
        FailureClass::CapabilitySkipped => 0,
    }
}

pub(super) fn route_advance_trigger(state: &RuntimeState, failure: &ClassifiedFailure) -> std::io::Result<Option<u32>> {
    let trigger = failure_trigger_mask(failure);
    if failure.action != FailureAction::RetryWithMatchingGroup
        || trigger == 0
        || !runtime_supports_trigger(state, trigger)?
    {
        return Ok(None);
    }
    Ok(Some(trigger))
}

pub(in crate::runtime) fn failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
    matches!(
        failure.class,
        FailureClass::TcpReset
            | FailureClass::SilentDrop
            | FailureClass::TlsAlert
            | FailureClass::HttpBlockpage
            | FailureClass::Redirect
            | FailureClass::TlsHandshakeFailure
            | FailureClass::ConnectionFreeze
    )
}
