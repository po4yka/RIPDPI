use std::net::TcpStream;

use super::accounting::{ActionContext, FallbackAccounting};

use crate::platform;
use crate::strategy_family::{
    log_ipfrag2_flow_fallback, should_fallback_ipfrag2_tcp_error_kind, should_fallback_seqovl_error_kind,
};
use crate::tcp_lowering::should_ignore_android_ttl_error;
use crate::transport_io::{
    log_android_desync_fallback, send_ip_fragmented_tcp_action_named, set_md5sig_action_named,
    set_md5sig_transport_action, write_strategy_payload_named, write_transport_payload,
};
use crate::types::OutboundSendError;

pub(super) struct PrivilegedActionExecutor;

impl PrivilegedActionExecutor {
    pub(super) fn set_md5sig(
        writer: &mut TcpStream,
        key_len: u16,
        context: &ActionContext,
        accounting: &FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        let result = if let Some(strategy_family) = context.strategy_family {
            set_md5sig_action_named(
                writer,
                key_len,
                "set_md5sig",
                strategy_family,
                accounting.fallback(),
                accounting.bytes_committed(),
            )
        } else {
            set_md5sig_transport_action(writer, key_len)
        };

        match result {
            Ok(()) => Ok(()),
            // TCP_MD5SIG is a privileged, best-effort fake-packet enhancement:
            // it needs CAP_NET_ADMIN / root. On a non-rooted device the
            // setsockopt fails with EPERM/EACCES; a kernel without the option
            // reports ENOPROTOOPT/EOPNOTSUPP; a non-Linux platform reports
            // `Unsupported`. Per the non-root baseline, degrade gracefully in
            // all of these cases: skip the md5sig corruption on this fake packet
            // and let the rest of the desync sequence run, rather than aborting
            // the whole connection. A genuinely unexpected error (e.g. EBADF)
            // still propagates so real bugs are not masked. The paired disable
            // (`key_len == 0`) cleanup routes through here too, so it is equally
            // tolerant. Mirrors the TTL / ipfrag2 / seqovl fallbacks.
            Err(err)
                if should_ignore_android_ttl_error(err.source_error())
                    || err.source_error().kind() == std::io::ErrorKind::Unsupported =>
            {
                log_android_desync_fallback("set_md5sig", accounting.fallback().unwrap_or("none"), &err);
                Ok(())
            }
            Err(err) => Err(err),
        }
    }

    pub(super) fn write_ip_fragmented_tcp(
        writer: &mut TcpStream,
        bytes: &[u8],
        split_offset: usize,
        disorder: bool,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        context: &ActionContext,
        accounting: &mut FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        if let Some(strategy_family) = context.strategy_family {
            match send_ip_fragmented_tcp_action_named(
                writer,
                bytes,
                split_offset,
                context.default_ttl,
                None,
                disorder,
                ipv6_ext,
                platform::TcpFlagOverrides::default(),
                context.ip_id_mode,
                "write_ipfrag2",
                strategy_family,
                accounting.fallback(),
                accounting.bytes_committed(),
            ) {
                Ok(committed) => accounting.set_bytes_committed(committed),
                Err(err) if strategy_family == "ipfrag2" && should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
                    log_ipfrag2_flow_fallback(&err);
                    let committed = write_strategy_payload_named(
                        writer,
                        bytes,
                        "write_ipfrag2",
                        strategy_family,
                        accounting.fallback(),
                        accounting.bytes_committed(),
                    )?;
                    accounting.set_bytes_committed(committed);
                }
                Err(err) => return Err(err),
            }
        } else {
            match platform::send_ip_fragmented_tcp(
                writer,
                bytes,
                split_offset,
                context.default_ttl,
                None,
                disorder,
                ipv6_ext,
                platform::TcpFlagOverrides::default(),
                context.ip_id_mode,
            ) {
                Ok(()) => accounting.add_bytes_committed(bytes.len()),
                Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
                    log_ipfrag2_flow_fallback(&err);
                    let committed = write_transport_payload(writer, bytes)?;
                    accounting.set_bytes_committed(committed);
                }
                Err(err) => return Err(OutboundSendError::transport(err)),
            }
        }
        Ok(())
    }

    pub(super) fn write_seq_overlap(
        writer: &mut TcpStream,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        remainder: &[u8],
        context: &ActionContext,
        accounting: &mut FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        match platform::send_seqovl_tcp(
            writer,
            real_chunk,
            fake_prefix,
            context.default_ttl,
            None,
            context.md5sig,
            platform::TcpFlagOverrides::default(),
            context.ip_id_mode,
        ) {
            Ok(()) => {
                accounting.add_bytes_committed(real_chunk.len());
                Self::write_seq_overlap_remainder(writer, remainder, accounting)?;
            }
            Err(err) if should_fallback_seqovl_error_kind(err.kind()) => {
                tracing::warn!("seqovl fallback to split: {err}");
                let committed = write_transport_payload(writer, real_chunk)?;
                accounting.add_bytes_committed(committed);
                Self::write_seq_overlap_remainder(writer, remainder, accounting)?;
            }
            Err(err) => return Err(OutboundSendError::transport(err)),
        }
        Ok(())
    }

    pub(super) fn send_fake_rst(writer: &mut TcpStream, context: &ActionContext) {
        let _ = platform::send_fake_rst(
            writer,
            context.default_ttl,
            None,
            platform::TcpFlagOverrides::default(),
            context.ip_id_mode,
        );
    }

    fn write_seq_overlap_remainder(
        writer: &mut TcpStream,
        remainder: &[u8],
        accounting: &mut FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        if !remainder.is_empty() {
            let committed = write_transport_payload(writer, remainder)?;
            accounting.add_bytes_committed(committed);
        }
        Ok(())
    }
}
