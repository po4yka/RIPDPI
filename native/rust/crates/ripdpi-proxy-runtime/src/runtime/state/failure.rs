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
        match error {
            OutboundSendError::Transport { source, .. } => {
                runtime_classify_transport_error(RuntimeFailureStage::FirstWrite, source)
            }
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
                .unwrap_or_else(|| runtime_classify_transport_error(RuntimeFailureStage::FirstWrite, source));
                failure = failure.with_tag("strategyFamily", (*strategy_family).to_string());
                failure = failure.with_tag("bytesCommitted", bytes_committed.to_string());
                if let Some(fallback_family) = fallback {
                    failure = failure.with_tag("fallback", (*fallback_family).to_string());
                }
                if *bytes_committed > 0 {
                    failure.action = RuntimeFailureAction::SurfaceOnly;
                }
                failure
            }
        }
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
