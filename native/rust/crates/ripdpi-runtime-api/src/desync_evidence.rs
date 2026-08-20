use std::fmt;

#[derive(Clone, PartialEq, Eq)]
pub struct AttemptCorrelationId {
    value: String,
}

impl AttemptCorrelationId {
    pub fn new(value: impl Into<String>) -> Option<Self> {
        let value = value.into();
        (!value.is_empty() && value.len() <= u8::MAX as usize).then_some(Self { value })
    }

    pub fn as_opaque_str(&self) -> &str {
        &self.value
    }
}

impl fmt::Debug for AttemptCorrelationId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("AttemptCorrelationId(<redacted>)")
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncExecutionEvidence {
    generation: u64,
    attempt_token: AttemptCorrelationId,
    connection_ordinal: u64,
    receipt: DesyncExecutionReceipt,
}

impl DesyncExecutionEvidence {
    pub fn new(
        generation: u64,
        attempt_token: AttemptCorrelationId,
        connection_ordinal: u64,
        receipt: DesyncExecutionReceipt,
    ) -> Option<Self> {
        (generation != 0 && connection_ordinal != 0).then_some(Self {
            generation,
            attempt_token,
            connection_ordinal,
            receipt,
        })
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn attempt_token(&self) -> &AttemptCorrelationId {
        &self.attempt_token
    }

    pub fn connection_ordinal(&self) -> u64 {
        self.connection_ordinal
    }

    pub fn receipt(&self) -> &DesyncExecutionReceipt {
        &self.receipt
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncExecutionReceipt {
    transport: DesyncExecutionTransport,
    disposition: DesyncExecutionDisposition,
    configured_family: Option<DesyncStrategyFamily>,
    effective_family: Option<DesyncStrategyFamily>,
    marker_base: Option<DesyncOffsetMarkerBase>,
    marker_delta: Option<i16>,
    resolved_offset: Option<u16>,
    planned_steps: u16,
    attempted_actions: u16,
    completed_actions: u16,
    real_writes_committed: usize,
    completed_awaits: usize,
    payload_bytes_committed: usize,
    tls_record_prelude_applied: bool,
    tls_prelude: Option<DesyncTlsPreludeEvidence>,
    udp_ipv6_extension_profile: Option<DesyncUdpIpv6ExtensionProfile>,
    fallback_reason: Option<DesyncFallbackReason>,
    terminal_reason: Option<DesyncTerminalReason>,
}

impl DesyncExecutionReceipt {
    #[allow(clippy::too_many_arguments)]
    pub fn try_new(
        transport: DesyncExecutionTransport,
        disposition: DesyncExecutionDisposition,
        configured_family: Option<DesyncStrategyFamily>,
        effective_family: Option<DesyncStrategyFamily>,
        marker_base: Option<DesyncOffsetMarkerBase>,
        marker_delta: Option<i16>,
        resolved_offset: Option<u16>,
        planned_steps: u16,
        attempted_actions: u16,
        completed_actions: u16,
        real_writes_committed: usize,
        completed_awaits: usize,
        payload_bytes_committed: usize,
        tls_record_prelude_applied: bool,
        tls_prelude: Option<DesyncTlsPreludeEvidence>,
        udp_ipv6_extension_profile: Option<DesyncUdpIpv6ExtensionProfile>,
        fallback_reason: Option<DesyncFallbackReason>,
        terminal_reason: Option<DesyncTerminalReason>,
    ) -> Option<Self> {
        let has_coherent_write = (real_writes_committed == 0) == (payload_bytes_committed == 0);
        let coherent_actions = completed_actions <= attempted_actions;
        let tls_prelude_coherent =
            tls_prelude.as_ref().is_none_or(|evidence| (evidence.applied_count() > 0) == tls_record_prelude_applied);
        let udp_profile_coherent = udp_ipv6_extension_profile.is_none()
            || (transport == DesyncExecutionTransport::Udp
                && configured_family == Some(DesyncStrategyFamily::QuicIpFragment2));
        let family_specific_shape = match effective_family {
            Some(DesyncStrategyFamily::Split | DesyncStrategyFamily::TlsRecordSplit) => {
                transport == DesyncExecutionTransport::Tcp
                    && marker_base.is_some()
                    && resolved_offset.is_some()
                    && attempted_actions >= 3
                    && completed_actions >= 3
                    && real_writes_committed >= 2
                    && completed_awaits >= 1
                    && fallback_reason.is_none()
                    && (effective_family != Some(DesyncStrategyFamily::TlsRecordSplit)
                        || tls_prelude.as_ref().is_some_and(DesyncTlsPreludeEvidence::is_exact_single_application))
            }
            Some(
                DesyncStrategyFamily::QuicBurst
                | DesyncStrategyFamily::QuicSniSplit
                | DesyncStrategyFamily::QuicFakeVersion
                | DesyncStrategyFamily::QuicCryptoSplit
                | DesyncStrategyFamily::QuicPaddingLadder
                | DesyncStrategyFamily::QuicVersionNegotiationDecoy
                | DesyncStrategyFamily::QuicMultiInitialRealistic
                | DesyncStrategyFamily::QuicDummyPrepend
                | DesyncStrategyFamily::QuicIpFragment2,
            ) => {
                transport == DesyncExecutionTransport::Udp
                    && !tls_record_prelude_applied
                    && completed_awaits == 0
                    && marker_base.is_none()
                    && resolved_offset.is_none()
            }
            _ => true,
        };
        let valid = match disposition {
            DesyncExecutionDisposition::Applied => {
                real_writes_committed > 0
                    && payload_bytes_committed > 0
                    && planned_steps > 0
                    && attempted_actions > 0
                    && completed_actions == attempted_actions
                    && terminal_reason.is_none()
                    && configured_family.is_some()
                    && effective_family.is_some()
                    && family_specific_shape
                    && (transport == DesyncExecutionTransport::Tcp || completed_awaits == 0)
            }
            DesyncExecutionDisposition::ActivationSkipped | DesyncExecutionDisposition::PlanFailedPlainFallback => {
                completed_awaits == 0
                    && real_writes_committed <= 1
                    && has_coherent_write
                    && terminal_reason.is_none()
                    && effective_family.is_none()
            }
            DesyncExecutionDisposition::ExecutionFailed => terminal_reason.is_some(),
        };
        (valid && coherent_actions && tls_prelude_coherent && udp_profile_coherent).then_some(Self {
            transport,
            disposition,
            configured_family,
            effective_family,
            marker_base,
            marker_delta,
            resolved_offset,
            planned_steps,
            attempted_actions,
            completed_actions,
            real_writes_committed,
            completed_awaits,
            payload_bytes_committed,
            tls_record_prelude_applied,
            tls_prelude,
            udp_ipv6_extension_profile,
            fallback_reason,
            terminal_reason,
        })
    }

    pub fn transport(&self) -> DesyncExecutionTransport {
        self.transport
    }

    pub fn disposition(&self) -> DesyncExecutionDisposition {
        self.disposition
    }

    pub fn configured_family(&self) -> Option<DesyncStrategyFamily> {
        self.configured_family
    }

    pub fn effective_family(&self) -> Option<DesyncStrategyFamily> {
        self.effective_family
    }

    pub fn marker_base(&self) -> Option<DesyncOffsetMarkerBase> {
        self.marker_base
    }

    pub fn marker_delta(&self) -> Option<i16> {
        self.marker_delta
    }

    pub fn resolved_offset(&self) -> Option<u16> {
        self.resolved_offset
    }

    pub fn planned_steps(&self) -> u16 {
        self.planned_steps
    }

    pub fn attempted_actions(&self) -> u16 {
        self.attempted_actions
    }

    pub fn completed_actions(&self) -> u16 {
        self.completed_actions
    }

    pub fn real_writes_committed(&self) -> usize {
        self.real_writes_committed
    }

    pub fn completed_awaits(&self) -> usize {
        self.completed_awaits
    }

    pub fn payload_bytes_committed(&self) -> usize {
        self.payload_bytes_committed
    }

    pub fn tls_record_prelude_applied(&self) -> bool {
        self.tls_record_prelude_applied
    }

    pub fn tls_prelude(&self) -> Option<&DesyncTlsPreludeEvidence> {
        self.tls_prelude.as_ref()
    }

    pub fn udp_ipv6_extension_profile(&self) -> Option<DesyncUdpIpv6ExtensionProfile> {
        self.udp_ipv6_extension_profile
    }

    pub fn fallback_reason(&self) -> Option<DesyncFallbackReason> {
        self.fallback_reason
    }

    pub fn terminal_reason(&self) -> Option<DesyncTerminalReason> {
        self.terminal_reason
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncTlsPreludeEvidence {
    configured_count: u16,
    applied_count: u16,
    kind: Option<DesyncTlsPreludeKind>,
    marker_base: Option<DesyncOffsetMarkerBase>,
    marker_delta: Option<i16>,
    resolved_offset: Option<u16>,
}

impl DesyncTlsPreludeEvidence {
    pub fn try_new(
        configured_count: u16,
        applied_count: u16,
        kind: Option<DesyncTlsPreludeKind>,
        marker_base: Option<DesyncOffsetMarkerBase>,
        marker_delta: Option<i16>,
        resolved_offset: Option<u16>,
    ) -> Option<Self> {
        let exact_fields =
            kind.is_some() && marker_base.is_some() && marker_delta.is_some() && resolved_offset.is_some();
        let valid = configured_count > 0
            && applied_count <= configured_count
            && ((applied_count == 1 && exact_fields) || (applied_count != 1 && !exact_fields));
        valid.then_some(Self { configured_count, applied_count, kind, marker_base, marker_delta, resolved_offset })
    }

    pub fn configured_count(&self) -> u16 {
        self.configured_count
    }
    pub fn applied_count(&self) -> u16 {
        self.applied_count
    }
    pub fn kind(&self) -> Option<DesyncTlsPreludeKind> {
        self.kind
    }
    pub fn marker_base(&self) -> Option<DesyncOffsetMarkerBase> {
        self.marker_base
    }
    pub fn marker_delta(&self) -> Option<i16> {
        self.marker_delta
    }
    pub fn resolved_offset(&self) -> Option<u16> {
        self.resolved_offset
    }
    pub fn is_exact_single_application(&self) -> bool {
        self.configured_count == 1
            && self.applied_count == 1
            && self.kind.is_some()
            && self.marker_base.is_some()
            && self.marker_delta.is_some()
            && self.resolved_offset.is_some()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncTlsPreludeKind {
    TlsRec,
    TlsRandRec,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncExecutionTransport {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncUdpIpv6ExtensionProfile {
    HopByHop,
    HopByHop2,
    DestinationOptions,
    HopByHopDestinationOptions,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncExecutionDisposition {
    Applied,
    ActivationSkipped,
    PlanFailedPlainFallback,
    ExecutionFailed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncStrategyFamily {
    Split,
    TlsRecordSplit,
    SeqOverlap,
    TlsRecordSeqOverlap,
    MultiDisorder,
    TlsRecordMultiDisorder,
    Disorder,
    OutOfBand,
    DisorderedOutOfBand,
    Fake,
    FakeSplit,
    FakeDisorder,
    HostFake,
    IpFragment2,
    FakeRst,
    TlsRecord,
    QuicBurst,
    QuicSniSplit,
    QuicFakeVersion,
    QuicCryptoSplit,
    QuicPaddingLadder,
    QuicVersionNegotiationDecoy,
    QuicMultiInitialRealistic,
    QuicDummyPrepend,
    QuicIpFragment2,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncOffsetMarkerBase {
    Absolute,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    EchExt,
    SniExt,
    Adaptive,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncFallbackReason {
    AndroidTtlUnavailable,
    StrategyFamilyFallback,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DesyncTerminalReason {
    Transport,
    StrategyExecution,
    Planning,
}
