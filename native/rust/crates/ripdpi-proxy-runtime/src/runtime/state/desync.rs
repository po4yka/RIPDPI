use super::*;
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    AttemptCorrelationId, DesyncExecutionDisposition, DesyncExecutionEvidence, DesyncExecutionReceipt,
    DesyncExecutionTransport, DesyncFallbackReason, DesyncOffsetMarkerBase, DesyncStrategyFamily, DesyncTerminalReason,
    DesyncTlsPreludeEvidence, DesyncTlsPreludeKind,
};

impl RuntimeState {
    pub(in crate::runtime) fn send_tcp_desync_payload(
        &self,
        writer: &mut TcpStream,
        request: DesyncSendRequest<'_>,
    ) -> Result<OutboundSendOutcome, OutboundSendError> {
        send_tcp_desync_payload(writer, self.tcp_desync_execution_context(), request)
    }
    pub(in crate::runtime) fn record_desync_execution_evidence(
        &self,
        attempt_token: Option<&AttemptCorrelationId>,
        receipt: &TcpExecutionReceipt,
    ) -> bool {
        let Some(control) = &self.control else {
            return false;
        };
        let Some(attempt_token) = attempt_token.cloned() else {
            return false;
        };
        let Some(connection_ordinal) = control.next_desync_connection_ordinal(&attempt_token) else {
            return false;
        };
        let Some(receipt) = runtime_desync_execution_receipt(receipt) else {
            return false;
        };
        let Some(evidence) = DesyncExecutionEvidence::new(
            control.desync_execution_generation(),
            attempt_token,
            connection_ordinal,
            receipt,
        ) else {
            return false;
        };
        control.record_desync_execution_evidence(evidence)
    }
    pub(in crate::runtime) fn record_udp_desync_execution_evidence(
        &self,
        attempt_token: Option<&AttemptCorrelationId>,
        execution_family: Option<&str>,
        outcome: UdpExecutionOutcome,
    ) -> bool {
        self.record_udp_desync_outcome(attempt_token, execution_family, outcome, None)
    }
    pub(in crate::runtime) fn record_udp_desync_failure_evidence(
        &self,
        attempt_token: Option<&AttemptCorrelationId>,
        execution_family: Option<&str>,
        outcome: UdpExecutionOutcome,
    ) -> bool {
        self.record_udp_desync_outcome(
            attempt_token,
            execution_family,
            outcome,
            Some(DesyncTerminalReason::StrategyExecution),
        )
    }
    fn record_udp_desync_outcome(
        &self,
        attempt_token: Option<&AttemptCorrelationId>,
        execution_family: Option<&str>,
        outcome: UdpExecutionOutcome,
        terminal_reason: Option<DesyncTerminalReason>,
    ) -> bool {
        let Some(control) = &self.control else {
            return false;
        };
        let Some(attempt_token) = attempt_token.cloned() else {
            return false;
        };
        let Some(configured_family) = execution_family.and_then(runtime_udp_desync_strategy_family) else {
            return false;
        };
        let fallback_reason = outcome.fallback_reason.map(|_| DesyncFallbackReason::StrategyFamilyFallback);
        let applied = terminal_reason.is_none()
            && outcome.technique_actions_completed > 0
            && outcome.real_writes_committed > 0
            && outcome.payload_bytes_committed > 0
            && outcome.completed_actions == outcome.attempted_actions
            && fallback_reason.is_none();
        let disposition = if terminal_reason.is_some() {
            DesyncExecutionDisposition::ExecutionFailed
        } else if applied {
            DesyncExecutionDisposition::Applied
        } else if fallback_reason.is_some() {
            DesyncExecutionDisposition::PlanFailedPlainFallback
        } else {
            DesyncExecutionDisposition::ActivationSkipped
        };
        let effective_family = applied.then_some(configured_family);
        let Some(receipt) = DesyncExecutionReceipt::try_new(
            DesyncExecutionTransport::Udp,
            disposition,
            Some(configured_family),
            effective_family,
            None,
            None,
            None,
            1,
            outcome.attempted_actions.min(u16::MAX as usize) as u16,
            outcome.completed_actions.min(u16::MAX as usize) as u16,
            outcome.real_writes_committed,
            0,
            outcome.payload_bytes_committed,
            false,
            None,
            outcome.ipv6_extension_profile,
            fallback_reason,
            terminal_reason,
        ) else {
            return false;
        };
        let Some(connection_ordinal) = control.next_desync_connection_ordinal(&attempt_token) else {
            return false;
        };
        let Some(evidence) = DesyncExecutionEvidence::new(
            control.desync_execution_generation(),
            attempt_token,
            connection_ordinal,
            receipt,
        ) else {
            return false;
        };
        control.record_desync_execution_evidence(evidence)
    }
    fn tcp_desync_execution_context(&self) -> TcpDesyncExecutionContext<'_> {
        TcpDesyncExecutionContext {
            executor: &self.tcp_desync_executor,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
            ttl_unavailable: &self.ttl_unavailable,
            pcap_hook: self.pcap_hook.as_ref(),
        }
    }
    fn plan_udp_desync_actions(&self, request: UdpDesyncPlanRequest<'_>) -> io::Result<Vec<UdpDesyncAction>> {
        plan_udp_actions_for_runtime(self.udp_desync_plan_context(), request)
    }
    pub(in crate::runtime) fn plan_udp_flow_actions(
        &self,
        group_index: usize,
        payload: &[u8],
        progress: RuntimeOutboundProgress,
        host: Option<&str>,
        target: SocketAddr,
        default_ttl: u8,
    ) -> io::Result<Vec<UdpDesyncAction>> {
        self.plan_udp_desync_actions(UdpDesyncPlanRequest {
            group_index,
            payload,
            progress: progress.into_adapter(),
            host,
            target,
            default_ttl,
        })
    }
    pub(in crate::runtime) fn execute_udp_desync_actions(
        upstream: &UdpSocket,
        target: SocketAddr,
        packet_settings: RuntimeUdpPacketSettings,
        protect_path: Option<&str>,
        socks_udp_frame: bool,
        actions: &[UdpDesyncAction],
        logical_payload: &[u8],
    ) -> Result<UdpExecutionOutcome, UdpExecutionError> {
        execute_udp_actions(
            UdpActionExecContext {
                upstream,
                target,
                default_ttl: packet_settings.default_ttl,
                protect_path,
                ip_id_mode: packet_settings.ip_id_mode,
                socks_udp_frame,
            },
            actions,
            logical_payload,
        )
    }
    fn udp_desync_plan_context(&self) -> UdpDesyncPlanContext<'_> {
        UdpDesyncPlanContext {
            planner: &self.udp_desync_planner,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
        }
    }
}

