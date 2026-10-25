use super::TcpHostFakeExecContext;
use super::pacing::hostfake_pacing;
use crate::platform;
use crate::transport_io::send_ordered_fake_segments_action_named;
use crate::types::OutboundSendError;

const SEND_FAKE_HOSTFAKE_ACTION: &str = "send_fake_hostfake";

pub(super) fn send_hostfake_ordered_segments(
    ctx: &mut TcpHostFakeExecContext<'_>,
    ordered_segments: &[platform::OrderedTcpSegment<'_>],
    original_len: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    let timestamp_delta_ticks =
        ctx.group.actions.fake_tcp_timestamp_enabled.then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
    send_ordered_fake_segments_action_named(
        ctx.writer,
        ordered_segments,
        original_len,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        ctx.md5sig,
        timestamp_delta_ticks,
        ctx.group.actions.ip_id_mode,
        hostfake_pacing(ctx),
        SEND_FAKE_HOSTFAKE_ACTION,
        step_family,
        step_fallback,
        bytes_committed,
    )
}
