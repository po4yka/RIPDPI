use super::TcpHostFakeExecContext;
use crate::transport_io::await_writable_action_named;
use crate::types::OutboundSendError;

pub(super) const AWAIT_WRITABLE_HOSTFAKE_ACTION: &str = "await_writable_hostfake";

pub(super) fn hostfake_pacing(ctx: &TcpHostFakeExecContext<'_>) -> crate::platform::TcpStageWait {
    (ctx.config.timeouts.wait_send, std::time::Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64))
}

pub(super) fn await_hostfake_writable(
    ctx: &mut TcpHostFakeExecContext<'_>,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    let (wait_send, await_interval) = hostfake_pacing(ctx);
    await_writable_action_named(
        ctx.writer,
        wait_send,
        await_interval,
        AWAIT_WRITABLE_HOSTFAKE_ACTION,
        step_family,
        step_fallback,
        bytes_committed,
    )
}