fn runtime_desync_execution_receipt(receipt: &TcpExecutionReceipt) -> Option<DesyncExecutionReceipt> {
    let tls_prelude = runtime_tls_prelude_evidence(receipt)?;
    DesyncExecutionReceipt::try_new(
        DesyncExecutionTransport::Tcp,
        runtime_desync_execution_disposition(receipt.disposition),
        receipt.configured_family.map(runtime_desync_strategy_family),
        receipt.effective_family.map(runtime_desync_strategy_family),
        receipt.marker_base.and_then(runtime_desync_marker_base),
        receipt.marker_delta,
        receipt.resolved_offset.map(|value| value.min(u16::MAX as usize) as u16),
        receipt.planned_steps.min(u16::MAX as usize) as u16,
        receipt.attempted_actions.min(u16::MAX as usize) as u16,
        receipt.completed_actions.min(u16::MAX as usize) as u16,
        receipt.real_writes_committed,
        receipt.completed_awaits,
        receipt.payload_bytes_committed,
        receipt.tls_record_prelude_applied,
        tls_prelude,
        None,
        receipt.fallback_reason.and_then(runtime_desync_fallback_reason),
        receipt.terminal_reason.and_then(runtime_desync_terminal_reason),
    )
}

fn runtime_tls_prelude_evidence(receipt: &TcpExecutionReceipt) -> Option<Option<DesyncTlsPreludeEvidence>> {
    if receipt.tls_prelude_configured_count == 0 {
        return Some(None);
    }
    DesyncTlsPreludeEvidence::try_new(
        receipt.tls_prelude_configured_count.min(u16::MAX as usize) as u16,
        receipt.tls_prelude_applied_count.min(u16::MAX as usize) as u16,
        receipt.tls_prelude_kind_token().and_then(|kind| match kind {
            "tlsrec" => Some(DesyncTlsPreludeKind::TlsRec),
            "tlsrandrec" => Some(DesyncTlsPreludeKind::TlsRandRec),
            _ => None,
        }),
        receipt.tls_prelude_marker_base.and_then(runtime_desync_marker_base),
        receipt.tls_prelude_marker_delta,
        receipt.tls_prelude_resolved_offset.map(|value| value.min(u16::MAX as usize) as u16),
    )
    .map(Some)
}

fn runtime_desync_execution_disposition(disposition: TcpExecutionDisposition) -> DesyncExecutionDisposition {
    match disposition {
        TcpExecutionDisposition::Applied => DesyncExecutionDisposition::Applied,
        TcpExecutionDisposition::ActivationSkipped => DesyncExecutionDisposition::ActivationSkipped,
        TcpExecutionDisposition::PlanFailedPlainFallback => DesyncExecutionDisposition::PlanFailedPlainFallback,
        TcpExecutionDisposition::ExecutionFailed => DesyncExecutionDisposition::ExecutionFailed,
        _ => DesyncExecutionDisposition::ExecutionFailed,
    }
}

