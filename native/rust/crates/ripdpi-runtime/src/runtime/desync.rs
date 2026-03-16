use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use crate::platform;
use ciadpi_config::{DesyncGroup, RuntimeConfig, TcpChainStepKind};
use ciadpi_desync::{
    activation_filter_matches, build_fake_packet, build_fake_region_bytes, build_hostfake_bytes, plan_tcp,
    resolve_hostfake_span, ActivationContext, ActivationTransport, AdaptivePlannerHints, DesyncAction, DesyncPlan,
};
use ciadpi_session::OutboundProgress;
use socket2::SockRef;

use super::adaptive::{resolve_adaptive_fake_ttl, resolve_adaptive_tcp_hints};
use super::state::{RuntimeState, DESYNC_SEED_BASE};

pub(super) fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    tcp_segment_hint: Option<ciadpi_desync::TcpSegmentHint>,
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
    let adaptive_hints = resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload)?;
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        platform::tcp_segment_hint(writer).ok().flatten(),
        resolved_fake_ttl,
        adaptive_hints,
    );
    if should_desync_tcp(group, context) {
        let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
        match plan_tcp(group, payload, seed, state.config.default_ttl, context) {
            Ok(plan) if requires_special_tcp_execution(group) => {
                execute_tcp_plan(writer, &state.config, group, &plan, seed, resolved_fake_ttl)?;
            }
            Ok(plan) => execute_tcp_actions(
                writer,
                &plan.actions,
                state.config.default_ttl,
                state.config.wait_send,
                Duration::from_millis(state.config.await_interval.max(1) as u64),
            )?,
            Err(_) => writer.write_all(payload)?,
        }
    } else {
        writer.write_all(payload)?;
    }
    Ok(())
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.mod_http != 0 || group.tlsminor.is_some()
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
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => writer.write_all(bytes)?,
            DesyncAction::WriteUrgent { prefix, urgent_byte } => send_out_of_band(writer, prefix, *urgent_byte)?,
            DesyncAction::SetTtl(ttl) => set_stream_ttl(writer, *ttl)?,
            DesyncAction::RestoreDefaultTtl => {
                if default_ttl != 0 {
                    set_stream_ttl(writer, default_ttl)?;
                }
            }
            DesyncAction::SetMd5Sig { key_len } => platform::set_tcp_md5sig(writer, *key_len)?,
            DesyncAction::AttachDropSack => {}
            DesyncAction::DetachDropSack => {}
            DesyncAction::AwaitWritable => platform::wait_tcp_stage(writer, wait_send, await_interval)?,
        }
    }
    Ok(())
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
    let send_steps =
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind.is_tls_prelude()).collect::<Vec<_>>();
    if send_steps.len() < plan.steps.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "tcp plan steps exceed configured send steps"));
    }

    let mut cursor = 0usize;
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
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Oob => {
                send_out_of_band(writer, chunk, group.oob_data.unwrap_or(b'a'))?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Disorder => {
                set_stream_ttl(writer, 1)?;
                writer.write_all(chunk)?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
                if config.default_ttl != 0 {
                    set_stream_ttl(writer, config.default_ttl)?;
                }
            }
            TcpChainStepKind::Disoob => {
                set_stream_ttl(writer, 1)?;
                send_out_of_band(writer, chunk, group.oob_data.unwrap_or(b'a'))?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
                if config.default_ttl != 0 {
                    set_stream_ttl(writer, config.default_ttl)?;
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
                    resolved_fake_ttl.or(group.ttl).unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
            }
            TcpChainStepKind::FakeSplit => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                }
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.ttl).unwrap_or(8);
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    &first_fake,
                    fake_ttl,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                platform::send_fake_tcp(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::FakeDisorder => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    set_stream_ttl(writer, 1)?;
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    if config.default_ttl != 0 {
                        set_stream_ttl(writer, config.default_ttl)?;
                    }
                    cursor = end;
                    continue;
                }
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.ttl).unwrap_or(8);
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    &first_fake,
                    1,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                platform::send_fake_tcp(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(configured_step, &plan.tampered, start, end, seed) else {
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                };

                if start < span.host_start {
                    writer.write_all(&plan.tampered[start..span.host_start])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }

                let real_host = &plan.tampered[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(real_host, configured_step.fake_host_template.as_deref(), seed);
                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.ttl).unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;

                if let Some(midhost) = span.midhost {
                    writer.write_all(&plan.tampered[span.host_start..midhost])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    writer.write_all(&plan.tampered[midhost..span.host_end])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                } else {
                    writer.write_all(real_host)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }

                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.ttl).unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;

                if span.host_end < end {
                    writer.write_all(&plan.tampered[span.host_end..end])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
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

#[cfg(test)]
mod tests {
    use super::*;
    use ciadpi_config::{NumericRange, OffsetExpr, TcpChainStep};

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

        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));
        assert!(has_tcp_actions(&group));
        assert!(should_desync_tcp(&group, in_range));
        assert!(!should_desync_tcp(&group, out_of_range));
    }

    #[test]
    fn special_tcp_execution_includes_fake_approximation_steps() {
        let mut group = test_group();
        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset()));
        assert_eq!(requires_special_tcp_execution(&group), platform::supports_fake_retransmit());

        group.tcp_chain.clear();
        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, test_offset()));
        assert_eq!(requires_special_tcp_execution(&group), platform::supports_fake_retransmit());

        group.tcp_chain.clear();
        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, test_offset()));
        assert!(requires_special_tcp_execution(&group));
    }
}
