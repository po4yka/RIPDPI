use std::borrow::Cow;
use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::time::Duration;

/// Callback invoked for each packet written during desync execution.
/// The bool parameter is `true` for outbound packets.
pub type PcapHook = Arc<dyn Fn(&[u8], bool) + Send + Sync>;

use crate::platform;
use ripdpi_config::{DesyncGroup, EntropyMode, FakeOrder, FakeSeqMode, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{
    activation_filter_matches, build_fake_packet, build_fake_region_bytes, build_hostfake_bytes,
    build_secondary_fake_packet, plan_tcp, resolve_hostfake_span, ActivationContext, ActivationTcpState,
    ActivationTransport, AdaptivePlannerHints, DesyncAction, DesyncPlan,
};
use ripdpi_packets::entropy;
use ripdpi_packets::tls_marker_info;
use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;
use socket2::SockRef;

use crate::sync::{AtomicBool, Ordering};

use super::adaptive::{
    capability_requires_desync_fallback, direct_path_capability_for_route, resolve_adaptive_fake_ttl,
    resolve_tcp_hints_with_evolver,
};
use super::morph::{apply_tcp_morph_policy_to_group, emit_morph_hint_applied, tcp_morph_hint_family};
use super::state::{RuntimeState, DESYNC_SEED_BASE};

#[derive(Debug)]
pub(super) struct OutboundSendOutcome {
    pub(super) bytes_committed: usize,
    pub(super) strategy_family: Option<&'static str>,
}

#[derive(Debug)]
pub(super) enum OutboundSendError {
    Transport(io::Error),
    StrategyExecution {
        action: &'static str,
        strategy_family: &'static str,
        fallback: Option<&'static str>,
        bytes_committed: usize,
        source_errno: Option<i32>,
        source: io::Error,
    },
}

impl OutboundSendError {
    pub(super) fn into_io_error(self) -> io::Error {
        let kind = self.kind();
        io::Error::new(kind, self)
    }

    pub(super) fn kind(&self) -> io::ErrorKind {
        match self {
            Self::Transport(source) => source.kind(),
            Self::StrategyExecution { source, .. } => source.kind(),
        }
    }

    fn source_error(&self) -> &io::Error {
        match self {
            Self::Transport(source) => source,
            Self::StrategyExecution { source, .. } => source,
        }
    }
}

impl std::fmt::Display for OutboundSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport(source) => source.fmt(formatter),
            Self::StrategyExecution { action, strategy_family, fallback, bytes_committed, source, .. } => {
                write!(
                    formatter,
                    "desync action={action} strategy_family={strategy_family} bytes_committed={bytes_committed}"
                )?;
                if let Some(fallback) = fallback {
                    write!(formatter, " fallback={fallback}")?;
                }
                write!(formatter, ": {source}")
            }
        }
    }
}

impl std::error::Error for OutboundSendError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.source_error())
    }
}

impl From<io::Error> for OutboundSendError {
    fn from(value: io::Error) -> Self {
        Self::Transport(value)
    }
}

#[derive(Debug)]
struct WriteProgressError {
    written: usize,
    source: io::Error,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FakeEmissionRole {
    Fake,
    Genuine,
}

#[derive(Debug)]
struct FakeEmission<'a> {
    role: FakeEmissionRole,
    payload: &'a [u8],
    ttl: u8,
    flags: platform::TcpFlagOverrides,
    original_offset: usize,
}

#[allow(clippy::too_many_arguments)]
fn build_ordered_fake_split_emissions<'a>(
    order: FakeOrder,
    first_real: &'a [u8],
    first_fake: &'a [u8],
    second_real: &'a [u8],
    second_fake: &'a [u8],
    first_real_ttl: u8,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<FakeEmission<'a>> {
    let second_offset = first_real.len();
    let fake_a = FakeEmission {
        role: FakeEmissionRole::Fake,
        payload: first_fake,
        ttl: fake_ttl,
        flags: fake_flags,
        original_offset: 0,
    };
    let real_a = FakeEmission {
        role: FakeEmissionRole::Genuine,
        payload: first_real,
        ttl: first_real_ttl,
        flags: original_flags,
        original_offset: 0,
    };
    let fake_b = FakeEmission {
        role: FakeEmissionRole::Fake,
        payload: second_fake,
        ttl: fake_ttl,
        flags: fake_flags,
        original_offset: second_offset,
    };
    let real_b = FakeEmission {
        role: FakeEmissionRole::Genuine,
        payload: second_real,
        ttl: fake_ttl,
        flags: original_flags,
        original_offset: second_offset,
    };

    match order {
        FakeOrder::BeforeEach => vec![fake_a, real_a, fake_b, real_b],
        FakeOrder::AllFakesFirst => vec![fake_a, fake_b, real_a, real_b],
        FakeOrder::RealFakeRealFake => vec![real_a, fake_a, real_b, fake_b],
        FakeOrder::AllRealsFirst => vec![real_a, real_b, fake_a, fake_b],
    }
}

fn build_plain_fake_emissions<'a>(
    order: FakeOrder,
    original: &'a [u8],
    fake_segments: &[&'a [u8]],
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<FakeEmission<'a>> {
    let mut fakes: Vec<FakeEmission<'a>> = fake_segments
        .iter()
        .map(|payload| FakeEmission {
            role: FakeEmissionRole::Fake,
            payload,
            ttl: fake_ttl,
            flags: fake_flags,
            original_offset: 0,
        })
        .collect();
    let original = FakeEmission {
        role: FakeEmissionRole::Genuine,
        payload: original,
        ttl: fake_ttl,
        flags: original_flags,
        original_offset: 0,
    };
    match order {
        FakeOrder::BeforeEach | FakeOrder::AllFakesFirst => {
            fakes.push(original);
            fakes
        }
        FakeOrder::RealFakeRealFake | FakeOrder::AllRealsFirst => {
            let mut result = vec![original];
            result.extend(fakes);
            result
        }
    }
}

fn ordered_segments_from_emissions<'a>(
    emissions: &'a [FakeEmission<'a>],
    fake_seq_mode: FakeSeqMode,
) -> Vec<platform::OrderedTcpSegment<'a>> {
    let mut fake_sequence_offset = 0usize;
    emissions
        .iter()
        .map(|emission| {
            let sequence_offset = match emission.role {
                FakeEmissionRole::Genuine => emission.original_offset,
                FakeEmissionRole::Fake => match fake_seq_mode {
                    FakeSeqMode::Duplicate => emission.original_offset,
                    FakeSeqMode::Sequential => {
                        let current = fake_sequence_offset;
                        fake_sequence_offset = fake_sequence_offset.saturating_add(emission.payload.len());
                        current
                    }
                },
            };
            platform::OrderedTcpSegment {
                payload: emission.payload,
                ttl: emission.ttl,
                flags: emission.flags,
                sequence_offset,
                use_fake_timestamp: emission.role == FakeEmissionRole::Fake,
            }
        })
        .collect()
}

pub(super) fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    payload: Option<&[u8]>,
    tcp_segment_hint: Option<ripdpi_desync::TcpSegmentHint>,
    tcp_activation_state: Option<platform::TcpActivationState>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    let has_ech = payload.and_then(tls_marker_info).and_then(|markers| markers.ech_ext_start).is_some();
    let tcp_state = tcp_activation_state.map_or(
        ActivationTcpState { has_ech: Some(has_ech), ..ActivationTcpState::default() },
        |state| ActivationTcpState {
            has_timestamp: state.has_timestamp,
            has_ech: Some(has_ech),
            window_size: state.window_size,
            mss: state.mss.or_else(|| tcp_segment_hint.and_then(|hint| hint.snd_mss.or(hint.advmss))),
        },
    );
    ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        seqovl_supported: platform::seqovl_supported(),
        transport,
        tcp_segment_hint,
        tcp_state,
        resolved_fake_ttl,
        adaptive,
    }
}

/// Prepend entropy-aware padding to the payload if the group's entropy
/// mode is enabled. An adaptive override (from strategy evolution) takes
/// precedence over the group's configured mode. Returns `Cow::Borrowed`
/// (zero allocation) when no padding is needed.
fn apply_entropy_padding<'a>(
    group: &DesyncGroup,
    payload: &'a [u8],
    adaptive_override: Option<EntropyMode>,
) -> Cow<'a, [u8]> {
    let actions = &group.actions;
    let max_pad = actions.entropy_padding_max as usize;
    let mode = adaptive_override.unwrap_or(actions.entropy_mode);

    let padding = match mode {
        EntropyMode::Disabled => return Cow::Borrowed(payload),
        EntropyMode::Popcount => {
            let target = match actions.entropy_padding_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => entropy::POPCOUNT_EXEMPT_LOW,
            };
            entropy::generate_entropy_padding(payload, target, max_pad)
        }
        EntropyMode::Shannon => {
            let target = match actions.shannon_entropy_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => 7.92,
            };
            entropy::generate_shannon_padding(payload, target, max_pad)
        }
        EntropyMode::Combined => {
            let pc_target = match actions.entropy_padding_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => entropy::POPCOUNT_EXEMPT_LOW,
            };
            let sh_target = match actions.shannon_entropy_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => 7.92,
            };
            entropy::generate_combined_padding(payload, pc_target, sh_target, max_pad)
        }
    };

    if padding.is_empty() {
        Cow::Borrowed(payload)
    } else {
        let mut padded = padding;
        padded.extend_from_slice(payload);
        Cow::Owned(padded)
    }
}

pub(super) fn send_with_group(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    group: &DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    host: Option<&str>,
    target: SocketAddr,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    let capability = direct_path_capability_for_route(state.runtime_context.as_ref(), host, target);
    let effective_group = apply_tcp_capability_fallback(group, capability);
    let effective_group = effective_group.as_ref();
    let resolved_fake_ttl = resolve_adaptive_fake_ttl(state, target, group_index, effective_group, host)?;
    let adaptive_hints = resolve_tcp_hints_with_evolver(state, target, group_index, effective_group, host, payload)?;
    emit_morph_hint_applied(state, target, tcp_morph_hint_family(state, payload, adaptive_hints));
    let morphed_group = apply_tcp_morph_policy_to_group(state, effective_group, payload, adaptive_hints);
    let effective_group = &morphed_group;
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        platform::tcp_segment_hint(writer).ok().flatten(),
        platform::tcp_activation_state(writer).ok().flatten(),
        resolved_fake_ttl,
        adaptive_hints,
    );
    // Only apply evolver-suggested entropy padding when the group has fake
    // steps; without fakes the padding bytes reach the upstream server and
    // corrupt the application stream.
    let entropy_override = adaptive_hints.entropy_mode.filter(|_| group_has_fake_steps(effective_group));
    let effective_payload = apply_entropy_padding(effective_group, payload, entropy_override);
    let strategy_family = primary_tcp_strategy_family(effective_group);
    if should_desync_tcp(effective_group, context) {
        let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
        match plan_tcp(effective_group, &effective_payload, seed, state.config.network.default_ttl, context) {
            Ok(plan) if requires_special_tcp_execution(effective_group) => {
                let bytes_committed = execute_tcp_plan(
                    writer,
                    &state.config,
                    effective_group,
                    &plan,
                    seed,
                    resolved_fake_ttl,
                    strategy_family,
                    &state.ttl_unavailable,
                )?;
                Ok(OutboundSendOutcome { bytes_committed, strategy_family })
            }
            Ok(plan) => {
                let bytes_committed = execute_tcp_actions(
                    writer,
                    &plan.actions,
                    state.config.network.default_ttl,
                    state.config.timeouts.wait_send,
                    Duration::from_millis(state.config.timeouts.await_interval.max(1) as u64),
                    strategy_family,
                    &state.ttl_unavailable,
                    group.actions.md5sig,
                    effective_group.actions.ip_id_mode,
                    state.pcap_hook.as_ref(),
                )?;
                Ok(OutboundSendOutcome { bytes_committed, strategy_family })
            }
            Err(_) => {
                let bytes_committed = write_transport_payload(writer, &effective_payload)?;
                Ok(OutboundSendOutcome { bytes_committed, strategy_family: None })
            }
        }
    } else {
        let bytes_committed = write_transport_payload(writer, &effective_payload)?;
        Ok(OutboundSendOutcome { bytes_committed, strategy_family: None })
    }
}