fn runtime_desync_strategy_family(family: TcpStrategyFamily) -> DesyncStrategyFamily {
    use TcpStrategyFamily as Source;
    match family {
        Source::Split => DesyncStrategyFamily::Split,
        Source::TlsRecordSplit => DesyncStrategyFamily::TlsRecordSplit,
        Source::SeqOverlap => DesyncStrategyFamily::SeqOverlap,
        Source::TlsRecordSeqOverlap => DesyncStrategyFamily::TlsRecordSeqOverlap,
        Source::MultiDisorder => DesyncStrategyFamily::MultiDisorder,
        Source::TlsRecordMultiDisorder => DesyncStrategyFamily::TlsRecordMultiDisorder,
        Source::Disorder => DesyncStrategyFamily::Disorder,
        Source::OutOfBand => DesyncStrategyFamily::OutOfBand,
        Source::DisorderedOutOfBand => DesyncStrategyFamily::DisorderedOutOfBand,
        Source::Fake => DesyncStrategyFamily::Fake,
        Source::FakeSplit => DesyncStrategyFamily::FakeSplit,
        Source::FakeDisorder => DesyncStrategyFamily::FakeDisorder,
        Source::HostFake => DesyncStrategyFamily::HostFake,
        Source::IpFragment2 => DesyncStrategyFamily::IpFragment2,
        Source::FakeRst => DesyncStrategyFamily::FakeRst,
        Source::TlsRecord => DesyncStrategyFamily::TlsRecord,
        Source::Unknown => DesyncStrategyFamily::Unknown,
        _ => DesyncStrategyFamily::Unknown,
    }
}

fn runtime_udp_desync_strategy_family(family: &str) -> Option<DesyncStrategyFamily> {
    match family {
        "quic_burst" => Some(DesyncStrategyFamily::QuicBurst),
        "quic_sni_split" => Some(DesyncStrategyFamily::QuicSniSplit),
        "quic_fake_version" => Some(DesyncStrategyFamily::QuicFakeVersion),
        "quic_crypto_split" => Some(DesyncStrategyFamily::QuicCryptoSplit),
        "quic_padding_ladder" => Some(DesyncStrategyFamily::QuicPaddingLadder),
        "quic_version_negotiation_decoy" => Some(DesyncStrategyFamily::QuicVersionNegotiationDecoy),
        "quic_multi_initial_realistic" => Some(DesyncStrategyFamily::QuicMultiInitialRealistic),
        "quic_dummy_prepend" => Some(DesyncStrategyFamily::QuicDummyPrepend),
        "quic_ipfrag2" => Some(DesyncStrategyFamily::QuicIpFragment2),
        _ => None,
    }
}

fn runtime_desync_marker_base(base: TcpOffsetMarkerBase) -> Option<DesyncOffsetMarkerBase> {
    use TcpOffsetMarkerBase as Source;
    match base {
        Source::Absolute => Some(DesyncOffsetMarkerBase::Absolute),
        Source::PayloadEnd => Some(DesyncOffsetMarkerBase::PayloadEnd),
        Source::PayloadMid => Some(DesyncOffsetMarkerBase::PayloadMid),
        Source::PayloadRand => Some(DesyncOffsetMarkerBase::PayloadRand),
        Source::Host => Some(DesyncOffsetMarkerBase::Host),
        Source::EndHost => Some(DesyncOffsetMarkerBase::EndHost),
        Source::HostMid => Some(DesyncOffsetMarkerBase::HostMid),
        Source::HostRand => Some(DesyncOffsetMarkerBase::HostRand),
        Source::Sld => Some(DesyncOffsetMarkerBase::Sld),
        Source::MidSld => Some(DesyncOffsetMarkerBase::MidSld),
        Source::EndSld => Some(DesyncOffsetMarkerBase::EndSld),
        Source::Method => Some(DesyncOffsetMarkerBase::Method),
        Source::ExtLen => Some(DesyncOffsetMarkerBase::ExtLen),
        Source::EchExt => Some(DesyncOffsetMarkerBase::EchExt),
        Source::SniExt => Some(DesyncOffsetMarkerBase::SniExt),
        Source::Adaptive => Some(DesyncOffsetMarkerBase::Adaptive),
        _ => None,
    }
}

fn runtime_desync_fallback_reason(reason: TcpFallbackReason) -> Option<DesyncFallbackReason> {
    match reason {
        TcpFallbackReason::AndroidTtlUnavailable => Some(DesyncFallbackReason::AndroidTtlUnavailable),
        TcpFallbackReason::StrategyFamilyFallback => Some(DesyncFallbackReason::StrategyFamilyFallback),
        _ => None,
    }
}

