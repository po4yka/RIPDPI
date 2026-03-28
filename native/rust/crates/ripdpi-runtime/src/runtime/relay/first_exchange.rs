use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use crate::platform;
use ripdpi_config::{
    RuntimeConfig, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_SILENT_DROP, DETECT_TCP_RESET,
    DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST,
};
use ripdpi_failure_classifier::{
    classify_transport_error, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};

use super::super::routing::{classify_response_failure, runtime_supports_trigger};
use super::super::state::RuntimeState;
use super::tls_boundary::TlsRecordBoundaryTracker;

pub(super) enum FirstResponse {
    Forward(Vec<u8>, Option<u8>),
    Failure { failure: ClassifiedFailure, response_bytes: Option<Vec<u8>> },
    NoData,
}

pub(super) fn needs_first_exchange(state: &RuntimeState) -> io::Result<bool> {
    Ok(runtime_supports_trigger(state, DETECT_HTTP_LOCAT)?
        || runtime_supports_trigger(state, DETECT_HTTP_BLOCKPAGE)?
        || runtime_supports_trigger(state, DETECT_TLS_HANDSHAKE_FAILURE)?
        || runtime_supports_trigger(state, DETECT_TLS_ALERT)?
        || runtime_supports_trigger(state, DETECT_TCP_RESET)?
        || runtime_supports_trigger(state, DETECT_SILENT_DROP)?
        || runtime_supports_trigger(state, DETECT_DNS_TAMPER)?
        || state.config.host_autolearn.enabled)
}

pub(super) fn read_first_response(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    upstream: &mut TcpStream,
    config: &RuntimeConfig,
    request: &[u8],
) -> io::Result<FirstResponse> {
    let _ = platform::enable_recv_ttl(upstream);
    let mut collected = Vec::new();
    let mut chunk = vec![0u8; config.network.buffer_size.max(16_384)];
    let mut tls_partial = TlsRecordBoundaryTracker::for_first_response(request, config);
    let mut timeout_count = 0i32;
    let mut observed_server_ttl: Option<u8> = None;

    loop {
        let _ = upstream.set_read_timeout(first_response_timeout(config, &tls_partial));
        let read_result = if collected.is_empty() {
            platform::read_chunk_with_ttl(upstream, &mut chunk).map(|(n, ttl)| {
                if ttl.is_some() {
                    observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut chunk)
        };
        let result = match read_result {
            Ok(0) => Ok(FirstResponse::Failure {
                failure: ClassifiedFailure::new(
                    FailureClass::SilentDrop,
                    FailureStage::FirstResponse,
                    FailureAction::RetryWithMatchingGroup,
                    "upstream closed before first response",
                ),
                response_bytes: None,
            }),
            Ok(n) => {
                collected.extend_from_slice(&chunk[..n]);
                tls_partial.observe(&chunk[..n]);

                if tls_partial.waiting_for_tls_record() {
                    continue;
                }

                if let Some(failure) = classify_response_failure(state, target, request, &collected, host) {
                    Ok(FirstResponse::Failure { failure, response_bytes: Some(collected) })
                } else {
                    Ok(FirstResponse::Forward(collected, observed_server_ttl))
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if tls_partial.waiting_for_tls_record() {
                    timeout_count += 1;
                    if timeout_count >= timeout_count_limit(config) {
                        Ok(FirstResponse::Failure {
                            failure: ClassifiedFailure::new(
                                FailureClass::SilentDrop,
                                FailureStage::FirstResponse,
                                FailureAction::RetryWithMatchingGroup,
                                "partial TLS response timed out",
                            ),
                            response_bytes: None,
                        })
                    } else {
                        continue;
                    }
                } else if config.timeouts.timeout_ms != 0 {
                    Ok(FirstResponse::Failure {
                        failure: classify_transport_error(FailureStage::FirstResponse, &err),
                        response_bytes: None,
                    })
                } else {
                    Ok(FirstResponse::NoData)
                }
            }
            Err(err)
                if matches!(
                    err.kind(),
                    io::ErrorKind::ConnectionReset
                        | io::ErrorKind::ConnectionAborted
                        | io::ErrorKind::BrokenPipe
                        | io::ErrorKind::ConnectionRefused
                        | io::ErrorKind::InvalidInput
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::HostUnreachable
                ) =>
            {
                Ok(FirstResponse::Failure {
                    failure: classify_transport_error(FailureStage::FirstResponse, &err),
                    response_bytes: None,
                })
            }
            Err(err) => Err(err),
        };
        let _ = upstream.set_read_timeout(None);
        return result;
    }
}

pub(super) fn first_response_timeout(
    config: &RuntimeConfig,
    tls_partial: &TlsRecordBoundaryTracker,
) -> Option<Duration> {
    if tls_partial.active() {
        Some(Duration::from_millis(config.timeouts.partial_timeout_ms as u64))
    } else if config.timeouts.timeout_ms != 0 {
        Some(Duration::from_millis(config.timeouts.timeout_ms as u64))
    } else if config.groups.iter().any(|group| {
        group.matches.detect
            & (DETECT_HTTP_LOCAT
                | DETECT_HTTP_BLOCKPAGE
                | DETECT_TLS_HANDSHAKE_FAILURE
                | DETECT_TLS_ALERT
                | DETECT_TORST)
            != 0
    }) {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

pub(super) fn timeout_count_limit(config: &RuntimeConfig) -> i32 {
    config.timeouts.timeout_count_limit.max(1)
}

#[cfg(test)]
pub(super) fn response_trigger_supported(config: &RuntimeConfig, trigger: ripdpi_session::TriggerEvent) -> bool {
    use ripdpi_config::DETECT_CONNECT;

    let flag = match trigger {
        ripdpi_session::TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        ripdpi_session::TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        ripdpi_session::TriggerEvent::Connect => DETECT_CONNECT,
        ripdpi_session::TriggerEvent::Torst => DETECT_TORST,
    };
    config.groups.iter().any(|group| group.matches.detect & flag != 0)
}
