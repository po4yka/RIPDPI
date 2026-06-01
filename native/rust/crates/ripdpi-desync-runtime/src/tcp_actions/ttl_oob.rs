use std::net::TcpStream;

use super::accounting::{ActionContext, FallbackAccounting};

use crate::strategy_family::{restore_ttl_action_name, set_ttl_action_name, write_action_name};
use crate::tcp_lowering::{
    TcpLoweringCapabilities, send_oob_with_android_ttl_fallback, write_payload_with_android_ttl_fallback,
};
use crate::transport_io::{send_oob_action_named, send_transport_oob_payload};
use crate::types::OutboundSendError;

pub(super) struct TtlOobActionExecutor;

impl TtlOobActionExecutor {
    pub(super) fn write_payload_with_ttl_fallback(
        writer: &mut TcpStream,
        bytes: &[u8],
        lowering_caps: &mut TcpLoweringCapabilities,
        ttl_modified: &mut bool,
        context: &ActionContext,
        accounting: &mut FallbackAccounting,
    ) -> Result<bool, OutboundSendError> {
        let Some(strategy_family) = context.strategy_family else {
            return Ok(false);
        };
        if !accounting.has_android_ttl_fallback() || !*ttl_modified {
            return Ok(false);
        }

        let (should_restore_ttl, committed) = write_payload_with_android_ttl_fallback(
            lowering_caps,
            writer,
            bytes,
            *ttl_modified,
            write_action_name(strategy_family),
            restore_ttl_action_name(strategy_family),
            strategy_family,
            accounting.fallback(),
            accounting.bytes_committed(),
        )?;
        *ttl_modified = should_restore_ttl;
        accounting.set_bytes_committed(committed);
        Ok(true)
    }

    pub(super) fn write_urgent(
        writer: &mut TcpStream,
        prefix: &[u8],
        urgent_byte: u8,
        lowering_caps: &mut TcpLoweringCapabilities,
        ttl_modified: &mut bool,
        context: &ActionContext,
        accounting: &mut FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        if let Some(strategy_family) = context.strategy_family {
            let action_name = oob_action_name(strategy_family);
            if accounting.has_android_ttl_fallback() && *ttl_modified {
                let (should_restore_ttl, committed) = send_oob_with_android_ttl_fallback(
                    lowering_caps,
                    writer,
                    prefix,
                    urgent_byte,
                    *ttl_modified,
                    action_name,
                    restore_ttl_action_name(strategy_family),
                    strategy_family,
                    accounting.fallback(),
                    accounting.bytes_committed(),
                )?;
                *ttl_modified = should_restore_ttl;
                accounting.set_bytes_committed(committed);
            } else {
                let committed = send_oob_action_named(
                    writer,
                    prefix,
                    urgent_byte,
                    action_name,
                    strategy_family,
                    accounting.fallback(),
                    accounting.bytes_committed(),
                )?;
                accounting.set_bytes_committed(committed);
            }
        } else {
            let committed = send_transport_oob_payload(writer, prefix, urgent_byte)?;
            accounting.set_bytes_committed(committed);
        }
        Ok(())
    }

    pub(super) fn set_ttl(
        writer: &mut TcpStream,
        ttl: u8,
        lowering_caps: &mut TcpLoweringCapabilities,
        ttl_modified: &mut bool,
        context: &ActionContext,
        accounting: &FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        if lowering_caps.set_ttl_named(
            writer,
            ttl,
            context.strategy_family.map_or("set_ttl", set_ttl_action_name),
            context.strategy_family.unwrap_or("split"),
            accounting.fallback(),
            accounting.bytes_committed(),
        )? {
            *ttl_modified = true;
        }
        Ok(())
    }

    pub(super) fn restore_default_ttl(
        writer: &mut TcpStream,
        cached_restore_ttl: Option<u8>,
        lowering_caps: &mut TcpLoweringCapabilities,
        ttl_modified: &mut bool,
        context: &ActionContext,
        accounting: &FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        if let Some(restore) = cached_restore_ttl
            && lowering_caps.restore_default_ttl_named(
                writer,
                restore,
                context.strategy_family.map_or("restore_default_ttl", restore_ttl_action_name),
                context.strategy_family.unwrap_or("split"),
                accounting.fallback(),
                accounting.bytes_committed(),
            )?
        {
            *ttl_modified = false;
        }
        Ok(())
    }
}

fn oob_action_name(strategy_family: &str) -> &'static str {
    match strategy_family {
        "disoob" => "send_oob_disoob",
        _ => "send_oob",
    }
}
