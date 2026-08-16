use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_desync::DesyncAction;

mod accounting;
mod privileged;
mod raw_write;
mod ttl_oob;

use accounting::{ActionContext, FallbackAccounting};
use privileged::PrivilegedActionExecutor;
use raw_write::RawWriteActionExecutor;
use ttl_oob::TtlOobActionExecutor;

use crate::platform;
use crate::strategy_family::{await_writable_action_name, strategy_fallback_family};
use crate::sync::AtomicBool;
use crate::tcp_lowering::TcpLoweringCapabilities;
use crate::transport_io::{await_transport_writable_action, await_writable_action_named, set_stream_ttl};
use crate::types::{OutboundSendError, PcapHook, TcpTerminalReason};

const RESTORE_WINDOW_CLAMP: u32 = 1_000_000;

#[allow(clippy::too_many_arguments)]
pub(crate) fn execute_tcp_actions(
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
    // Snapshot the per-connection TTL lowering capabilities once so action
    // execution and plan execution share the same degradation behavior.
    let mut lowering_caps = TcpLoweringCapabilities::snapshot(default_ttl, session_ttl_unavailable);
    let cached_restore_ttl: Option<u8> = Some(lowering_caps.restore_ttl);
    let mut ttl_modified = false;
    let mut accounting = FallbackAccounting::new(strategy_family.and_then(strategy_fallback_family));
    let context = ActionContext { strategy_family, default_ttl, md5sig, ip_id_mode };

    let result = (|| -> Result<usize, OutboundSendError> {
        for action in actions {
            accounting.begin_action();
            match action {
                DesyncAction::Write(bytes) => {
                    let handled_by_ttl_fallback = TtlOobActionExecutor::write_payload_with_ttl_fallback(
                        writer,
                        bytes,
                        &mut lowering_caps,
                        &mut ttl_modified,
                        &context,
                        &mut accounting,
                    )?;
                    if !handled_by_ttl_fallback {
                        RawWriteActionExecutor::write_payload(writer, bytes, &context, &mut accounting)?;
                    }
                    accounting.complete_real_write(bytes.len());
                    if let Some(hook) = pcap_hook {
                        hook(bytes, true);
                    }
                }
                DesyncAction::WriteUrgent { prefix, urgent_byte } => {
                    TtlOobActionExecutor::write_urgent(
                        writer,
                        prefix,
                        *urgent_byte,
                        &mut lowering_caps,
                        &mut ttl_modified,
                        &context,
                        &mut accounting,
                    )?;
                    accounting.complete_real_write(prefix.len().saturating_add(1));
                }
                DesyncAction::SetTtl(ttl) => {
                    TtlOobActionExecutor::set_ttl(
                        writer,
                        *ttl,
                        &mut lowering_caps,
                        &mut ttl_modified,
                        &context,
                        &accounting,
                    )?;
                    accounting.complete_action();
                }
                DesyncAction::RestoreDefaultTtl => {
                    TtlOobActionExecutor::restore_default_ttl(
                        writer,
                        cached_restore_ttl,
                        &mut lowering_caps,
                        &mut ttl_modified,
                        &context,
                        &accounting,
                    )?;
                    accounting.complete_action();
                }
                DesyncAction::SetMd5Sig { key_len } => {
                    PrivilegedActionExecutor::set_md5sig(writer, *key_len, &context, &accounting)?;
                    accounting.complete_action();
                }
                DesyncAction::AttachDropSack => {
                    accounting.complete_action();
                }
                DesyncAction::DetachDropSack => {
                    accounting.complete_action();
                }
                DesyncAction::WriteIpFragmentedTcp { bytes, split_offset, disorder, ipv6_ext } => {
                    PrivilegedActionExecutor::write_ip_fragmented_tcp(
                        writer,
                        bytes,
                        *split_offset,
                        *disorder,
                        *ipv6_ext,
                        &context,
                        &mut accounting,
                    )?;
                    accounting.complete_real_write(bytes.len());
                }
                DesyncAction::WriteSeqOverlap { real_chunk, fake_prefix, remainder } => {
                    PrivilegedActionExecutor::write_seq_overlap(
                        writer,
                        real_chunk,
                        fake_prefix,
                        remainder,
                        &context,
                        &mut accounting,
                    )?;
                    accounting.complete_real_writes(2, real_chunk.len().saturating_add(remainder.len()));
                }
                DesyncAction::WriteIpFragmentedUdp { .. } => {
                    return Err(OutboundSendError::transport(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "udp fragmentation action reached tcp executor",
                    )));
                }
                DesyncAction::AwaitWritable => {
                    if let Some(strategy_family) = context.strategy_family {
                        await_writable_action_named(
                            writer,
                            wait_send,
                            await_interval,
                            await_writable_action_name(strategy_family),
                            strategy_family,
                            accounting.fallback(),
                            accounting.bytes_committed(),
                        )?;
                    } else {
                        await_transport_writable_action(writer, wait_send, await_interval)?;
                    }
                    accounting.complete_await();
                }
                DesyncAction::SetWindowClamp(size) => {
                    let _ = platform::set_tcp_window_clamp(writer, *size);
                    accounting.complete_action();
                }
                DesyncAction::RestoreWindowClamp => {
                    let _ = platform::set_tcp_window_clamp(writer, RESTORE_WINDOW_CLAMP);
                    accounting.complete_action();
                }
                DesyncAction::SetWsize { window } => {
                    let _ = platform::set_tcp_window_clamp(writer, *window);
                    accounting.complete_action();
                }
                DesyncAction::RestoreWsize => {
                    let _ = platform::set_tcp_window_clamp(writer, RESTORE_WINDOW_CLAMP);
                    accounting.complete_action();
                }
                DesyncAction::SendFakeRst => {
                    PrivilegedActionExecutor::send_fake_rst(writer, &context);
                    accounting.complete_action();
                }
                DesyncAction::Delay(ms) => {
                    // std-thread-safe: each connection runs on its own dedicated OS thread
                    // (mio + std::thread, no tokio worker pool). Blocking here is correct.
                    std::thread::sleep(Duration::from_millis(u64::from(*ms)));
                    accounting.complete_action();
                }
            }
        }
        Ok(accounting.bytes_committed())
    })();

    // Safety net: restore TTL even on early error return.
    if ttl_modified && let Some(restore) = cached_restore_ttl {
        let _ = set_stream_ttl(writer, restore);
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    lowering_caps.persist(session_ttl_unavailable);

    result.map_err(|err| {
        let terminal_reason = match &err {
            OutboundSendError::Transport { .. } => TcpTerminalReason::Transport,
            OutboundSendError::StrategyExecution { .. } => TcpTerminalReason::StrategyExecution,
        };
        err.with_execution_receipt(accounting.failure_receipt(strategy_family, terminal_reason))
    })
}
