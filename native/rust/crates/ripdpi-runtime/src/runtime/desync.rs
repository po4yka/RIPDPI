use std::borrow::Cow;
use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use crate::platform;
use ripdpi_config::{DesyncGroup, EntropyMode, RuntimeConfig, TcpChainStepKind};
use ripdpi_desync::{
    activation_filter_matches, build_fake_packet, build_fake_region_bytes, build_hostfake_bytes, plan_tcp,
    resolve_hostfake_span, ActivationContext, ActivationTransport, AdaptivePlannerHints, DesyncAction, DesyncPlan,
};
use ripdpi_packets::entropy;
use ripdpi_session::OutboundProgress;
use socket2::SockRef;

use crate::sync::{AtomicBool, Ordering};

use super::adaptive::{resolve_adaptive_fake_ttl, resolve_tcp_hints_with_evolver};
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

pub(super) fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    tcp_segment_hint: Option<ripdpi_desync::TcpSegmentHint>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        seqovl_supported: platform::seqovl_supported(),
        transport,
        tcp_segment_hint,
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
    let resolved_fake_ttl = resolve_adaptive_fake_ttl(state, target, group_index, group, host)?;
    let adaptive_hints = resolve_tcp_hints_with_evolver(state, target, group_index, group, host, payload)?;
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        platform::tcp_segment_hint(writer).ok().flatten(),
        resolved_fake_ttl,
        adaptive_hints,
    );
    let effective_payload = apply_entropy_padding(group, payload, adaptive_hints.entropy_mode);
    let strategy_family = primary_tcp_strategy_family(group);
    if should_desync_tcp(group, context) {
        let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
        match plan_tcp(group, &effective_payload, seed, state.config.network.default_ttl, context) {
            Ok(plan) if requires_special_tcp_execution(group) => {
                let bytes_committed = execute_tcp_plan(
                    writer,
                    &state.config,
                    group,
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

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some()
}

fn primary_tcp_strategy_family(group: &DesyncGroup) -> Option<&'static str> {
    let chain = group.effective_tcp_chain();
    let has_tls_prelude = chain.iter().any(|step| step.kind.is_tls_prelude());
    chain.into_iter().find(|step| !step.kind.is_tls_prelude()).map(|step| match step.kind {
        TcpChainStepKind::Split => "split",
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
    })
}

fn execute_tcp_actions(
    writer: &mut TcpStream,
    actions: &[DesyncAction],
    default_ttl: u8,
    wait_send: bool,
    await_interval: Duration,
    strategy_family: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
    md5sig: bool,
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
                DesyncAction::WriteIpFragmentedTcp { bytes, split_offset } => {
                    if let Some(strategy_family) = strategy_family {
                        match send_ip_fragmented_tcp_action_named(
                            writer,
                            bytes,
                            *split_offset,
                            default_ttl,
                            None,
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
                        match platform::send_ip_fragmented_tcp(writer, bytes, *split_offset, default_ttl, None) {
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
                    match platform::send_seqovl_tcp(writer, real_chunk, fake_prefix, default_ttl, None, md5sig) {
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
    let fake =
        if plan.steps.iter().any(|step| {
            matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
        }) {
            Some(build_fake_packet(group, &plan.tampered, seed).map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync")
            })?)
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
        return execute_multi_disorder_tcp_plan(writer, config, &send_steps, plan, strategy_family, md5sig);
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
            TcpChainStepKind::Split => "split",
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
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => strategy_family.unwrap_or("tlsrec"),
        };
        let step_fallback = strategy_fallback_family(step_family);

        match step.kind {
            TcpChainStepKind::Split => {
                bytes_committed = write_strategy_payload_named(
                    writer,
                    chunk,
                    "write_split",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    "await_writable_split",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            TcpChainStepKind::SeqOverlap => {
                bytes_committed = write_strategy_payload_named(
                    writer,
                    chunk,
                    "write_seqovl",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    "await_writable_seqovl",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            TcpChainStepKind::Oob => {
                bytes_committed = send_oob_action_named(
                    writer,
                    chunk,
                    group.actions.oob_data.unwrap_or(b'a'),
                    "send_oob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    "await_writable_oob",
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
                await_writable_action_named(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    "await_writable_disorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
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
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let span = chunk.len();
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                let fake_chunk = &fake.bytes[fake.fake_offset..fake_end];
                if fake_chunk.len() != span {
                    return Err(OutboundSendError::Transport(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "fake packet prefix length does not match original split span",
                    )));
                }
                bytes_committed = send_fake_tcp_action_named(
                    writer,
                    chunk,
                    fake_chunk,
                    resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8),
                    md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                    "send_fake",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
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
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                bytes_committed = send_fake_tcp_action_named(
                    writer,
                    chunk,
                    &first_fake,
                    fake_ttl,
                    md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
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
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                    "send_fake_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
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
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                match send_fake_tcp_action_named(
                    writer,
                    chunk,
                    &first_fake,
                    1,
                    md5sig,
                    config.network.default_ttl,
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
                let fake_host = build_hostfake_bytes(real_host, configured_step.fake_host_template.as_deref(), seed);
                bytes_committed = send_fake_tcp_action_named(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8),
                    md5sig,
                    config.network.default_ttl,
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
                    resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8),
                    md5sig,
                    config.network.default_ttl,
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
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, wait),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_prefix.len())
}

#[allow(clippy::too_many_arguments)]
fn send_ip_fragmented_tcp_action_named(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_ip_fragmented_tcp(stream, payload, split_offset, default_ttl, protect_path),
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
}