fn group_has_fake_steps(group: &DesyncGroup) -> bool {
    group.effective_tcp_chain().iter().any(|step| {
        matches!(
            step.kind,
            TcpChainStepKind::Fake
                | TcpChainStepKind::FakeSplit
                | TcpChainStepKind::FakeDisorder
                | TcpChainStepKind::HostFake
        )
    })
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some()
}

pub(super) fn primary_tcp_strategy_family(group: &DesyncGroup) -> Option<&'static str> {
    let chain = group.effective_tcp_chain();
    let has_tls_prelude = chain.iter().any(|step| step.kind.is_tls_prelude());
    chain.into_iter().find(|step| !step.kind.is_tls_prelude()).map(|step| match step.kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => "split",
        TcpChainStepKind::SeqOverlap => {
            if has_tls_prelude {
                "tlsrec_seqovl"
            } else {
                "seqovl"
            }
        }
        TcpChainStepKind::MultiDisorder => {
            if has_tls_prelude {
                "tlsrec_multidisorder"
            } else {
                "multidisorder"
            }
        }
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => "tlsrec",
    })
}

fn strategy_fallback_family(strategy_family: &'static str) -> Option<&'static str> {
    match strategy_family {
        "seqovl" => Some("split"),
        "tlsrec_seqovl" => Some("tlsrec_split"),
        "disorder" => Some("split"),
        "disoob" => Some("oob"),
        "fakeddisorder" => Some("fakedsplit"),
        _ => None,
    }
}

fn apply_tcp_capability_fallback<'a>(
    group: &'a DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
) -> Cow<'a, DesyncGroup> {
    let Some(capability) = capability else {
        return Cow::Borrowed(group);
    };
    if !capability_requires_desync_fallback(capability) {
        return Cow::Borrowed(group);
    }
    let Some(strategy_family) = primary_tcp_strategy_family(group) else {
        if group.actions.fake_tcp_timestamp_enabled {
            let mut adjusted = group.clone();
            adjusted.actions.fake_tcp_timestamp_enabled = false;
            return Cow::Owned(adjusted);
        }
        return Cow::Borrowed(group);
    };
    let mut adjusted = group.clone();
    let mut changed = false;
    if let Some(fallback_kind) = tcp_fallback_kind_for_strategy(strategy_family) {
        if let Some(step) = adjusted.actions.tcp_chain.iter_mut().find(|step| !step.kind.is_tls_prelude()) {
            if step.kind != fallback_kind {
                step.kind = fallback_kind;
                changed = true;
            }
        }
    }
    if adjusted.actions.fake_tcp_timestamp_enabled {
        adjusted.actions.fake_tcp_timestamp_enabled = false;
        changed = true;
    }
    if changed {
        Cow::Owned(adjusted)
    } else {
        Cow::Borrowed(group)
    }
}

fn tcp_fallback_kind_for_strategy(strategy_family: &'static str) -> Option<TcpChainStepKind> {
    match strategy_family {
        "seqovl" | "tlsrec_seqovl" | "disorder" => Some(TcpChainStepKind::Split),
        "disoob" => Some(TcpChainStepKind::Oob),
        "fakeddisorder" => Some(TcpChainStepKind::FakeSplit),
        _ => None,
    }
}

fn write_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "split" => "write_split",
        "seqovl" | "tlsrec_seqovl" => "write_seqovl",
        "disorder" => "write_disorder",
        "oob" => "write_oob",
        "disoob" => "write_disoob",
        "fake" => "write_fake",
        "fakedsplit" => "write_fakesplit",
        "fakeddisorder" => "write_fakeddisorder",
        "hostfake" => "write_hostfake",
        _ => "write",
    }
}

fn should_fallback_ipfrag2_tcp_error_kind(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::InvalidInput | io::ErrorKind::WouldBlock | io::ErrorKind::Unsupported)
}

fn log_ipfrag2_flow_fallback(error: &impl std::fmt::Display) {
    tracing::debug!("falling back to normal TCP write for ipfrag2 after per-flow repair downgrade: {error}");
}

fn should_fallback_seqovl_error_kind(kind: io::ErrorKind) -> bool {
    matches!(
        kind,
        io::ErrorKind::InvalidInput
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::Unsupported
            | io::ErrorKind::PermissionDenied
    )
}

fn await_writable_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "split" => "await_writable_split",
        "seqovl" | "tlsrec_seqovl" => "await_writable_seqovl",
        "disorder" => "await_writable_disorder",
        "oob" => "await_writable_oob",
        "disoob" => "await_writable_disoob",
        "fakedsplit" => "await_writable_fakesplit",
        "fakeddisorder" => "await_writable_fakeddisorder",
        "hostfake" => "await_writable_hostfake",
        _ => "await_writable",
    }
}

fn set_ttl_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "disorder" => "set_ttl_disorder",
        "disoob" => "set_ttl_disoob",
        "fakeddisorder" => "set_ttl_fakeddisorder",
        _ => "set_ttl",
    }
}

fn restore_ttl_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "disorder" => "restore_default_ttl_disorder",
        "disoob" => "restore_default_ttl_disoob",
        "fakeddisorder" => "restore_default_ttl_fakeddisorder",
        _ => "restore_default_ttl",
    }
}

pub(super) fn requires_special_tcp_execution(group: &DesyncGroup) -> bool {
    let supports_fake_retransmit = platform::supports_fake_retransmit();
    group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::MultiDisorder | TcpChainStepKind::Fake | TcpChainStepKind::IpFrag2)
            || (supports_fake_retransmit
                && matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder))
            || tcp_step_has_flag_overrides(step)
    })
}

fn tcp_step_has_flag_overrides(step: &TcpChainStep) -> bool {
    step.tcp_flags_set.unwrap_or_default() != 0
        || step.tcp_flags_unset.unwrap_or_default() != 0
        || step.tcp_flags_orig_set.unwrap_or_default() != 0
        || step.tcp_flags_orig_unset.unwrap_or_default() != 0
}

fn step_fake_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_set.unwrap_or_default(),
        unset: step.tcp_flags_unset.unwrap_or_default(),
    }
}

fn step_original_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_orig_set.unwrap_or_default(),
        unset: step.tcp_flags_orig_unset.unwrap_or_default(),
    }
}

#[allow(clippy::too_many_arguments)]
fn execute_tcp_actions(
    writer: &mut TcpStream,
    actions: &[DesyncAction],
    default_ttl: u8,
    wait_send: bool,
    await_interval: Duration,
    strategy_family: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
    md5sig: bool,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    pcap_hook: Option<&PcapHook>,
) -> Result<usize, OutboundSendError> {
    // When default_ttl is 0 (auto-detect), lazily read the current TTL on
    // the first SetTtl action so we always have a value to restore.
    let mut cached_restore_ttl: Option<u8> = if default_ttl != 0 { Some(default_ttl) } else { None };
    let mut ttl_modified = false;
    // Some Android builds reject per-socket TTL rewrites at runtime. In that
    // case, continue without the TTL mutation so the connection can still
    // progress instead of failing the whole request. Pre-seeded from the
    // session-level flag so subsequent connections skip TTL immediately.
    let mut ttl_actions_unavailable = session_ttl_unavailable.load(Ordering::Relaxed);
    let mut bytes_committed = 0usize;
    let fallback = strategy_family.and_then(strategy_fallback_family);

    let result = (|| -> Result<usize, OutboundSendError> {
        for action in actions {
            match action {
                DesyncAction::Write(bytes) => {
                    if let Some(strategy_family) = strategy_family {
                        if fallback.is_some() && ttl_modified {
                            let (should_restore_ttl, committed) = write_payload_with_android_ttl_fallback(
                                writer,
                                bytes,
                                cached_restore_ttl.unwrap_or(default_ttl.max(1)),
                                ttl_modified,
                                &mut ttl_actions_unavailable,
                                write_action_name(strategy_family),
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                            ttl_modified = should_restore_ttl;
                            bytes_committed = committed;
                        } else {
                            bytes_committed = write_strategy_payload_named(
                                writer,
                                bytes,
                                write_action_name(strategy_family),
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                        }
                    } else {
                        bytes_committed = write_transport_payload(writer, bytes)?;
                    }
                    if let Some(hook) = pcap_hook {
                        hook(bytes, true);
                    }
                }
                DesyncAction::WriteUrgent { prefix, urgent_byte } => {
                    if let Some(strategy_family) = strategy_family {
                        if fallback.is_some() && ttl_modified {
                            let (should_restore_ttl, committed) = send_oob_with_android_ttl_fallback(
                                writer,
                                prefix,
                                *urgent_byte,
                                cached_restore_ttl.unwrap_or(default_ttl.max(1)),
                                ttl_modified,
                                &mut ttl_actions_unavailable,
                                match strategy_family {
                                    "disoob" => "send_oob_disoob",
                                    _ => "send_oob",
                                },
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                            ttl_modified = should_restore_ttl;
                            bytes_committed = committed;
                        } else {
                            bytes_committed = send_oob_action_named(
                                writer,
                                prefix,
                                *urgent_byte,
                                match strategy_family {
                                    "disoob" => "send_oob_disoob",
                                    _ => "send_oob",
                                },
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                        }
                    } else {
                        bytes_committed = send_transport_oob_payload(writer, prefix, *urgent_byte)?;
                    }
                }
                DesyncAction::SetTtl(ttl) => {
                    // Capture current TTL before first modification when auto-detecting.
                    if cached_restore_ttl.is_none() {
                        cached_restore_ttl = platform::detect_default_ttl().ok();
                    }
                    if set_ttl_with_android_fallback_named(
                        writer,
                        *ttl,
                        &mut ttl_actions_unavailable,
                        strategy_family.map_or("set_ttl", set_ttl_action_name),
                        strategy_family.unwrap_or("split"),
                        fallback,
                        bytes_committed,
                    )? {
                        ttl_modified = true;
                    }
                }
                DesyncAction::RestoreDefaultTtl => {
                    if let Some(restore) = cached_restore_ttl {
                        if restore_default_ttl_with_android_fallback_named(
                            writer,
                            restore,
                            &mut ttl_actions_unavailable,
                            strategy_family.map_or("restore_default_ttl", restore_ttl_action_name),
                            strategy_family.unwrap_or("split"),
                            fallback,
                            bytes_committed,
                        )? {
                            ttl_modified = false;
                        }
                    }
                }
                DesyncAction::SetMd5Sig { key_len } => {
                    if let Some(strategy_family) = strategy_family {
                        set_md5sig_action_named(
                            writer,
                            *key_len,
                            "set_md5sig",
                            strategy_family,
                            fallback,
                            bytes_committed,
                        )?;
                    } else {
                        set_md5sig_transport_action(writer, *key_len)?;
                    }
                }
                DesyncAction::AttachDropSack => {}
                DesyncAction::DetachDropSack => {}
                DesyncAction::WriteIpFragmentedTcp { bytes, split_offset, disorder, ipv6_ext } => {
                    if let Some(strategy_family) = strategy_family {
                        match send_ip_fragmented_tcp_action_named(
                            writer,
                            bytes,
                            *split_offset,
                            default_ttl,
                            None,
                            *disorder,
                            *ipv6_ext,
                            platform::TcpFlagOverrides::default(),
                            ip_id_mode,
                            "write_ipfrag2",
                            strategy_family,
                            fallback,
                            bytes_committed,
                        ) {
                            Ok(committed) => {
                                bytes_committed = committed;
                            }
                            Err(err)
                                if strategy_family == "ipfrag2"
                                    && should_fallback_ipfrag2_tcp_error_kind(err.kind()) =>
                            {
                                log_ipfrag2_flow_fallback(&err);
                                bytes_committed = write_strategy_payload_named(
                                    writer,
                                    bytes,
                                    "write_ipfrag2",
                                    strategy_family,
                                    fallback,
                                    bytes_committed,
                                )?;
                            }
                            Err(err) => return Err(err),
                        }
                    } else {
                        match platform::send_ip_fragmented_tcp(
                            writer,
                            bytes,
                            *split_offset,
                            default_ttl,
                            None,
                            *disorder,
                            *ipv6_ext,
                            platform::TcpFlagOverrides::default(),
                            ip_id_mode,
                        ) {
                            Ok(()) => {
                                bytes_committed += bytes.len();
                            }
                            Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
                                log_ipfrag2_flow_fallback(&err);
                                bytes_committed = write_transport_payload(writer, bytes)?;
                            }
                            Err(err) => return Err(OutboundSendError::Transport(err)),
                        }
                    }
                }
                DesyncAction::WriteSeqOverlap { real_chunk, fake_prefix, remainder } => {
                    match platform::send_seqovl_tcp(
                        writer,
                        real_chunk,
                        fake_prefix,
                        default_ttl,
                        None,
                        md5sig,
                        platform::TcpFlagOverrides::default(),
                        ip_id_mode,
                    ) {
                        Ok(()) => {
                            bytes_committed += real_chunk.len();
                            if !remainder.is_empty() {
                                bytes_committed += write_transport_payload(writer, remainder)?;
                            }
                        }
                        Err(err) if should_fallback_seqovl_error_kind(err.kind()) => {
                            tracing::warn!("seqovl fallback to split: {err}");
                            bytes_committed += write_transport_payload(writer, real_chunk)?;
                            if !remainder.is_empty() {
                                bytes_committed += write_transport_payload(writer, remainder)?;
                            }
                        }
                        Err(err) => return Err(OutboundSendError::Transport(err)),
                    }
                }
                DesyncAction::WriteIpFragmentedUdp { .. } => {
                    return Err(OutboundSendError::Transport(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "udp fragmentation action reached tcp executor",
                    )));
                }
                DesyncAction::AwaitWritable => {
                    if let Some(strategy_family) = strategy_family {
                        await_writable_action_named(
                            writer,
                            wait_send,
                            await_interval,
                            await_writable_action_name(strategy_family),
                            strategy_family,
                            fallback,
                            bytes_committed,
                        )?;
                    } else {
                        await_transport_writable_action(writer, wait_send, await_interval)?;
                    }
                }
                DesyncAction::SetWindowClamp(size) => {
                    let _ = platform::set_tcp_window_clamp(writer, *size);
                }
                DesyncAction::RestoreWindowClamp => {
                    let _ = platform::set_tcp_window_clamp(writer, 0);
                }
                DesyncAction::SetWsize { window } => {
                    let _ = platform::set_tcp_window_clamp(writer, *window);
                }
                DesyncAction::RestoreWsize => {
                    let _ = platform::set_tcp_window_clamp(writer, 0);
                }
                DesyncAction::SendFakeRst => {
                    let _ = platform::send_fake_rst(
                        writer,
                        default_ttl,
                        None,
                        platform::TcpFlagOverrides::default(),
                        ip_id_mode,
                    );
                }
                DesyncAction::Delay(ms) => {
                    std::thread::sleep(Duration::from_millis(u64::from(*ms)));
                }
            }
        }
        Ok(bytes_committed)
    })();

    // Safety net: restore TTL even on early error return.
    if ttl_modified {
        if let Some(restore) = cached_restore_ttl {
            let _ = set_stream_ttl(writer, restore);
        }
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    if ttl_actions_unavailable {
        session_ttl_unavailable.store(true, Ordering::Relaxed);
    }

    result
}

#[cfg(any(test, target_os = "android"))]
fn should_ignore_android_ttl_error(err: &io::Error) -> bool {
    matches!(
        extract_os_error(err),
        Some(libc::EROFS | libc::EINVAL | libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES)
    )
}

#[cfg(not(any(test, target_os = "android")))]
fn should_ignore_android_ttl_error(_err: &io::Error) -> bool {
    false
}

#[cfg(any(test, target_os = "android"))]
fn extract_os_error(err: &io::Error) -> Option<i32> {
    err.raw_os_error()
}

#[derive(Debug)]
struct BuiltFakePackets {
    primary: ripdpi_desync::FakePacketPlan,
    secondary: Option<ripdpi_desync::FakePacketPlan>,
}

fn build_tcp_fake_packets(group: &DesyncGroup, tampered: &[u8], seed: u32) -> io::Result<Option<BuiltFakePackets>> {
    let primary = build_fake_packet(group, tampered, seed)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync"))?;
    let secondary = build_secondary_fake_packet(group, tampered, seed.wrapping_add(1)).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidData, "failed to build secondary fake packet for tcp desync")
    })?;
    Ok(Some(BuiltFakePackets { primary, secondary }))
}

