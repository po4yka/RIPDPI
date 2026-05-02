use std::net::TcpStream;

use super::accounting::{ActionContext, FallbackAccounting};

use crate::strategy_family::write_action_name;
use crate::transport_io::{write_strategy_payload_named, write_transport_payload};
use crate::types::OutboundSendError;

pub(super) struct RawWriteActionExecutor;

impl RawWriteActionExecutor {
    pub(super) fn write_payload(
        writer: &mut TcpStream,
        bytes: &[u8],
        context: &ActionContext,
        accounting: &mut FallbackAccounting,
    ) -> Result<(), OutboundSendError> {
        if let Some(strategy_family) = context.strategy_family {
            let committed = write_strategy_payload_named(
                writer,
                bytes,
                write_action_name(strategy_family),
                strategy_family,
                accounting.fallback(),
                accounting.bytes_committed(),
            )?;
            accounting.set_bytes_committed(committed);
        } else {
            let committed = write_transport_payload(writer, bytes)?;
            accounting.set_bytes_committed(committed);
        }
        Ok(())
    }
}
