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

use super::adaptive::{resolve_adaptive_fake_ttl, resolve_tcp_hints_with_evolver};
use super::state::{RuntimeState, DESYNC_SEED_BASE};

#[derive(Debug)]
struct DesyncActionError {
    action: &'static str,
    source: io::Error,
}

impl std::fmt::Display for DesyncActionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "desync action={}: {}", self.action, self.source)
    }
}

impl std::error::Error for DesyncActionError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.source)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct DesyncActionContext {
    pub(super) action: &'static str,
    pub(super) kind: io::ErrorKind,
    pub(super) errno: Option<i32>,
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
) -> io::Result<()> {
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
    if should_desync_tcp(group, context) {
        let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
        match plan_tcp(group, &effective_payload, seed, state.config.network.default_ttl, context) {
            Ok(plan) if requires_special_tcp_execution(group) => {
                execute_tcp_plan(writer, &state.config, group, &plan, seed, resolved_fake_ttl)?;
            }
            Ok(plan) => execute_tcp_actions(
                writer,
                &plan.actions,
                state.config.network.default_ttl,
                state.config.timeouts.wait_send,
                Duration::from_millis(state.config.timeouts.await_interval.max(1) as u64),
            )?,
            Err(_) => writer.write_all(&effective_payload)?,
        }
    } else {
        writer.write_all(&effective_payload)?;
    }
    Ok(())
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some()
}

pub(super) fn requires_special_tcp_execution(group: &DesyncGroup) -> bool {
    let supports_fake_retransmit = platform::supports_fake_retransmit();
    group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake)
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
) -> io::Result<()> {
    // When default_ttl is 0 (auto-detect), lazily read the current TTL on
    // the first SetTtl action so we always have a value to restore.
    let mut cached_restore_ttl: Option<u8> = if default_ttl != 0 { Some(default_ttl) } else { None };
    let mut ttl_modified = false;
    // Some Android builds reject per-socket TTL rewrites at runtime. In that
    // case, continue without the TTL mutation so the connection can still
    // progress instead of failing the whole request.
    let mut ttl_actions_unavailable = false;

    let result = (|| -> io::Result<()> {
        for action in actions {
            match action {
                DesyncAction::Write(bytes) => write_payload_action(writer, bytes)?,
                DesyncAction::WriteUrgent { prefix, urgent_byte } => send_oob_action(writer, prefix, *urgent_byte)?,
                DesyncAction::SetTtl(ttl) => {
                    // Capture current TTL before first modification when auto-detecting.
                    if cached_restore_ttl.is_none() {
                        cached_restore_ttl = platform::detect_default_ttl().ok();
                    }
                    if set_ttl_with_android_fallback(writer, *ttl, &mut ttl_actions_unavailable)? {
                        ttl_modified = true;
                    }
                }
                DesyncAction::RestoreDefaultTtl => {
                    if let Some(restore) = cached_restore_ttl {
                        if restore_default_ttl_with_android_fallback(writer, restore, &mut ttl_actions_unavailable)? {
                            ttl_modified = false;
                        }
                    }
                }
                DesyncAction::SetMd5Sig { key_len } => set_md5sig_action(writer, *key_len)?,
                DesyncAction::AttachDropSack => {}
                DesyncAction::DetachDropSack => {}
                DesyncAction::AwaitWritable => await_writable_action(writer, wait_send, await_interval)?,
                DesyncAction::SetWindowClamp(size) => {
                    let _ = platform::set_tcp_window_clamp(writer, *size);
                }
                DesyncAction::RestoreWindowClamp => {
                    let _ = platform::set_tcp_window_clamp(writer, 0);
                }
            }
        }
        Ok(())
    })();

    // Safety net: restore TTL even on early error return.
    if ttl_modified {
        if let Some(restore) = cached_restore_ttl {
            let _ = set_stream_ttl(writer, restore);
        }
    }

    result
}