struct TcpBasicStreamExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    md5sig: bool,
}

fn execute_basic_tcp_stream_step(
    ctx: &mut TcpBasicStreamExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => {
            let bytes_committed = if step_original_tcp_flags(configured_step).is_empty() {
                write_strategy_payload_named(
                    ctx.writer,
                    chunk,
                    "write_split",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            } else {
                send_flagged_tcp_payload_action_named(
                    ctx.writer,
                    chunk,
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    step_original_tcp_flags(configured_step),
                    ctx.group.actions.ip_id_mode,
                    "write_split",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            };
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::SeqOverlap => {
            let bytes_committed = write_strategy_payload_named(
                ctx.writer,
                chunk,
                "write_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Oob => {
            let bytes_committed = send_oob_action_named(
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                "send_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        _ => unreachable!("non-basic tcp step dispatched to basic stream executor"),
    }
}

fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    strategy_family: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
) -> Result<usize, OutboundSendError> {
    let has_multi_disorder = plan.steps.iter().any(|step| step.kind == TcpChainStepKind::MultiDisorder);
    let fake_packets = if plan.steps.iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
    }) {
        build_tcp_fake_packets(group, &plan.tampered, seed)?
    } else {
        None
    };
    // When default_ttl is 0 (auto-detect), use the system default so that
    // Disorder/Disoob/FakeDisorder handlers always restore the TTL.
    let restore_ttl = if config.network.default_ttl != 0 {
        config.network.default_ttl
    } else {
        platform::detect_default_ttl().unwrap_or(64)
    };
    let md5sig = group.actions.md5sig;
    let send_steps =
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind.is_tls_prelude()).collect::<Vec<_>>();
    if has_multi_disorder {
        return execute_multi_disorder_tcp_plan(
            writer,
            config,
            &send_steps,
            plan,
            strategy_family,
            md5sig,
            group.actions.ip_id_mode,
        );
    }
    if send_steps.len() < plan.steps.len() {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "tcp plan steps exceed configured send steps",
        )));
    }

    let mut cursor = 0usize;
    let mut ttl_actions_unavailable = session_ttl_unavailable.load(Ordering::Relaxed);
    let mut bytes_committed = 0usize;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(OutboundSendError::Transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid tcp desync step bounds",
            )));
        }
        let chunk = &plan.tampered[start..end];
        let configured_step = &send_steps[index];
        let step_family = match step.kind {
            TcpChainStepKind::Split | TcpChainStepKind::SynData => "split",
            TcpChainStepKind::SeqOverlap => strategy_family.unwrap_or("seqovl"),
            TcpChainStepKind::MultiDisorder => strategy_family.unwrap_or("multidisorder"),
            TcpChainStepKind::Oob => "oob",
            TcpChainStepKind::Disorder => "disorder",
            TcpChainStepKind::Disoob => "disoob",
            TcpChainStepKind::Fake => "fake",
            TcpChainStepKind::FakeSplit => "fakedsplit",
            TcpChainStepKind::FakeDisorder => "fakeddisorder",
            TcpChainStepKind::HostFake => "hostfake",
            TcpChainStepKind::IpFrag2 => "ipfrag2",
            TcpChainStepKind::FakeRst => "fakerst",
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => strategy_family.unwrap_or("tlsrec"),
        };
        let step_fallback = strategy_fallback_family(step_family);

        match step.kind {
            TcpChainStepKind::Split
            | TcpChainStepKind::SynData
            | TcpChainStepKind::SeqOverlap
            | TcpChainStepKind::Oob => {
                let mut basic_stream_ctx = TcpBasicStreamExecContext { writer, config, group, md5sig };
                bytes_committed = execute_basic_tcp_stream_step(
                    &mut basic_stream_ctx,
                    step.kind,
                    configured_step,
                    chunk,
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            TcpChainStepKind::Disorder => {
                let ttl_modified = set_ttl_with_android_fallback_named(
                    writer,
                    1,
                    &mut ttl_actions_unavailable,
                    "set_ttl_disorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                if step_original_tcp_flags(configured_step).is_empty() {
                    let (should_restore_ttl, committed) = write_payload_with_android_ttl_fallback(
                        writer,
                        chunk,
                        restore_ttl,
                        ttl_modified,
                        &mut ttl_actions_unavailable,
                        "write_disorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    bytes_committed = committed;
                    if should_restore_ttl {
                        let _ = restore_default_ttl_with_android_fallback_named(
                            writer,
                            restore_ttl,
                            &mut ttl_actions_unavailable,
                            "restore_default_ttl_disorder",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                    }
                } else {
                    bytes_committed = send_flagged_tcp_payload_action_named(
                        writer,
                        chunk,
                        config.network.default_ttl,
                        config.process.protect_path.as_deref(),
                        md5sig,
                        step_original_tcp_flags(configured_step),
                        group.actions.ip_id_mode,
                        "write_disorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    if ttl_modified {
                        let _ = restore_default_ttl_with_android_fallback_named(
                            writer,
                            restore_ttl,
                            &mut ttl_actions_unavailable,
                            "restore_default_ttl_disorder",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                    }
                }
                await_writable_action_named(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    "await_writable_disorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            TcpChainStepKind::Disoob => {
                let ttl_modified = set_ttl_with_android_fallback_named(
                    writer,
                    1,
                    &mut ttl_actions_unavailable,
                    "set_ttl_disoob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                let (should_restore_ttl, committed) = send_oob_with_android_ttl_fallback(
                    writer,
                    chunk,
                    group.actions.oob_data.unwrap_or(b'a'),
                    restore_ttl,
                    ttl_modified,
                    &mut ttl_actions_unavailable,
                    "send_oob_disoob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                bytes_committed = committed;
                await_writable_action_named(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    "await_writable_disoob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                if should_restore_ttl {
                    let _ = restore_default_ttl_with_android_fallback_named(
                        writer,
                        restore_ttl,
                        &mut ttl_actions_unavailable,
                        "restore_default_ttl_disoob",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }
            }
            TcpChainStepKind::Fake => {
                let fake_packets = fake_packets
                    .as_ref()
                    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let fake = &fake_packets.primary;
                let span = chunk.len();
                // Use cyclic wrapping when the fake payload is shorter than the
                // split span.  This matches FakeSplit/FakeDisorder which already
                // use build_fake_region_bytes() for the same purpose.
                let fake_chunk: Vec<u8> =
                    (0..span).map(|i| fake.bytes[(fake.fake_offset + i) % fake.bytes.len()]).collect();
                let secondary_fake_chunk =
                    fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, start, span));
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                let fake_flags = step_fake_tcp_flags(configured_step);
                let original_flags = step_original_tcp_flags(configured_step);
                let timestamp_delta_ticks =
                    group.actions.fake_tcp_timestamp_enabled.then_some(group.actions.fake_tcp_timestamp_delta_ticks);
                let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                    || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
                if custom_order {
                    let fake_refs: Vec<&[u8]> = std::iter::once(fake_chunk.as_slice())
                        .chain(secondary_fake_chunk.iter().map(Vec::as_slice))
                        .collect();
                    let emissions = build_plain_fake_emissions(
                        configured_step.fake_order,
                        chunk,
                        &fake_refs,
                        fake_ttl,
                        fake_flags,
                        original_flags,
                    );
                    let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                    bytes_committed = send_ordered_fake_segments_action_named(
                        writer,
                        &ordered_segments,
                        chunk.len(),
                        config.network.default_ttl,
                        config.process.protect_path.as_deref(),
                        md5sig,
                        timestamp_delta_ticks,
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                } else {
                    bytes_committed = send_fake_tcp_action_named(
                        writer,
                        chunk,
                        &fake_chunk,
                        fake_ttl,
                        md5sig,
                        config.network.default_ttl,
                        platform::FakeTcpOptions {
                            secondary_fake_prefix: secondary_fake_chunk.as_deref(),
                            timestamp_delta_ticks,
                            protect_path: config.process.protect_path.as_deref(),
                            fake_flags,
                            orig_flags: original_flags,
                            ..Default::default()
                        },
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }
            }
            TcpChainStepKind::FakeSplit => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        chunk,
                        "write_fakesplit",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_fakesplit",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    cursor = end;
                    continue;
                }
                let fake_packets = fake_packets
                    .as_ref()
                    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(&fake_packets.primary, start, chunk.len());
                let second_fake = build_fake_region_bytes(&fake_packets.primary, end, second.len());
                let first_secondary_fake = fake_packets
                    .secondary
                    .as_ref()
                    .map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
                let second_secondary_fake = fake_packets
                    .secondary
                    .as_ref()
                    .map(|secondary| build_fake_region_bytes(secondary, end, second.len()));
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                let fake_flags = step_fake_tcp_flags(configured_step);
                let original_flags = step_original_tcp_flags(configured_step);
                let timestamp_delta_ticks =
                    group.actions.fake_tcp_timestamp_enabled.then_some(group.actions.fake_tcp_timestamp_delta_ticks);
                let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                    || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
                if custom_order {
                    let emissions = build_ordered_fake_split_emissions(
                        configured_step.fake_order,
                        chunk,
                        &first_fake,
                        second,
                        &second_fake,
                        fake_ttl,
                        fake_ttl,
                        fake_flags,
                        original_flags,
                    );
                    let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                    bytes_committed = send_ordered_fake_segments_action_named(
                        writer,
                        &ordered_segments,
                        chunk.len() + second.len(),
                        config.network.default_ttl,
                        config.process.protect_path.as_deref(),
                        md5sig,
                        timestamp_delta_ticks,
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake_fakesplit",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                } else {
                    bytes_committed = send_fake_tcp_action_named(
                        writer,
                        chunk,
                        &first_fake,
                        fake_ttl,
                        md5sig,
                        config.network.default_ttl,
                        platform::FakeTcpOptions {
                            secondary_fake_prefix: first_secondary_fake.as_deref(),
                            timestamp_delta_ticks,
                            protect_path: config.process.protect_path.as_deref(),
                            fake_flags,
                            orig_flags: original_flags,
                            ..Default::default()
                        },
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake_fakesplit",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    bytes_committed = send_fake_tcp_action_named(
                        writer,
                        second,
                        &second_fake,
                        fake_ttl,
                        md5sig,
                        config.network.default_ttl,
                        platform::FakeTcpOptions {
                            secondary_fake_prefix: second_secondary_fake.as_deref(),
                            timestamp_delta_ticks,
                            protect_path: config.process.protect_path.as_deref(),
                            fake_flags,
                            orig_flags: original_flags,
                            ..Default::default()
                        },
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake_fakesplit",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::FakeDisorder => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    let ttl_modified = set_ttl_with_android_fallback_named(
                        writer,
                        1,
                        &mut ttl_actions_unavailable,
                        "set_ttl_fakeddisorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    let (should_restore_ttl, committed) = write_payload_with_android_ttl_fallback(
                        writer,
                        chunk,
                        restore_ttl,
                        ttl_modified,
                        &mut ttl_actions_unavailable,
                        "write_fakeddisorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    bytes_committed = committed;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_fakeddisorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    if should_restore_ttl {
                        let _ = restore_default_ttl_with_android_fallback_named(
                            writer,
                            restore_ttl,
                            &mut ttl_actions_unavailable,
                            "restore_default_ttl_fakeddisorder",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                    }
                    cursor = end;
                    continue;
                }
                let fake_packets = fake_packets
                    .as_ref()
                    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(&fake_packets.primary, start, chunk.len());
                let second_fake = build_fake_region_bytes(&fake_packets.primary, end, second.len());
                let first_secondary_fake = fake_packets
                    .secondary
                    .as_ref()
                    .map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
                let second_secondary_fake = fake_packets
                    .secondary
                    .as_ref()
                    .map(|secondary| build_fake_region_bytes(secondary, end, second.len()));
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                let fake_flags = step_fake_tcp_flags(configured_step);
                let original_flags = step_original_tcp_flags(configured_step);
                let timestamp_delta_ticks =
                    group.actions.fake_tcp_timestamp_enabled.then_some(group.actions.fake_tcp_timestamp_delta_ticks);
                let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                    || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
                if custom_order {
                    let second_offset = chunk.len();
                    let emissions = match configured_step.fake_order {
                        FakeOrder::BeforeEach => vec![
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &first_fake,
                                ttl: 1,
                                flags: fake_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: chunk,
                                ttl: 1,
                                flags: original_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &second_fake,
                                ttl: fake_ttl,
                                flags: fake_flags,
                                original_offset: second_offset,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: second,
                                ttl: fake_ttl,
                                flags: original_flags,
                                original_offset: second_offset,
                            },
                        ],
                        FakeOrder::AllFakesFirst => vec![
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &first_fake,
                                ttl: 1,
                                flags: fake_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &second_fake,
                                ttl: fake_ttl,
                                flags: fake_flags,
                                original_offset: second_offset,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: chunk,
                                ttl: 1,
                                flags: original_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: second,
                                ttl: fake_ttl,
                                flags: original_flags,
                                original_offset: second_offset,
                            },
                        ],
                        FakeOrder::RealFakeRealFake => vec![
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: chunk,
                                ttl: 1,
                                flags: original_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &first_fake,
                                ttl: 1,
                                flags: fake_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: second,
                                ttl: fake_ttl,
                                flags: original_flags,
                                original_offset: second_offset,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &second_fake,
                                ttl: fake_ttl,
                                flags: fake_flags,
                                original_offset: second_offset,
                            },
                        ],
                        FakeOrder::AllRealsFirst => vec![
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: chunk,
                                ttl: 1,
                                flags: original_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: second,
                                ttl: fake_ttl,
                                flags: original_flags,
                                original_offset: second_offset,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &first_fake,
                                ttl: 1,
                                flags: fake_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &second_fake,
                                ttl: fake_ttl,
                                flags: fake_flags,
                                original_offset: second_offset,
                            },
                        ],
                    };
                    let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                    bytes_committed = send_ordered_fake_segments_action_named(
                        writer,
                        &ordered_segments,
                        chunk.len() + second.len(),
                        config.network.default_ttl,
                        config.process.protect_path.as_deref(),
                        md5sig,
                        timestamp_delta_ticks,
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake_fakeddisorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    cursor = plan.tampered.len();
                    break;
                }
                match send_fake_tcp_action_named(
                    writer,
                    chunk,
                    &first_fake,
                    1,
                    md5sig,
                    config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: first_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    group.actions.ip_id_mode,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                    "send_fake_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                ) {
                    Ok(committed) => {
                        bytes_committed = committed;
                    }
                    Err(err) if should_ignore_android_ttl_error(err.source_error()) => {
                        log_android_desync_fallback("send_fake_fakeddisorder", "fakedsplit", &err);
                        bytes_committed = send_fake_tcp_action_named(
                            writer,
                            chunk,
                            &first_fake,
                            fake_ttl,
                            md5sig,
                            config.network.default_ttl,
                            platform::FakeTcpOptions {
                                secondary_fake_prefix: first_secondary_fake.as_deref(),
                                timestamp_delta_ticks,
                                protect_path: config.process.protect_path.as_deref(),
                                fake_flags,
                                orig_flags: original_flags,
                                ..Default::default()
                            },
                            group.actions.ip_id_mode,
                            (
                                config.timeouts.wait_send,
                                Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                            ),
                            "send_fake_fakeddisorder",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                    }
                    Err(err) => return Err(err),
                }
                bytes_committed = send_fake_tcp_action_named(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    md5sig,
                    config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: second_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    group.actions.ip_id_mode,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                    "send_fake_fakesplit",
                    "fakedsplit",
                    None,
                    bytes_committed,
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::IpFrag2 => {
                match send_ip_fragmented_tcp_action_named(
                    writer,
                    &plan.tampered,
                    end,
                    config.network.default_ttl,
                    config.process.protect_path.as_deref(),
                    false, // disorder not available in legacy plan path
                    ripdpi_ipfrag::Ipv6ExtHeaders::default(),
                    step_original_tcp_flags(configured_step),
                    group.actions.ip_id_mode,
                    "write_ipfrag2",
                    step_family,
                    step_fallback,
                    bytes_committed,
                ) {
                    Ok(committed) => {
                        bytes_committed = committed;
                    }
                    Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
                        log_ipfrag2_flow_fallback(&err);
                        bytes_committed = write_strategy_payload_named(
                            writer,
                            &plan.tampered,
                            "write_ipfrag2",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                    }
                    Err(err) => return Err(err),
                }
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(configured_step, &plan.tampered, start, end, seed) else {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        chunk,
                        "write_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    cursor = end;
                    continue;
                };

                if start < span.host_start {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        &plan.tampered[start..span.host_start],
                        "write_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }

                let real_host = &plan.tampered[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(
                    real_host,
                    configured_step.fake_host_template.as_deref(),
                    seed,
                    configured_step.random_fake_host,
                );
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                let fake_flags = step_fake_tcp_flags(configured_step);
                let original_flags = step_original_tcp_flags(configured_step);
                let timestamp_delta_ticks =
                    group.actions.fake_tcp_timestamp_enabled.then_some(group.actions.fake_tcp_timestamp_delta_ticks);
                let custom_order = configured_step.fake_seq_mode != FakeSeqMode::Duplicate
                    || (span.midhost.is_some() && configured_step.fake_order != FakeOrder::BeforeEach);
                if custom_order {
                    let emissions = if let Some(midhost) = span.midhost {
                        let split = midhost - span.host_start;
                        let first_real = &plan.tampered[span.host_start..midhost];
                        let second_real = &plan.tampered[midhost..span.host_end];
                        let first_fake = &fake_host[..split];
                        let second_fake = &fake_host[split..];
                        build_ordered_fake_split_emissions(
                            configured_step.fake_order,
                            first_real,
                            first_fake,
                            second_real,
                            second_fake,
                            fake_ttl,
                            fake_ttl,
                            fake_flags,
                            original_flags,
                        )
                    } else {
                        vec![
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &fake_host,
                                ttl: fake_ttl,
                                flags: fake_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Genuine,
                                payload: real_host,
                                ttl: fake_ttl,
                                flags: original_flags,
                                original_offset: 0,
                            },
                            FakeEmission {
                                role: FakeEmissionRole::Fake,
                                payload: &fake_host,
                                ttl: fake_ttl,
                                flags: fake_flags,
                                original_offset: 0,
                            },
                        ]
                    };
                    let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                    bytes_committed = send_ordered_fake_segments_action_named(
                        writer,
                        &ordered_segments,
                        real_host.len(),
                        config.network.default_ttl,
                        config.process.protect_path.as_deref(),
                        md5sig,
                        timestamp_delta_ticks,
                        group.actions.ip_id_mode,
                        (
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        ),
                        "send_fake_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    if span.host_end < end {
                        bytes_committed = write_strategy_payload_named(
                            writer,
                            &plan.tampered[span.host_end..end],
                            "write_hostfake",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                        await_writable_action_named(
                            writer,
                            config.timeouts.wait_send,
                            Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                            "await_writable_hostfake",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?;
                    }
                    cursor = end;
                    continue;
                }
                bytes_committed = send_fake_tcp_action_named(
                    writer,
                    real_host,
                    &fake_host,
                    fake_ttl,
                    md5sig,
                    config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: None,
                        timestamp_delta_ticks: None,
                        protect_path: config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    group.actions.ip_id_mode,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                    "send_fake_hostfake",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;

                if let Some(midhost) = span.midhost {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        &plan.tampered[span.host_start..midhost],
                        "write_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        &plan.tampered[midhost..span.host_end],
                        "write_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                } else {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        real_host,
                        "write_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }

                bytes_committed = send_fake_tcp_action_named(
                    writer,
                    real_host,
                    &fake_host,
                    fake_ttl,
                    md5sig,
                    config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: None,
                        timestamp_delta_ticks: None,
                        protect_path: config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    group.actions.ip_id_mode,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                    "send_fake_hostfake",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;

                if span.host_end < end {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        &plan.tampered[span.host_end..end],
                        "write_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                    await_writable_action_named(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        "await_writable_hostfake",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }
            }
            TcpChainStepKind::FakeRst => {
                let _ = platform::send_fake_rst(
                    writer,
                    config.network.default_ttl,
                    config.process.protect_path.as_deref(),
                    step_fake_tcp_flags(configured_step),
                    group.actions.ip_id_mode,
                );
                if step_original_tcp_flags(configured_step).is_empty() {
                    bytes_committed = write_strategy_payload_named(
                        writer,
                        chunk,
                        "write_fakerst",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                } else {
                    bytes_committed = send_flagged_tcp_payload_action_named(
                        writer,
                        chunk,
                        config.network.default_ttl,
                        config.process.protect_path.as_deref(),
                        md5sig,
                        step_original_tcp_flags(configured_step),
                        group.actions.ip_id_mode,
                        "write_fakerst",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }
            }
            TcpChainStepKind::MultiDisorder => {
                return Err(OutboundSendError::Transport(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "multidisorder must be executed as a grouped tcp plan",
                )));
            }
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => {
                return Err(OutboundSendError::Transport(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "tls prelude step must not appear in tcp send plan",
                )));
            }
        }
        if configured_step.inter_segment_delay_ms > 0 && index + 1 < plan.steps.len() {
            std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms.min(500))));
        }
        cursor = end;
    }

    if cursor < plan.tampered.len() {
        bytes_committed = write_strategy_payload_named(
            writer,
            &plan.tampered[cursor..],
            write_action_name(strategy_family.unwrap_or("split")),
            strategy_family.unwrap_or("split"),
            strategy_family.and_then(strategy_fallback_family),
            bytes_committed,
        )?;
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    if ttl_actions_unavailable {
        session_ttl_unavailable.store(true, Ordering::Relaxed);
    }

    Ok(bytes_committed)
}

fn execute_multi_disorder_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    send_steps: &[ripdpi_config::TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
    md5sig: bool,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> Result<usize, OutboundSendError> {
    if send_steps.len() < 2 || send_steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid multidisorder tcp chain configuration",
        )));
    }
    if plan.steps.len() < 3 || plan.steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder requires at least three non-empty planned segments",
        )));
    }

    let mut cursor = 0usize;
    let mut segments = Vec::with_capacity(plan.steps.len());
    for step in &plan.steps {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start != cursor || end <= start || end > plan.tampered.len() {
            return Err(OutboundSendError::Transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid multidisorder tcp segment bounds",
            )));
        }
        segments.push(platform::TcpPayloadSegment { start, end });
        cursor = end;
    }
    if cursor != plan.tampered.len() {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder tcp plan does not cover the full payload",
        )));
    }

    let strategy_family = strategy_family.unwrap_or("multidisorder");
    let fallback = strategy_fallback_family(strategy_family);
    let inter_segment_delay_ms = send_steps.first().map_or(0, |s| s.inter_segment_delay_ms);
    strategy_result(
        platform::send_multi_disorder_tcp(
            writer,
            &plan.tampered,
            &segments,
            config.network.default_ttl,
            config.process.protect_path.as_deref(),
            inter_segment_delay_ms,
            md5sig,
            step_original_tcp_flags(send_steps.first().expect("multidisorder send step missing")),
            ip_id_mode,
        ),
        "write_multidisorder",
        strategy_family,
        fallback,
        0,
    )
    .map(|()| plan.tampered.len())
}

fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    let mut packet = Vec::with_capacity(prefix.len() + 1);
    packet.extend_from_slice(prefix);
    packet.push(urgent_byte);
    let sent = SockRef::from(writer).send_out_of_band(&packet)?;
    if sent != packet.len() {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "partial MSG_OOB send"));
    }
    Ok(())
}

pub(super) fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl_v4(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

fn strategy_execution_error(
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
    source: io::Error,
) -> OutboundSendError {
    OutboundSendError::StrategyExecution {
        action,
        strategy_family,
        fallback,
        bytes_committed,
        source_errno: source.raw_os_error(),
        source,
    }
}

fn log_android_desync_fallback(action: &'static str, fallback: &'static str, error: &OutboundSendError) {
    tracing::warn!("Android desync fallback applied: action={action} fallback={fallback}: {error}");
}

fn write_payload_progress(stream: &mut TcpStream, bytes: &[u8]) -> Result<(), WriteProgressError> {
    let mut written = 0usize;
    while written < bytes.len() {
        match stream.write(&bytes[written..]) {
            Ok(0) => {
                return Err(WriteProgressError {
                    written,
                    source: io::Error::new(io::ErrorKind::WriteZero, "partial tcp payload write"),
                });
            }
            Ok(chunk) => {
                written += chunk;
            }
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => {
                return Err(WriteProgressError { written, source: err });
            }
        }
    }
    Ok(())
}

fn write_transport_payload(stream: &mut TcpStream, bytes: &[u8]) -> Result<usize, OutboundSendError> {
    write_payload_progress(stream, bytes)
        .map(|()| bytes.len())
        .map_err(|progress| OutboundSendError::Transport(progress.source))
}

fn write_strategy_payload_named(
    stream: &mut TcpStream,
    bytes: &[u8],
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    write_payload_progress(stream, bytes).map(|()| bytes_committed + bytes.len()).map_err(|progress| {
        strategy_execution_error(action, strategy_family, fallback, bytes_committed + progress.written, progress.source)
    })
}

fn send_transport_oob_payload(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> Result<usize, OutboundSendError> {
    send_out_of_band(writer, prefix, urgent_byte).map(|()| prefix.len() + 1).map_err(OutboundSendError::Transport)
}

fn strategy_result<T>(
    result: io::Result<T>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<T, OutboundSendError> {
    result.map_err(|source| strategy_execution_error(action, strategy_family, fallback, bytes_committed, source))
}

fn transport_result<T>(result: io::Result<T>) -> Result<T, OutboundSendError> {
    result.map_err(OutboundSendError::Transport)
}

#[allow(clippy::too_many_arguments)]
fn write_payload_with_android_ttl_fallback(
    writer: &mut TcpStream,
    bytes: &[u8],
    restore_ttl: u8,
    ttl_modified: bool,
    ttl_actions_unavailable: &mut bool,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(bool, usize), OutboundSendError> {
    match write_payload_progress(writer, bytes) {
        Ok(()) => Ok((ttl_modified, bytes_committed + bytes.len())),
        Err(progress) if ttl_modified && progress.written == 0 && should_ignore_android_ttl_error(&progress.source) => {
            *ttl_actions_unavailable = true;
            restore_default_ttl_with_android_fallback_named(
                writer,
                restore_ttl,
                ttl_actions_unavailable,
                restore_ttl_action_name(strategy_family),
                strategy_family,
                fallback,
                bytes_committed,
            )?;
            log_android_desync_fallback(
                action,
                fallback.unwrap_or("none"),
                &strategy_execution_error(action, strategy_family, fallback, bytes_committed, progress.source),
            );
            let committed =
                write_strategy_payload_named(writer, bytes, action, strategy_family, fallback, bytes_committed)?;
            Ok((false, committed))
        }
        Err(progress) => Err(strategy_execution_error(
            action,
            strategy_family,
            fallback,
            bytes_committed + progress.written,
            progress.source,
        )),
    }
}

fn send_oob_action_named(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(send_out_of_band(writer, prefix, urgent_byte), action, strategy_family, fallback, bytes_committed)
        .map(|()| bytes_committed + prefix.len() + 1)
}

#[allow(clippy::too_many_arguments)]
fn send_oob_with_android_ttl_fallback(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
    restore_ttl: u8,
    ttl_modified: bool,
    ttl_actions_unavailable: &mut bool,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(bool, usize), OutboundSendError> {
    match send_oob_action_named(writer, prefix, urgent_byte, action, strategy_family, fallback, bytes_committed) {
        Ok(committed) => Ok((ttl_modified, committed)),
        Err(err) if ttl_modified && should_ignore_android_ttl_error(err.source_error()) => {
            *ttl_actions_unavailable = true;
            restore_default_ttl_with_android_fallback_named(
                writer,
                restore_ttl,
                ttl_actions_unavailable,
                restore_ttl_action_name(strategy_family),
                strategy_family,
                fallback,
                bytes_committed,
            )?;
            log_android_desync_fallback(action, fallback.unwrap_or("none"), &err);
            let committed =
                send_oob_action_named(writer, prefix, urgent_byte, action, strategy_family, fallback, bytes_committed)?;
            Ok((false, committed))
        }
        Err(err) => Err(err),
    }
}

#[allow(clippy::too_many_arguments)]
fn send_fake_tcp_action_named(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: platform::FakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            options,
            ip_id_mode,
            wait,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_prefix.len())
}

#[allow(clippy::too_many_arguments)]
fn send_ordered_fake_segments_action_named(
    stream: &TcpStream,
    segments: &[platform::OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_ordered_tcp_segments(
            stream,
            segments,
            original_payload_len,
            default_ttl,
            protect_path,
            md5sig,
            timestamp_delta_ticks,
            ip_id_mode,
            wait,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_payload_len)
}

#[allow(clippy::too_many_arguments)]
fn send_flagged_tcp_payload_action_named(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + payload.len())
}

#[allow(clippy::too_many_arguments)]
fn send_ip_fragmented_tcp_action_named(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_ip_fragmented_tcp(
            stream,
            payload,
            split_offset,
            default_ttl,
            protect_path,
            disorder,
            ipv6_ext,
            flags,
            ip_id_mode,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + payload.len())
}

fn set_ttl_action_named(
    stream: &TcpStream,
    ttl: u8,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(set_stream_ttl(stream, ttl), action, strategy_family, fallback, bytes_committed)
}

fn set_ttl_with_android_fallback_named(
    stream: &TcpStream,
    ttl: u8,
    ttl_actions_unavailable: &mut bool,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<bool, OutboundSendError> {
    if *ttl_actions_unavailable {
        return Ok(false);
    }

    match set_ttl_action_named(stream, ttl, action, strategy_family, fallback, bytes_committed) {
        Ok(()) => Ok(true),
        Err(err) if should_ignore_android_ttl_error(err.source_error()) => {
            *ttl_actions_unavailable = true;
            tracing::warn!("TTL desync action unavailable on this Android build: {err}");
            Ok(false)
        }
        Err(err) => Err(err),
    }
}

fn restore_default_ttl_action_named(
    stream: &TcpStream,
    ttl: u8,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(set_stream_ttl(stream, ttl), action, strategy_family, fallback, bytes_committed)
}

fn restore_default_ttl_with_android_fallback_named(
    stream: &TcpStream,
    ttl: u8,
    ttl_actions_unavailable: &mut bool,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<bool, OutboundSendError> {
    if *ttl_actions_unavailable {
        return Ok(false);
    }

    match restore_default_ttl_action_named(stream, ttl, action, strategy_family, fallback, bytes_committed) {
        Ok(()) => Ok(true),
        Err(err) if should_ignore_android_ttl_error(err.source_error()) => {
            *ttl_actions_unavailable = true;
            tracing::warn!("TTL desync action unavailable on this Android build: {err}");
            Ok(false)
        }
        Err(err) => Err(err),
    }
}

fn set_md5sig_transport_action(stream: &TcpStream, key_len: u16) -> Result<(), OutboundSendError> {
    transport_result(platform::set_tcp_md5sig(stream, key_len))
}

fn set_md5sig_action_named(
    stream: &TcpStream,
    key_len: u16,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(platform::set_tcp_md5sig(stream, key_len), action, strategy_family, fallback, bytes_committed)
}

fn await_writable_action_named(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(
        platform::wait_tcp_stage(stream, wait_send, await_interval),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
}

fn await_transport_writable_action(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
) -> Result<(), OutboundSendError> {
    transport_result(platform::wait_tcp_stage(stream, wait_send, await_interval))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{NumericRange, OffsetExpr, TcpChainStep};
    use ripdpi_desync::{PlannedStep, ProtoInfo};
    use std::net::{Ipv4Addr, TcpListener};

    #[allow(dead_code)]
    mod rust_packet_seeds {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
    }

    fn test_group() -> DesyncGroup {
        DesyncGroup::new(0)
    }

    fn test_offset() -> OffsetExpr {
        OffsetExpr::absolute(0)
    }

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn multidisorder_chain() -> Vec<TcpChainStep> {
        vec![
            TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2)),
            TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)),
        ]
    }

    #[test]
    fn tcp_desync_helpers_require_actionable_groups_and_matching_rounds() {
        let mut group = test_group();
        group.set_round_activation(Some(NumericRange::new(2, 4)));
        let in_range = ActivationContext {
            round: 3,
            payload_size: 16,
            stream_start: 0,
            stream_end: 15,
            seqovl_supported: false,
            transport: ActivationTransport::Tcp,
            tcp_segment_hint: None,
            tcp_state: ActivationTcpState::default(),
            resolved_fake_ttl: None,
            adaptive: AdaptivePlannerHints::default(),
        };
        let out_of_range = ActivationContext { round: 5, ..in_range };

        assert!(!has_tcp_actions(&group));
        assert!(!should_desync_tcp(&group, in_range));
        assert!(activation_filter_matches(group.activation_filter(), in_range));
        assert!(!activation_filter_matches(group.activation_filter(), out_of_range));

        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));
        assert!(has_tcp_actions(&group));
        assert!(should_desync_tcp(&group, in_range));
        assert!(!should_desync_tcp(&group, out_of_range));
    }

    #[test]
    fn activation_context_from_progress_maps_tcp_state_and_ech_from_payload() {
        let payload = rust_packet_seeds::tls_client_hello_ech();
        let progress = OutboundProgress {
            round: 2,
            payload_size: payload.len(),
            stream_start: 32,
            stream_end: 32 + payload.len() - 1,
        };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            Some(ripdpi_desync::TcpSegmentHint {
                snd_mss: Some(1300),
                advmss: Some(1400),
                pmtu: Some(1500),
                ip_header_overhead: 40,
            }),
            Some(platform::TcpActivationState { has_timestamp: Some(true), window_size: Some(2048), mss: None }),
            Some(9),
            AdaptivePlannerHints::default(),
        );

        assert_eq!(context.round, 2);
        assert_eq!(context.tcp_state.has_timestamp, Some(true));
        assert_eq!(context.tcp_state.has_ech, Some(true));
        assert_eq!(context.tcp_state.window_size, Some(2048));
        assert_eq!(context.tcp_state.mss, Some(1300));
        assert_eq!(context.resolved_fake_ttl, Some(9));
    }

    #[test]
    fn special_tcp_execution_includes_fake_approximation_steps() {
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset()));
        assert_eq!(requires_special_tcp_execution(&group), platform::supports_fake_retransmit());

        group.actions.tcp_chain.clear();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, test_offset()));
        assert_eq!(requires_special_tcp_execution(&group), platform::supports_fake_retransmit());

        group.actions.tcp_chain.clear();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, test_offset()));
        assert!(requires_special_tcp_execution(&group));

        group.actions.tcp_chain.clear();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, test_offset()));
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)));
        assert!(requires_special_tcp_execution(&group));
    }

    #[test]
    fn seqovl_strategy_family_maps_to_seqovl_actions_and_split_fallbacks() {
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SeqOverlap, test_offset()));

        assert_eq!(primary_tcp_strategy_family(&group), Some("seqovl"));
        assert_eq!(strategy_fallback_family("seqovl"), Some("split"));
        assert_eq!(write_action_name("seqovl"), "write_seqovl");
        assert_eq!(await_writable_action_name("seqovl"), "await_writable_seqovl");

        group.actions.tcp_chain.insert(0, TcpChainStep::new(TcpChainStepKind::TlsRec, test_offset()));

        assert_eq!(primary_tcp_strategy_family(&group), Some("tlsrec_seqovl"));
        assert_eq!(strategy_fallback_family("tlsrec_seqovl"), Some("tlsrec_split"));
        assert_eq!(write_action_name("tlsrec_seqovl"), "write_seqovl");
        assert_eq!(await_writable_action_name("tlsrec_seqovl"), "await_writable_seqovl");
    }

    #[test]
    fn multidisorder_strategy_family_maps_tlsrec_variant_without_fallback() {
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, test_offset()));
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)));

        assert_eq!(primary_tcp_strategy_family(&group), Some("multidisorder"));
        assert_eq!(strategy_fallback_family("multidisorder"), None);

        group.actions.tcp_chain.insert(0, TcpChainStep::new(TcpChainStepKind::TlsRec, test_offset()));
        assert_eq!(primary_tcp_strategy_family(&group), Some("tlsrec_multidisorder"));
        assert_eq!(strategy_fallback_family("tlsrec_multidisorder"), None);
    }

    #[test]
    fn tcp_capability_fallback_rewrites_seqovl_to_tlsrec_split_and_disables_fake_timestamp() {
        let mut group = test_group();
        group.actions.fake_tcp_timestamp_enabled = true;
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, test_offset()));
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SeqOverlap, test_offset()));
        let capability = ProxyDirectPathCapability {
            authority: "example.org:443".to_string(),
            quic_usable: None,
            udp_usable: None,
            fallback_required: Some(true),
            repeated_handshake_failure_class: Some("tcp_reset".to_string()),
            updated_at: 1,
        };

        let adjusted = apply_tcp_capability_fallback(&group, Some(&capability)).into_owned();

        assert_eq!(
            strategy_fallback_family(primary_tcp_strategy_family(&group).expect("strategy family")),
            Some("tlsrec_split")
        );
        assert_eq!(adjusted.actions.tcp_chain[1].kind, TcpChainStepKind::Split);
        assert!(!adjusted.actions.fake_tcp_timestamp_enabled);
    }

    #[test]
    fn tcp_capability_fallback_leaves_group_unchanged_without_fallback_signal() {
        let mut group = test_group();
        group.actions.fake_tcp_timestamp_enabled = true;
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disorder, test_offset()));
        let capability = ProxyDirectPathCapability {
            authority: "example.org:443".to_string(),
            quic_usable: Some(true),
            udp_usable: Some(true),
            fallback_required: Some(false),
            repeated_handshake_failure_class: None,
            updated_at: 1,
        };

        let adjusted = apply_tcp_capability_fallback(&group, Some(&capability));

        assert!(matches!(adjusted, Cow::Borrowed(_)));
    }

    #[test]
    fn execute_multidisorder_tcp_plan_rejects_non_contiguous_segment_bounds() {
        let (mut client, _server) = connected_pair();
        let err = execute_multi_disorder_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &multidisorder_chain(),
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 3, end: 4 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
                ],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            Some("multidisorder"),
            false,
            None,
        )
        .expect_err("reject gapped multidisorder plan");

        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("invalid multidisorder tcp segment bounds"));
    }

    #[test]
    fn execute_multidisorder_tcp_plan_rejects_partial_payload_coverage() {
        let (mut client, _server) = connected_pair();
        let err = execute_multi_disorder_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &multidisorder_chain(),
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 5 },
                ],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            Some("multidisorder"),
            false,
            None,
        )
        .expect_err("reject truncated multidisorder plan");

        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("multidisorder tcp plan does not cover the full payload"));
    }

    #[test]
    fn outbound_send_error_preserves_strategy_execution_metadata() {
        let err = strategy_execution_error(
            "set_ttl_disorder",
            "disorder",
            Some("split"),
            0,
            io::Error::from_raw_os_error(libc::EINVAL),
        );

        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        match err {
            OutboundSendError::StrategyExecution {
                action,
                strategy_family,
                fallback,
                bytes_committed,
                source_errno,
                ..
            } => {
                assert_eq!(action, "set_ttl_disorder");
                assert_eq!(strategy_family, "disorder");
                assert_eq!(fallback, Some("split"));
                assert_eq!(bytes_committed, 0);
                assert_eq!(source_errno, Some(libc::EINVAL));
            }
            OutboundSendError::Transport(_) => panic!("expected strategy execution error"),
        }
        assert!(err.to_string().contains("desync action=set_ttl_disorder"));
    }

    #[test]
    fn outbound_send_error_into_io_error_preserves_fallback_details() {
        let err = strategy_execution_error(
            "write_disorder",
            "disorder",
            Some("split"),
            0,
            io::Error::from_raw_os_error(libc::EROFS),
        );
        let io_error = err.into_io_error();

        assert_eq!(io_error.kind(), io::ErrorKind::ReadOnlyFilesystem);
        assert_eq!(
            io_error.get_ref().and_then(|inner| inner.downcast_ref::<OutboundSendError>()).and_then(
                |inner| match inner {
                    OutboundSendError::StrategyExecution { fallback, .. } => *fallback,
                    OutboundSendError::Transport(_) => None,
                }
            ),
            Some("split")
        );
        assert!(io_error.get_ref().and_then(|inner| inner.downcast_ref::<OutboundSendError>()).is_some());
    }

    #[test]
    fn android_ttl_fallback_filter_matches_capability_errors_only() {
        assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EROFS)));
        assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EINVAL)));
        assert!(!should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::ECONNRESET)));
    }

    #[test]
    fn android_ttl_fallback_filter_matches_strategy_execution_source_errors() {
        let err = strategy_execution_error(
            "set_ttl_disorder",
            "disorder",
            Some("split"),
            0,
            io::Error::from_raw_os_error(libc::EROFS),
        );
        assert!(should_ignore_android_ttl_error(err.source_error()));
    }

    // ---------------------------------------------------------------
    // apply_entropy_padding
    // ---------------------------------------------------------------

    #[test]
    fn entropy_padding_disabled_returns_borrowed() {
        let group = test_group(); // entropy_mode defaults to Disabled
        let payload = b"test payload";
        let result = apply_entropy_padding(&group, payload, None);
        assert!(matches!(result, Cow::Borrowed(_)));
        assert_eq!(&*result, payload);
    }

    #[test]
    fn entropy_padding_popcount_mode_pads_non_exempt_payload() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Popcount;
        // 0xAA has popcount 4.0 (in GFW detection window 3.4-4.6)
        let payload = vec![0xAA; 100];
        let result = apply_entropy_padding(&group, &payload, None);
        assert!(matches!(result, Cow::Owned(_)), "should pad non-exempt payload");
        assert!(result.len() > payload.len(), "padded should be longer");
        // Padded payload should start with padding, end with original
        assert_eq!(&result[result.len() - payload.len()..], &payload[..]);
    }

    #[test]
    fn entropy_padding_popcount_mode_skips_exempt_payload() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Popcount;
        // All zeros: popcount 0.0, already exempt
        let payload = vec![0x00; 100];
        let result = apply_entropy_padding(&group, &payload, None);
        assert!(matches!(result, Cow::Borrowed(_)), "exempt payload should not be padded");
    }

    #[test]
    fn entropy_padding_shannon_mode_pads_high_entropy() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Shannon;
        // High entropy payload
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, None);
        assert!(matches!(result, Cow::Owned(_)), "should pad high-entropy payload");
        assert!(result.len() > payload.len());
    }

    #[test]
    fn entropy_padding_shannon_mode_skips_low_entropy() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Shannon;
        let payload = b"AAAAAAAAAAAAAAAAAAAAA"; // very low entropy
        let result = apply_entropy_padding(&group, payload, None);
        assert!(matches!(result, Cow::Borrowed(_)), "low entropy should not be padded");
    }

    #[test]
    fn entropy_padding_combined_mode_works() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Combined;
        // High entropy: needs Shannon padding
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, None);
        assert!(result.len() > payload.len(), "combined mode should pad high-entropy");
    }

    #[test]
    fn entropy_padding_adaptive_override_takes_precedence() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Disabled; // group says disabled
                                                            // But adaptive override says Shannon
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, Some(EntropyMode::Shannon));
        assert!(matches!(result, Cow::Owned(_)), "adaptive override should enable padding");
        assert!(result.len() > payload.len());
    }

    #[test]
    fn entropy_padding_adaptive_override_can_disable() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Shannon; // group says Shannon
                                                           // But adaptive override says Disabled
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, Some(EntropyMode::Disabled));
        assert!(matches!(result, Cow::Borrowed(_)), "adaptive Disabled should skip padding");
    }

    #[test]
    fn entropy_padding_custom_shannon_target_permil() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Shannon;
        group.actions.shannon_entropy_target_permil = Some(7920); // 7.92 bits/byte
        let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, None);
        assert!(matches!(result, Cow::Owned(_)));
        // Padded result should bring entropy below 7.92
        let combined_entropy = entropy::shannon_entropy(&result);
        assert!(combined_entropy <= 7.92, "expected <= 7.92, got {combined_entropy}");
    }

    #[test]
    fn entropy_padding_custom_popcount_target_permil() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Popcount;
        group.actions.entropy_padding_target_permil = Some(3200); // 3.2 target
        let payload = vec![0xAA; 100]; // popcount 4.0
        let result = apply_entropy_padding(&group, &payload, None);
        assert!(matches!(result, Cow::Owned(_)));
        let pc = entropy::popcount_per_byte(&result);
        assert!(pc <= 3.2, "expected popcount <= 3.2, got {pc}");
    }

    #[test]
    fn entropy_padding_preserves_original_payload_at_end() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Shannon;
        let payload: Vec<u8> = (0..512).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, None);
        if result.len() > payload.len() {
            let suffix = &result[result.len() - payload.len()..];
            assert_eq!(suffix, &payload[..], "original payload should be at the end");
        }
    }

    #[test]
    fn entropy_padding_respects_max_pad_config() {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Shannon;
        group.actions.entropy_padding_max = 10; // very small
        let payload: Vec<u8> = (0..4096).map(|i| (i % 256) as u8).collect();
        let result = apply_entropy_padding(&group, &payload, None);
        // Padding can be at most 10 bytes
        let padding_size = result.len() - payload.len();
        assert!(padding_size <= 10, "padding {padding_size} exceeds max 10");
    }

    // ---------------------------------------------------------------
    // Phase A: Pure helper function tests
    // ---------------------------------------------------------------

    #[test]
    fn strategy_fallback_maps_all_families() {
        assert_eq!(strategy_fallback_family("disorder"), Some("split"));
        assert_eq!(strategy_fallback_family("seqovl"), Some("split"));
        assert_eq!(strategy_fallback_family("tlsrec_seqovl"), Some("tlsrec_split"));
        assert_eq!(strategy_fallback_family("disoob"), Some("oob"));
        assert_eq!(strategy_fallback_family("fakeddisorder"), Some("fakedsplit"));
        assert_eq!(strategy_fallback_family("split"), None);
        assert_eq!(strategy_fallback_family("oob"), None);
        assert_eq!(strategy_fallback_family("fake"), None);
        assert_eq!(strategy_fallback_family("multidisorder"), None);
        assert_eq!(strategy_fallback_family("unknown"), None);
    }

    #[test]
    fn write_action_name_maps_all_families() {
        assert_eq!(write_action_name("split"), "write_split");
        assert_eq!(write_action_name("seqovl"), "write_seqovl");
        assert_eq!(write_action_name("tlsrec_seqovl"), "write_seqovl");
        assert_eq!(write_action_name("disorder"), "write_disorder");
        assert_eq!(write_action_name("oob"), "write_oob");
        assert_eq!(write_action_name("disoob"), "write_disoob");
        assert_eq!(write_action_name("fake"), "write_fake");
        assert_eq!(write_action_name("fakedsplit"), "write_fakesplit");
        assert_eq!(write_action_name("fakeddisorder"), "write_fakeddisorder");
        assert_eq!(write_action_name("hostfake"), "write_hostfake");
        assert_eq!(write_action_name("unknown"), "write");
    }

    #[test]
    fn set_ttl_action_name_maps_variants() {
        assert_eq!(set_ttl_action_name("disorder"), "set_ttl_disorder");
        assert_eq!(set_ttl_action_name("disoob"), "set_ttl_disoob");
        assert_eq!(set_ttl_action_name("fakeddisorder"), "set_ttl_fakeddisorder");
        assert_eq!(set_ttl_action_name("split"), "set_ttl");
        assert_eq!(set_ttl_action_name("oob"), "set_ttl");
    }

    #[test]
    fn restore_ttl_action_name_maps_variants() {
        assert_eq!(restore_ttl_action_name("disorder"), "restore_default_ttl_disorder");
        assert_eq!(restore_ttl_action_name("disoob"), "restore_default_ttl_disoob");
        assert_eq!(restore_ttl_action_name("fakeddisorder"), "restore_default_ttl_fakeddisorder");
        assert_eq!(restore_ttl_action_name("split"), "restore_default_ttl");
    }

    #[test]
    fn await_writable_action_name_maps_all() {
        assert_eq!(await_writable_action_name("split"), "await_writable_split");
        assert_eq!(await_writable_action_name("seqovl"), "await_writable_seqovl");
        assert_eq!(await_writable_action_name("tlsrec_seqovl"), "await_writable_seqovl");
        assert_eq!(await_writable_action_name("disorder"), "await_writable_disorder");
        assert_eq!(await_writable_action_name("oob"), "await_writable_oob");
        assert_eq!(await_writable_action_name("disoob"), "await_writable_disoob");
        assert_eq!(await_writable_action_name("fakedsplit"), "await_writable_fakesplit");
        assert_eq!(await_writable_action_name("fakeddisorder"), "await_writable_fakeddisorder");
        assert_eq!(await_writable_action_name("hostfake"), "await_writable_hostfake");
        assert_eq!(await_writable_action_name("unknown"), "await_writable");
    }

    #[test]
    fn ipfrag2_fallback_matches_expected_kinds() {
        assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::InvalidInput));
        assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::WouldBlock));
        assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::Unsupported));
        assert!(!should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::ConnectionReset));
        assert!(!should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::BrokenPipe));
    }

    #[test]
    fn seqovl_fallback_matches_expected_kinds() {
        assert!(should_fallback_seqovl_error_kind(io::ErrorKind::InvalidInput));
        assert!(should_fallback_seqovl_error_kind(io::ErrorKind::WouldBlock));
        assert!(should_fallback_seqovl_error_kind(io::ErrorKind::Unsupported));
        assert!(should_fallback_seqovl_error_kind(io::ErrorKind::PermissionDenied));
        assert!(!should_fallback_seqovl_error_kind(io::ErrorKind::ConnectionReset));
    }

    #[test]
    fn strategy_result_ok_passes_through() {
        let result: Result<i32, OutboundSendError> = strategy_result(Ok(42), "action", "family", Some("fallback"), 0);
        assert_eq!(result.unwrap(), 42);
    }

    #[test]
    fn strategy_result_err_wraps_metadata() {
        let result: Result<i32, OutboundSendError> = strategy_result(
            Err(io::Error::new(io::ErrorKind::BrokenPipe, "broken")),
            "write_split",
            "split",
            Some("disorder"),
            100,
        );
        match result.unwrap_err() {
            OutboundSendError::StrategyExecution { action, strategy_family, fallback, bytes_committed, .. } => {
                assert_eq!(action, "write_split");
                assert_eq!(strategy_family, "split");
                assert_eq!(fallback, Some("disorder"));
                assert_eq!(bytes_committed, 100);
            }
            OutboundSendError::Transport(err) => panic!("expected StrategyExecution, got Transport({err})"),
        }
    }

    #[test]
    fn transport_result_ok_passes_through() {
        let result: Result<i32, OutboundSendError> = transport_result(Ok(42));
        assert_eq!(result.unwrap(), 42);
    }

    #[test]
    fn transport_result_err_wraps_as_transport() {
        let result: Result<i32, OutboundSendError> =
            transport_result(Err(io::Error::new(io::ErrorKind::BrokenPipe, "broken")));
        assert!(matches!(result.unwrap_err(), OutboundSendError::Transport(_)));
    }

    // ---------------------------------------------------------------
    // Phase B: Write helper tests
    // ---------------------------------------------------------------

    #[test]
    fn write_payload_progress_full_payload() {
        let (mut client, mut server) = connected_pair();
        let payload = b"hello world test data";
        write_payload_progress(&mut client, payload).expect("write succeeds");
        let mut buf = vec![0u8; payload.len()];
        use std::io::Read;
        server.read_exact(&mut buf).expect("read succeeds");
        assert_eq!(&buf, payload);
    }

    #[test]
    fn write_payload_progress_closed_stream_errors() {
        let (mut client, server) = connected_pair();
        drop(server);
        // Write enough data to overwhelm kernel buffers and trigger an error
        let big = vec![0u8; 1024 * 1024];
        let mut got_error = false;
        for _ in 0..16 {
            if write_payload_progress(&mut client, &big).is_err() {
                got_error = true;
                break;
            }
        }
        assert!(got_error, "expected write error after filling kernel buffer to closed peer");
    }

    #[test]
    fn write_transport_payload_returns_byte_count() {
        let (mut client, _server) = connected_pair();
        let result = write_transport_payload(&mut client, b"hello");
        assert_eq!(result.unwrap(), 5);
    }

    #[test]
    fn write_transport_payload_error_is_transport() {
        let (mut client, server) = connected_pair();
        drop(server);
        let big = vec![0u8; 1024 * 1024];
        let mut last_err = None;
        for _ in 0..16 {
            if let Err(err) = write_transport_payload(&mut client, &big) {
                last_err = Some(err);
                break;
            }
        }
        let err = last_err.expect("expected transport error after filling kernel buffer");
        assert!(matches!(err, OutboundSendError::Transport(_)));
    }

    #[test]
    fn write_strategy_named_accumulates_committed() {
        let (mut client, _server) = connected_pair();
        let result = write_strategy_payload_named(&mut client, b"hello world", "write_split", "split", None, 50);
        assert_eq!(result.unwrap(), 61); // 50 + 11
    }

    #[test]
    fn write_strategy_named_error_has_metadata() {
        let (mut client, server) = connected_pair();
        drop(server);
        let big = vec![0u8; 1024 * 1024];
        let mut last_err = None;
        for _ in 0..16 {
            if let Err(err) =
                write_strategy_payload_named(&mut client, &big, "write_split", "split", Some("disorder"), 50)
            {
                last_err = Some(err);
                break;
            }
        }
        match last_err.expect("expected strategy error") {
            OutboundSendError::StrategyExecution { action, strategy_family, fallback, .. } => {
                assert_eq!(action, "write_split");
                assert_eq!(strategy_family, "split");
                assert_eq!(fallback, Some("disorder"));
            }
            OutboundSendError::Transport(err) => panic!("expected StrategyExecution, got Transport({err})"),
        }
    }

    // ---------------------------------------------------------------
    // Phase C: execute_tcp_actions tests
    // ---------------------------------------------------------------

    fn default_ttl_unavailable() -> AtomicBool {
        AtomicBool::new(false)
    }

    #[test]
    fn actions_write_only_no_strategy() {
        let (mut client, mut server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::Write(b"hello".to_vec()), DesyncAction::Write(b"world".to_vec())];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        // write_transport_payload returns bytes.len() (not accumulated), so last write's len is returned
        assert_eq!(result.unwrap(), 5);
        let mut buf = vec![0u8; 10];
        use std::io::Read;
        server.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"helloworld");
    }

    #[test]
    fn actions_write_with_strategy() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::Write(b"hello".to_vec())];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("split"),
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 5);
    }

    #[test]
    fn actions_set_ttl_and_restore() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions =
            vec![DesyncAction::SetTtl(42), DesyncAction::Write(b"x".to_vec()), DesyncAction::RestoreDefaultTtl];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("disorder"),
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 1);
    }

    #[test]
    fn actions_set_ttl_auto_detect() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::SetTtl(1), DesyncAction::RestoreDefaultTtl];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            0,
            false,
            Duration::from_millis(10),
            Some("disorder"),
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 0);
    }

    #[test]
    fn actions_write_urgent_no_strategy() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::WriteUrgent { prefix: b"ab".to_vec(), urgent_byte: b'!' }];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 3); // prefix.len() + 1
    }

    #[test]
    fn actions_write_urgent_with_strategy() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::WriteUrgent { prefix: b"ab".to_vec(), urgent_byte: b'!' }];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("oob"),
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 3);
    }

    // ipfrag2 fallback tests: on non-Linux, send_ip_fragmented_tcp returns
    // Unsupported and the fallback path plain-writes the data.  On Linux the
    // raw-socket call needs CAP_NET_RAW which CI runners lack.
    #[test]
    #[cfg(not(target_os = "linux"))]
    fn actions_ipfrag2_fallback_with_strategy() {
        let (mut client, mut server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::WriteIpFragmentedTcp {
            bytes: b"hello".to_vec(),
            split_offset: 2,
            disorder: false,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        }];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("ipfrag2"),
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 5);
        let mut buf = vec![0u8; 5];
        use std::io::Read;
        server.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"hello");
    }

    #[test]
    #[cfg(not(target_os = "linux"))]
    fn actions_ipfrag2_fallback_no_strategy() {
        let (mut client, mut server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::WriteIpFragmentedTcp {
            bytes: b"world".to_vec(),
            split_offset: 2,
            disorder: false,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        }];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 5);
        let mut buf = vec![0u8; 5];
        use std::io::Read;
        server.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"world");
    }

    #[test]
    fn actions_seqovl_fallback_to_split() {
        let (mut client, mut server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::WriteSeqOverlap {
            real_chunk: b"ab".to_vec(),
            fake_prefix: b"xx".to_vec(),
            remainder: b"cd".to_vec(),
        }];
        // On macOS, send_seqovl_tcp returns Unsupported -> fallback writes real_chunk + remainder
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 4);
        let mut buf = vec![0u8; 4];
        use std::io::Read;
        server.read_exact(&mut buf).expect("read");
        assert_eq!(&buf, b"abcd");
    }

    #[test]
    fn actions_udp_frag_rejects_in_tcp() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::WriteIpFragmentedUdp {
            bytes: b"data".to_vec(),
            split_offset: 2,
            disorder: false,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        }];
        let err = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        )
        .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        assert!(err.to_string().contains("udp fragmentation action reached tcp executor"));
    }

    #[test]
    fn actions_attach_detach_drop_sack_noop() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions =
            vec![DesyncAction::AttachDropSack, DesyncAction::Write(b"x".to_vec()), DesyncAction::DetachDropSack];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 1);
    }

    #[test]
    fn actions_window_clamp_ignored_on_unsupported() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![
            DesyncAction::SetWindowClamp(1024),
            DesyncAction::Write(b"x".to_vec()),
            DesyncAction::RestoreWindowClamp,
        ];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 1);
    }

    // These operations return Unsupported on macOS but succeed on Linux,
    // so the "errors on unsupported" assertion only holds off-Linux.
    #[test]
    #[cfg(not(target_os = "linux"))]
    fn actions_await_writable_errors_on_unsupported() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::Write(b"x".to_vec()), DesyncAction::AwaitWritable];
        let err = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("split"),
            &unavailable,
            false,
            None,
            None,
        )
        .unwrap_err();
        assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    }

    #[test]
    #[cfg(not(target_os = "linux"))]
    fn actions_set_md5sig_errors_on_unsupported() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![DesyncAction::SetMd5Sig { key_len: 16 }];
        let err = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("split"),
            &unavailable,
            false,
            None,
            None,
        )
        .unwrap_err();
        assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    }

    #[test]
    fn actions_ttl_unavailable_skips_set_restore() {
        let (mut client, _server) = connected_pair();
        let unavailable = AtomicBool::new(true);
        let actions =
            vec![DesyncAction::SetTtl(1), DesyncAction::Write(b"data".to_vec()), DesyncAction::RestoreDefaultTtl];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("disorder"),
            &unavailable,
            false,
            None,
            None,
        );
        assert_eq!(result.unwrap(), 4);
    }

    #[test]
    fn actions_safety_net_restores_ttl_on_success() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        // SetTtl modifies TTL, then write + no RestoreDefaultTtl -- safety net should restore
        let actions = vec![DesyncAction::SetTtl(42), DesyncAction::Write(b"x".to_vec())];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(10),
            Some("disorder"),
            &unavailable,
            false,
            None,
            None,
        );
        // Should succeed and safety net restores TTL at lines 590-594
        assert_eq!(result.unwrap(), 1);
    }

    // ---------------------------------------------------------------
    // Phase D: TTL and OOB wrapper tests
    // ---------------------------------------------------------------

    #[test]
    fn set_stream_ttl_loopback() {
        let (client, _server) = connected_pair();
        let result = set_stream_ttl(&client, 42);
        assert!(result.is_ok(), "set_stream_ttl should succeed on loopback: {:?}", result.err());
    }

    #[test]
    fn set_ttl_with_fallback_success() {
        let (client, _server) = connected_pair();
        let mut unavailable = false;
        let result = set_ttl_with_android_fallback_named(&client, 42, &mut unavailable, "set_ttl", "disorder", None, 0);
        assert!(result.unwrap());
        assert!(!unavailable);
    }

    #[test]
    fn set_ttl_with_fallback_skips_when_unavailable() {
        let (client, _server) = connected_pair();
        let mut unavailable = true;
        let result = set_ttl_with_android_fallback_named(&client, 42, &mut unavailable, "set_ttl", "disorder", None, 0);
        assert!(!result.unwrap());
    }

    #[test]
    fn restore_ttl_with_fallback_success() {
        let (client, _server) = connected_pair();
        let mut unavailable = false;
        let result = restore_default_ttl_with_android_fallback_named(
            &client,
            64,
            &mut unavailable,
            "restore_default_ttl",
            "disorder",
            None,
            0,
        );
        assert!(result.unwrap());
        assert!(!unavailable);
    }

    #[test]
    fn restore_ttl_with_fallback_skips_when_unavailable() {
        let (client, _server) = connected_pair();
        let mut unavailable = true;
        let result = restore_default_ttl_with_android_fallback_named(
            &client,
            64,
            &mut unavailable,
            "restore_default_ttl",
            "disorder",
            None,
            0,
        );
        assert!(!result.unwrap());
    }

    #[test]
    fn send_out_of_band_sends_prefix_plus_byte() {
        let (client, _server) = connected_pair();
        let result = send_out_of_band(&client, b"abc", b'!');
        assert!(result.is_ok(), "send_out_of_band should succeed on loopback: {:?}", result.err());
    }

    #[test]
    fn send_oob_action_named_accumulates() {
        let (client, _server) = connected_pair();
        let result = send_oob_action_named(&client, b"ab", b'!', "send_oob", "oob", None, 10);
        assert_eq!(result.unwrap(), 13); // 10 + 2 + 1
    }

    #[test]
    fn write_with_android_fallback_success() {
        let (mut client, _server) = connected_pair();
        let mut unavailable = false;
        let (ttl_modified, committed) = write_payload_with_android_ttl_fallback(
            &mut client,
            b"hello",
            64,
            true,
            &mut unavailable,
            "write_disorder",
            "disorder",
            Some("split"),
            50,
        )
        .unwrap();
        assert!(ttl_modified);
        assert_eq!(committed, 55); // 50 + 5
        assert!(!unavailable);
    }

    // ---------------------------------------------------------------
    // Phase E: execute_tcp_plan validation tests
    // ---------------------------------------------------------------

    #[test]
    fn plan_rejects_step_count_mismatch() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        // Group has 1 tcp_chain step but plan has 2 steps
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let err = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![
                    PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 3 },
                    PlannedStep { kind: TcpChainStepKind::Split, start: 3, end: 6 },
                ],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("split"),
            &unavailable,
        )
        .unwrap_err();
        assert!(err.to_string().contains("tcp plan steps exceed configured send steps"));
    }

    #[test]
    fn plan_rejects_negative_start() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let err = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: -1, end: 3 }],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("split"),
            &unavailable,
        )
        .unwrap_err();
        assert!(err.to_string().contains("negative tcp plan start"));
    }

    #[test]
    fn plan_rejects_negative_end() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let err = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: -1 }],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("split"),
            &unavailable,
        )
        .unwrap_err();
        assert!(err.to_string().contains("negative tcp plan end"));
    }

    #[test]
    fn plan_rejects_out_of_order_bounds() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let err = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 4, end: 2 }],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("split"),
            &unavailable,
        )
        .unwrap_err();
        assert!(err.to_string().contains("invalid tcp desync step bounds"));
    }

    #[test]
    fn plan_rejects_end_beyond_payload() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let err = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"abc".to_vec(),
                steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 10 }],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("split"),
            &unavailable,
        )
        .unwrap_err();
        assert!(err.to_string().contains("invalid tcp desync step bounds"));
    }

    #[test]
    fn plan_split_step_writes_chunk() {
        let (mut client, mut server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let result = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"hello".to_vec(),
                steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 5 }],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("split"),
            &unavailable,
        );
        // On macOS, await_writable returns Unsupported after the write succeeds.
        // The write portion (5 bytes) has been committed to the socket.
        // The error is from await_writable, not from the write itself.
        if let Err(err) = &result {
            assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
        }
        // Regardless of the await error, data should have been written
        server.set_read_timeout(Some(Duration::from_millis(100))).ok();
        let mut buf = vec![0u8; 5];
        use std::io::Read;
        let read_result = server.read(&mut buf);
        assert!(read_result.is_ok(), "data should have been written before await error");
    }

    #[test]
    fn plan_tlsrec_step_errors() {
        let (mut client, _server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let mut group = test_group();
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

        let err = execute_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &group,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![PlannedStep { kind: TcpChainStepKind::TlsRec, start: 0, end: 6 }],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            0,
            None,
            Some("tlsrec"),
            &unavailable,
        )
        .unwrap_err();
        assert!(err.to_string().contains("tls prelude step must not appear in tcp send plan"));
    }

    #[test]
    fn multidisorder_rejects_mixed_kinds_in_chain() {
        let (mut client, _server) = connected_pair();
        let chain = vec![
            TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2)),
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(4)),
        ];
        let err = execute_multi_disorder_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &chain,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
                ],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            Some("multidisorder"),
            false,
            None,
        )
        .expect_err("reject mixed chain kinds");
        assert!(err.to_string().contains("invalid multidisorder tcp chain configuration"));
    }

    #[test]
    fn multidisorder_rejects_single_step() {
        let (mut client, _server) = connected_pair();
        let chain = vec![TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2))];
        let err = execute_multi_disorder_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &chain,
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
                ],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            Some("multidisorder"),
            false,
            None,
        )
        .expect_err("reject single send step");
        assert!(err.to_string().contains("invalid multidisorder tcp chain configuration"));
    }

    #[test]
    fn multidisorder_rejects_too_few_planned() {
        let (mut client, _server) = connected_pair();
        let err = execute_multi_disorder_tcp_plan(
            &mut client,
            &RuntimeConfig::default(),
            &multidisorder_chain(),
            &DesyncPlan {
                tampered: b"abcdef".to_vec(),
                steps: vec![
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 3 },
                    PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 3, end: 6 },
                ],
                proto: ProtoInfo::default(),
                actions: Vec::new(),
            },
            Some("multidisorder"),
            false,
            None,
        )
        .expect_err("reject fewer than 3 planned segments");
        assert!(err.to_string().contains("multidisorder requires at least three non-empty planned segments"));
    }

    #[test]
    fn fake_ordering_plain_fake_collapses_expected_sides() {
        let before = build_plain_fake_emissions(
            FakeOrder::AllFakesFirst,
            b"real",
            &[b"fake-a".as_slice(), b"fake-b".as_slice()],
            7,
            platform::TcpFlagOverrides::default(),
            platform::TcpFlagOverrides::default(),
        );
        let after = build_plain_fake_emissions(
            FakeOrder::AllRealsFirst,
            b"real",
            &[b"fake-a".as_slice(), b"fake-b".as_slice()],
            7,
            platform::TcpFlagOverrides::default(),
            platform::TcpFlagOverrides::default(),
        );

        assert_eq!(
            before.iter().map(|emission| emission.role).collect::<Vec<_>>(),
            vec![FakeEmissionRole::Fake, FakeEmissionRole::Fake, FakeEmissionRole::Genuine]
        );
        assert_eq!(
            after.iter().map(|emission| emission.role).collect::<Vec<_>>(),
            vec![FakeEmissionRole::Genuine, FakeEmissionRole::Fake, FakeEmissionRole::Fake]
        );
    }

    #[test]
    fn fake_ordering_split_variants_emit_expected_order() {
        let emissions = build_ordered_fake_split_emissions(
            FakeOrder::AllFakesFirst,
            b"A",
            b"FA",
            b"B",
            b"FB",
            3,
            9,
            platform::TcpFlagOverrides::default(),
            platform::TcpFlagOverrides::default(),
        );

        let labels =
            emissions.iter().map(|emission| std::str::from_utf8(emission.payload).unwrap()).collect::<Vec<_>>();

        assert_eq!(labels, vec!["FA", "FB", "A", "B"]);
        assert_eq!(emissions[2].ttl, 3);
        assert_eq!(emissions[3].original_offset, 1);
    }

    #[test]
    fn fake_sequence_sequential_advances_only_fake_offsets() {
        let emissions = build_ordered_fake_split_emissions(
            FakeOrder::BeforeEach,
            b"AAA",
            b"F1",
            b"BBBB",
            b"F222",
            5,
            9,
            platform::TcpFlagOverrides::default(),
            platform::TcpFlagOverrides::default(),
        );

        let segments = ordered_segments_from_emissions(&emissions, FakeSeqMode::Sequential);

        assert_eq!(segments[0].sequence_offset, 0);
        assert_eq!(segments[1].sequence_offset, 0);
        assert_eq!(segments[2].sequence_offset, 2);
        assert_eq!(segments[3].sequence_offset, 3);
    }

    #[test]
    fn actions_delay_does_not_affect_bytes_committed() {
        let (mut client, mut server) = connected_pair();
        let unavailable = default_ttl_unavailable();
        let actions = vec![
            DesyncAction::Write(b"hello".to_vec()),
            DesyncAction::Delay(1), // 1ms delay
            DesyncAction::Write(b"world".to_vec()),
        ];
        let result = execute_tcp_actions(
            &mut client,
            &actions,
            64,
            false,
            Duration::from_millis(50),
            None,
            &unavailable,
            false,
            None,
            None,
        );
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), 5); // last write's len (write_transport_payload returns per-call bytes)

        let mut buf = vec![0u8; 10];
        use std::io::Read;
        server.read_exact(&mut buf).expect("read_exact");
        assert_eq!(&buf, b"helloworld");
    }
}
