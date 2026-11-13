use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn failure_penalizes_strategy(failure: &RuntimeClassifiedFailure) -> bool {
        runtime_failure_penalizes_strategy(failure)
    }
    #[cfg(test)]
    pub(in crate::runtime) fn failure_trigger_mask(failure: &RuntimeClassifiedFailure) -> u32 {
        runtime_failure_trigger_mask(failure)
    }
    #[cfg(test)]
    pub(in crate::runtime) fn trigger_flag(trigger: RuntimeTriggerEvent) -> u32 {
        runtime_response_trigger_flag(trigger)
    }
    #[cfg(test)]
    pub(in crate::runtime) fn response_trigger_supported(config: &RuntimeConfig, trigger: RuntimeTriggerEvent) -> bool {
        runtime_response_trigger_supported(config, trigger)
    }
    pub(in crate::runtime) fn udp_flow_timeout_failure() -> Option<RuntimeClassifiedFailure> {
        runtime_classify_quic_probe("quic_timeout", Some("UDP flow expired before first response"))
    }
    pub(in crate::runtime) fn silent_drop_failure_class() -> RuntimeFailureClass {
        RuntimeFailureClass::SilentDrop
    }
    pub(in crate::runtime) fn connect_failure_trigger() -> u32 {
        DETECT_CONNECT
    }
    pub(in crate::runtime) fn classify_connect_transport_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_transport_error(RuntimeFailureStage::Connect, source)
    }
    pub(in crate::runtime) fn classify_first_response_transport_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_transport_error(RuntimeFailureStage::FirstResponse, source)
    }
    pub(in crate::runtime) fn classify_first_response_closed_before_response() -> RuntimeClassifiedFailure {
        runtime_classify_first_response_closed_before_response()
    }
    pub(in crate::runtime) fn classify_first_response_partial_tls_timeout() -> RuntimeClassifiedFailure {
        runtime_classify_first_response_partial_tls_timeout()
    }
    pub(in crate::runtime) fn classify_first_write_failure(error: &OutboundSendError) -> RuntimeClassifiedFailure {
        Self::classify_first_write_failure_with_bound(error, false)
    }
    /// `send_bounded` is true when the upstream carries the deferred-connect send timeout,
    /// the only source of a `WouldBlock` that means a silent drop.
    pub(in crate::runtime) fn classify_first_write_failure_with_bound(
        error: &OutboundSendError,
        send_bounded: bool,
    ) -> RuntimeClassifiedFailure {
        let mut failure = match error {
            OutboundSendError::Transport { source, .. } => first_write_transport_failure(source, send_bounded)
                .with_tag("bytesCommitted", error.bytes_committed().to_string()),
            OutboundSendError::StrategyExecution {
                action,
                strategy_family,
                fallback,
                bytes_committed,
                source_errno,
                source,
                ..
            } => {
                let mut failure = runtime_classify_strategy_execution_failure(
                    RuntimeFailureStage::FirstWrite,
                    action,
                    source.kind(),
                    *source_errno,
                    error.to_string(),
                )
                .unwrap_or_else(|| first_write_transport_failure(source, send_bounded));
                failure = failure.with_tag("strategyFamily", (*strategy_family).to_string());
                failure = failure.with_tag("bytesCommitted", bytes_committed.to_string());
                if let Some(fallback_family) = fallback {
                    failure = failure.with_tag("fallback", (*fallback_family).to_string());
                }
                if error.payload_commit_uncertain() {
                    failure = failure.with_tag("payloadCommitUncertain", "true");
                }
                failure
            }
        };
        // A closed in-flight write can hide accepted bytes, including a TFO payload.
        if error.bytes_committed() > 0 || error.payload_commit_uncertain() || error.kind() == io::ErrorKind::BrokenPipe
        {
            failure.action = RuntimeFailureAction::SurfaceOnly;
        }
        failure
    }
    pub(in crate::runtime) fn classify_response_failure(
        &self,
        target: SocketAddr,
        request: &[u8],
        response: &[u8],
        host: Option<&str>,
    ) -> Option<RuntimeClassifiedFailure> {
        let answer_set = if runtime_response_requires_dns_tampering_evidence(request, response) {
            host.and_then(|value| self.encrypted_dns_ip_answers_for_host(value).ok())
        } else {
            None
        };
        let dns_evidence = host.zip(answer_set.as_ref()).map(|(value, answers)| RuntimeDnsTamperingEvidence {
            host: value,
            target_ip: target.ip(),
            answers: &answers.answers,
            resolver_label: &answers.label,
        });
        runtime_classify_response_failure(request, response, dns_evidence)
    }
}

/// A blocking upstream socket returns `WouldBlock` for the send timeout that bounds a
/// deferred TCP Fast Open handshake; that is a silent drop. Without that bound the
/// error is not remapped.
fn first_write_transport_failure(source: &io::Error, send_bounded: bool) -> RuntimeClassifiedFailure {
    if send_bounded && source.kind() == io::ErrorKind::WouldBlock {
        let timed_out = io::Error::new(io::ErrorKind::TimedOut, source.to_string());
        return runtime_classify_transport_error(RuntimeFailureStage::FirstWrite, &timed_out);
    }
    runtime_classify_transport_error(RuntimeFailureStage::FirstWrite, source)
}

#[cfg(test)]
mod first_write_tests {
    use super::*;

    #[test]
    fn send_timeout_on_first_write_is_a_silent_drop() {
        let failure = first_write_transport_failure(&io::Error::from(io::ErrorKind::WouldBlock), true);
        assert_eq!(failure.class, RuntimeFailureClass::SilentDrop);
        assert_eq!(failure.stage, RuntimeFailureStage::FirstWrite);
    }

    #[test]
    fn would_block_without_the_send_bound_is_not_a_silent_drop() {
        let failure = first_write_transport_failure(&io::Error::from(io::ErrorKind::WouldBlock), false);
        assert_ne!(failure.class, RuntimeFailureClass::SilentDrop);
    }
}