fn set_ttl_with_android_fallback(stream: &TcpStream, ttl: u8, ttl_actions_unavailable: &mut bool) -> io::Result<bool> {
    if *ttl_actions_unavailable {
        return Ok(false);
    }

    match set_ttl_action(stream, ttl) {
        Ok(()) => Ok(true),
        Err(err) => handle_android_ttl_capability_error(err, ttl_actions_unavailable),
    }
}

fn restore_default_ttl_with_android_fallback(
    stream: &TcpStream,
    ttl: u8,
    ttl_actions_unavailable: &mut bool,
) -> io::Result<bool> {
    if *ttl_actions_unavailable {
        return Ok(false);
    }

    match restore_default_ttl_action(stream, ttl) {
        Ok(()) => Ok(true),
        Err(err) => handle_android_ttl_capability_error(err, ttl_actions_unavailable),
    }
}

fn handle_android_ttl_capability_error(err: io::Error, ttl_actions_unavailable: &mut bool) -> io::Result<bool> {
    if should_ignore_android_ttl_error(&err) {
        *ttl_actions_unavailable = true;
        tracing::warn!("TTL desync action unavailable on this Android build: {err}");
        Ok(false)
    } else {
        Err(err)
    }
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
    err.raw_os_error().or_else(|| {
        err.get_ref()
            .and_then(|inner| inner.downcast_ref::<DesyncActionError>())
            .and_then(|wrapped| wrapped.source.raw_os_error())
    })
}

fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
) -> io::Result<()> {
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
    let send_steps =
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind.is_tls_prelude()).collect::<Vec<_>>();
    if send_steps.len() < plan.steps.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "tcp plan steps exceed configured send steps"));
    }

    let mut cursor = 0usize;
    let mut ttl_actions_unavailable = false;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))?;
        let end = usize::try_from(step.end)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid tcp desync step bounds"));
        }
        let chunk = &plan.tampered[start..end];
        let configured_step = &send_steps[index];

        match step.kind {
            TcpChainStepKind::Split => {
                writer.write_all(chunk)?;
                await_writable_action(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Oob => {
                send_oob_action(writer, chunk, group.actions.oob_data.unwrap_or(b'a'))?;
                await_writable_action(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Disorder => {
                let ttl_modified = set_ttl_with_android_fallback(writer, 1, &mut ttl_actions_unavailable)?;
                writer.write_all(chunk)?;
                await_writable_action(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                )?;
                if ttl_modified {
                    let _ =
                        restore_default_ttl_with_android_fallback(writer, restore_ttl, &mut ttl_actions_unavailable)?;
                }
            }
            TcpChainStepKind::Disoob => {
                let ttl_modified = set_ttl_with_android_fallback(writer, 1, &mut ttl_actions_unavailable)?;
                send_oob_action(writer, chunk, group.actions.oob_data.unwrap_or(b'a'))?;
                await_writable_action(
                    writer,
                    config.timeouts.wait_send,
                    Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                )?;
                if ttl_modified {
                    let _ =
                        restore_default_ttl_with_android_fallback(writer, restore_ttl, &mut ttl_actions_unavailable)?;
                }
            }
            TcpChainStepKind::Fake => {
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let span = chunk.len();
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                let fake_chunk = &fake.bytes[fake.fake_offset..fake_end];
                if fake_chunk.len() != span {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "fake packet prefix length does not match original split span",
                    ));
                }
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    fake_chunk,
                    resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8),
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;
            }
            TcpChainStepKind::FakeSplit => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    writer.write_all(chunk)?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                }
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    &first_fake,
                    fake_ttl,
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;
                platform::send_fake_tcp(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::FakeDisorder => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    let ttl_modified = set_ttl_with_android_fallback(writer, 1, &mut ttl_actions_unavailable)?;
                    writer.write_all(chunk)?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                    if ttl_modified {
                        let _ = restore_default_ttl_with_android_fallback(
                            writer,
                            restore_ttl,
                            &mut ttl_actions_unavailable,
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
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    &first_fake,
                    1,
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;
                platform::send_fake_tcp(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(configured_step, &plan.tampered, start, end, seed) else {
                    writer.write_all(chunk)?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                };

                if start < span.host_start {
                    writer.write_all(&plan.tampered[start..span.host_start])?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                }

                let real_host = &plan.tampered[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(real_host, configured_step.fake_host_template.as_deref(), seed);
                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8),
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;

                if let Some(midhost) = span.midhost {
                    writer.write_all(&plan.tampered[span.host_start..midhost])?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                    writer.write_all(&plan.tampered[midhost..span.host_end])?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                } else {
                    writer.write_all(real_host)?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                }

                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8),
                    group.actions.md5sig,
                    config.network.default_ttl,
                    (config.timeouts.wait_send, Duration::from_millis(config.timeouts.await_interval.max(1) as u64)),
                )?;

                if span.host_end < end {
                    writer.write_all(&plan.tampered[span.host_end..end])?;
                    await_writable_action(
                        writer,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                    )?;
                }
            }
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "tls prelude step must not appear in tcp send plan",
                ));
            }
        }
        cursor = end;
    }

    if cursor < plan.tampered.len() {
        writer.write_all(&plan.tampered[cursor..])?;
    }
    Ok(())
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
    let ipv4 = socket.set_ttl(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

pub(super) fn desync_action_context(error: &io::Error) -> Option<DesyncActionContext> {
    let wrapped = error.get_ref()?.downcast_ref::<DesyncActionError>()?;
    Some(DesyncActionContext {
        action: wrapped.action,
        kind: wrapped.source.kind(),
        errno: wrapped.source.raw_os_error(),
    })
}

pub(super) fn wrap_desync_action_error(action: &'static str, error: io::Error) -> io::Error {
    let kind = error.kind();
    io::Error::new(kind, DesyncActionError { action, source: error })
}

fn map_desync_action_error<T>(action: &'static str, result: io::Result<T>) -> io::Result<T> {
    result.map_err(|error| wrap_desync_action_error(action, error))
}

fn send_oob_action(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    map_desync_action_error("send_oob", send_out_of_band(writer, prefix, urgent_byte))
}

fn set_ttl_action(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    map_desync_action_error("set_ttl", set_stream_ttl(stream, ttl))
}

fn restore_default_ttl_action(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    map_desync_action_error("restore_default_ttl", set_stream_ttl(stream, ttl))
}

fn set_md5sig_action(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    map_desync_action_error("set_md5sig", platform::set_tcp_md5sig(stream, key_len))
}

fn write_payload_action(stream: &mut TcpStream, bytes: &[u8]) -> io::Result<()> {
    map_desync_action_error("write", stream.write_all(bytes))
}

fn await_writable_action(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    map_desync_action_error("await_writable", platform::wait_tcp_stage(stream, wait_send, await_interval))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{NumericRange, OffsetExpr, TcpChainStep};

    fn test_group() -> DesyncGroup {
        DesyncGroup::new(0)
    }

    fn test_offset() -> OffsetExpr {
        OffsetExpr::absolute(0)
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
    }

    #[test]
    fn desync_action_context_preserves_action_name_and_error_details() {
        let err =
            map_desync_action_error::<()>("set_ttl", Err(io::Error::from_raw_os_error(libc::EINVAL))).unwrap_err();

        let context = desync_action_context(&err).expect("wrapped desync action");
        assert_eq!(context.action, "set_ttl");
        assert_eq!(context.kind, io::ErrorKind::InvalidInput);
        assert_eq!(context.errno, Some(libc::EINVAL));
        assert!(err.to_string().contains("desync action=set_ttl"));
    }

    #[test]
    fn android_ttl_fallback_filter_matches_capability_errors_only() {
        assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EROFS)));
        assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EINVAL)));
        assert!(!should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::ECONNRESET)));
    }

    #[test]
    fn android_ttl_fallback_filter_matches_wrapped_desync_errors() {
        let err = wrap_desync_action_error("set_ttl", io::Error::from_raw_os_error(libc::EROFS));
        assert!(should_ignore_android_ttl_error(&err));
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
