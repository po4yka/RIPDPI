use std::time::Duration;

use ripdpi_config::{
    DETECT_CONNECT, DETECT_CONNECTION_FREEZE, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT,
    DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST, RuntimeConfig,
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FirstResponseExchangePolicy {
    pub host_autolearn_enabled: bool,
}

pub fn first_response_exchange_policy(config: &RuntimeConfig) -> FirstResponseExchangePolicy {
    FirstResponseExchangePolicy { host_autolearn_enabled: crate::model::config::host_autolearn_enabled(config) }
}

pub fn first_response_exchange_required<E>(
    config: &RuntimeConfig,
    mut trigger_supported: impl FnMut(u32) -> Result<bool, E>,
) -> Result<bool, E> {
    first_response_exchange_required_with(first_response_exchange_policy(config), &mut trigger_supported)
}

pub fn first_response_exchange_required_with<E>(
    policy: FirstResponseExchangePolicy,
    mut trigger_supported: impl FnMut(u32) -> Result<bool, E>,
) -> Result<bool, E> {
    for trigger in first_response_detection_flags() {
        if trigger_supported(*trigger)? {
            return Ok(true);
        }
    }
    Ok(policy.host_autolearn_enabled)
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
        FailureClass::DnsResolutionFailure => 0,
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
        FailureClass::TuicVersionUnsupported => 0,
        FailureClass::ShadowTlsVersionMismatch => 0,
        FailureClass::MasqueH3TcpUnsupported => 0,
        FailureClass::IpBlockSuspect => 0,
        FailureClass::ConfirmGoodDpiSuspected => 0,
    }
}

pub fn failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_response_exchange_required_accepts_supported_detection_trigger() {
        let mut config = RuntimeConfig::default();
        config.host_autolearn.enabled = false;

        let required = first_response_exchange_required::<()>(&config, |trigger| Ok(trigger == DETECT_HTTP_BLOCKPAGE))
            .expect("exchange projection");

        assert!(required);
    }

    #[test]
    fn first_response_exchange_required_falls_back_to_host_autolearn() {
        let mut config = RuntimeConfig::default();
        config.host_autolearn.enabled = true;

        let required = first_response_exchange_required::<()>(&config, |_| Ok(false)).expect("exchange projection");

        assert!(required);
    }

    #[test]
    fn first_response_exchange_policy_projects_host_autolearn() {
        let mut config = RuntimeConfig::default();
        config.host_autolearn.enabled = true;

        assert_eq!(
            first_response_exchange_policy(&config),
            FirstResponseExchangePolicy { host_autolearn_enabled: true },
        );
    }
}
