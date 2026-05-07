use std::time::Duration;

use ripdpi_config::{
    RuntimeConfig, DETECT_CONNECT, DETECT_CONNECTION_FREEZE, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE,
    DETECT_HTTP_LOCAT, DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE,
    DETECT_TORST,
};
use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass};
use ripdpi_session::TriggerEvent;

const FIRST_RESPONSE_DETECTION_FLAGS: &[u32] = &[
    DETECT_HTTP_LOCAT,
    DETECT_HTTP_BLOCKPAGE,
    DETECT_TLS_HANDSHAKE_FAILURE,
    DETECT_TLS_ALERT,
    DETECT_TCP_RESET,
    DETECT_SILENT_DROP,
    DETECT_DNS_TAMPER,
];

const FIRST_RESPONSE_TIMEOUT_FLAGS: &[u32] =
    &[DETECT_HTTP_LOCAT, DETECT_HTTP_BLOCKPAGE, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TLS_ALERT, DETECT_TORST];

pub fn response_trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => DETECT_TORST,
    }
}

pub fn response_trigger_supported(config: &RuntimeConfig, trigger: TriggerEvent) -> bool {
    let flag = response_trigger_flag(trigger);
    config.groups.iter().any(|group| group.matches.detect & flag != 0)
}

pub fn first_response_detection_flags() -> &'static [u32] {
    FIRST_RESPONSE_DETECTION_FLAGS
}

pub fn first_response_timeout(config: &RuntimeConfig, tls_partial_active: bool) -> Option<Duration> {
    if tls_partial_active {
        Some(Duration::from_millis(config.timeouts.partial_timeout_ms as u64))
    } else if config.timeouts.timeout_ms != 0 {
        Some(Duration::from_millis(config.timeouts.timeout_ms as u64))
    } else if config
        .groups
        .iter()
        .any(|group| FIRST_RESPONSE_TIMEOUT_FLAGS.iter().any(|flag| group.matches.detect & *flag != 0))
    {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

pub fn timeout_count_limit(config: &RuntimeConfig) -> i32 {
    config.timeouts.timeout_count_limit.max(1)
}

pub fn failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
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
        FailureClass::CapabilitySkipped => 0,
    }
}
