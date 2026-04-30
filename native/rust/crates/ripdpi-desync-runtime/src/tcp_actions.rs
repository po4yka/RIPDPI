use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_desync::DesyncAction;

use crate::platform;
use crate::strategy_family::{
    await_writable_action_name, log_ipfrag2_flow_fallback, restore_ttl_action_name, set_ttl_action_name,
    should_fallback_ipfrag2_tcp_error_kind, should_fallback_seqovl_error_kind, strategy_fallback_family,
    write_action_name,
};
use crate::sync::AtomicBool;
use crate::tcp_lowering::{
    send_oob_with_android_ttl_fallback, write_payload_with_android_ttl_fallback, TcpLoweringCapabilities,
};
use crate::transport_io::{
    await_transport_writable_action, await_writable_action_named, send_ip_fragmented_tcp_action_named,
    send_oob_action_named, send_transport_oob_payload, set_md5sig_action_named, set_md5sig_transport_action,
    set_stream_ttl, write_strategy_payload_named, write_transport_payload,
};
use crate::types::{OutboundSendError, PcapHook};

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
    let mut bytes_committed = 0usize;
    let fallback = strategy_family.and_then(strategy_fallback_family);

    let result = (|| -> Result<usize, OutboundSendError> {
        for action in actions {
            match action {
                DesyncAction::Write(bytes) => {
                    if let Some(strategy_family) = strategy_family {
                        if fallback.is_some() && ttl_modified {
                            let (should_restore_ttl, committed) = write_payload_with_android_ttl_fallback(
                                &mut lowering_caps,
                                writer,
                                bytes,
                                ttl_modified,
                                write_action_name(strategy_family),
                                restore_ttl_action_name(strategy_family),
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
                                &mut lowering_caps,
                                writer,
                                prefix,
                                *urgent_byte,
                                ttl_modified,
                                match strategy_family {
                                    "disoob" => "send_oob_disoob",
                                    _ => "send_oob",
                                },
                                restore_ttl_action_name(strategy_family),
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
                    if lowering_caps.set_ttl_named(
                        writer,
                        *ttl,
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
                        if lowering_caps.restore_default_ttl_named(
                            writer,
                            restore,
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
                    // std-thread-safe: each connection runs on its own dedicated OS thread
                    // (mio + std::thread, no tokio worker pool). Blocking here is correct.
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
    lowering_caps.persist(session_ttl_unavailable);

    result
}