fn runtime_desync_terminal_reason(reason: TcpTerminalReason) -> Option<DesyncTerminalReason> {
    match reason {
        TcpTerminalReason::Transport => Some(DesyncTerminalReason::Transport),
        TcpTerminalReason::StrategyExecution => Some(DesyncTerminalReason::StrategyExecution),
        TcpTerminalReason::Planning => Some(DesyncTerminalReason::Planning),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
    use ripdpi_proxy_runtime_adapter::model::runtime_api::DesyncUdpIpv6ExtensionProfile;
    use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
    use ripdpi_proxy_runtime_adapter::udp_desync::UdpExecutionFallbackReason;
    use std::sync::Arc;

    #[test]
    fn udp_decoy_only_outcome_records_non_applied_receipt() {
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 7));
        let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
        let attempt_token = AttemptCorrelationId::new("udp-decoy-only").expect("valid attempt token");

        assert!(state.record_udp_desync_execution_evidence(
            Some(&attempt_token),
            Some("quic_burst"),
            UdpExecutionOutcome {
                attempted_actions: 2,
                completed_actions: 2,
                real_writes_committed: 0,
                payload_bytes_committed: 0,
                technique_actions_completed: 2,
                ipv6_extension_profile: None,
                fallback_reason: None,
            },
        ));

        let evidence = control.desync_execution_evidence();
        assert_eq!(evidence.len(), 1);
        assert_eq!(evidence[0].receipt().disposition(), DesyncExecutionDisposition::ActivationSkipped);
        assert_eq!(evidence[0].receipt().effective_family(), None);
        assert_eq!(evidence[0].receipt().fallback_reason(), None);
    }

    #[test]
    fn udp_plain_fallback_is_distinct_from_activation_skip() {
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 8));
        let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
        let attempt_token = AttemptCorrelationId::new("udp-plain-fallback").expect("valid attempt token");

        assert!(state.record_udp_desync_execution_evidence(
            Some(&attempt_token),
            Some("quic_ipfrag2"),
            UdpExecutionOutcome {
                attempted_actions: 2,
                completed_actions: 2,
                real_writes_committed: 1,
                payload_bytes_committed: 256,
                technique_actions_completed: 0,
                ipv6_extension_profile: None,
                fallback_reason: Some(UdpExecutionFallbackReason::IpFragmentationFallback),
            },
        ));

        let evidence = control.desync_execution_evidence();
        assert_eq!(evidence.len(), 1);
        assert_eq!(evidence[0].receipt().disposition(), DesyncExecutionDisposition::PlanFailedPlainFallback);
        assert_eq!(evidence[0].receipt().effective_family(), None);
        assert_eq!(evidence[0].receipt().fallback_reason(), Some(DesyncFallbackReason::StrategyFamilyFallback));
    }

    #[test]
    fn udp_applied_ip_fragmentation_preserves_exact_ipv6_extension_profile() {
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 10));
        let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
        let attempt_token = AttemptCorrelationId::new("udp-ipv6-extension").expect("valid attempt token");

        assert!(state.record_udp_desync_execution_evidence(
            Some(&attempt_token),
            Some("quic_ipfrag2"),
            UdpExecutionOutcome {
                attempted_actions: 1,
                completed_actions: 1,
                real_writes_committed: 1,
                payload_bytes_committed: 256,
                technique_actions_completed: 1,
                ipv6_extension_profile: Some(DesyncUdpIpv6ExtensionProfile::HopByHopDestinationOptions),
                fallback_reason: None,
            },
        ));

        let evidence = control.desync_execution_evidence();
        assert_eq!(evidence.len(), 1);
        assert_eq!(
            evidence[0].receipt().udp_ipv6_extension_profile(),
            Some(DesyncUdpIpv6ExtensionProfile::HopByHopDestinationOptions),
        );
    }

    #[test]
    fn udp_partial_execution_failure_records_terminal_receipt() {
        let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, 9));
        let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
        let attempt_token = AttemptCorrelationId::new("udp-partial-failure").expect("valid attempt token");

        assert!(state.record_udp_desync_failure_evidence(
            Some(&attempt_token),
            Some("quic_sni_split"),
            UdpExecutionOutcome {
                attempted_actions: 3,
                completed_actions: 1,
                real_writes_committed: 0,
                payload_bytes_committed: 0,
                technique_actions_completed: 1,
                ipv6_extension_profile: None,
                fallback_reason: None,
            },
        ));

        let evidence = control.desync_execution_evidence();
        assert_eq!(evidence.len(), 1);
        assert_eq!(evidence[0].receipt().disposition(), DesyncExecutionDisposition::ExecutionFailed);
        assert_eq!(evidence[0].receipt().terminal_reason(), Some(DesyncTerminalReason::StrategyExecution));
        assert_eq!(evidence[0].receipt().completed_actions(), 1);
    }
}
