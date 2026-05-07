use std::net::TcpStream;

use ripdpi_proxy_runtime_adapter::desync_platform::{OutboundSendError, OutboundSendOutcome, PcapHook};
use ripdpi_proxy_runtime_adapter::model::desync::{ActivationContext, TcpSegmentHint};
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use crate::sync::AtomicBool;

pub(super) fn tcp_segment_hint(stream: &TcpStream) -> Option<TcpSegmentHint> {
    ripdpi_proxy_runtime_adapter::desync_platform::tcp_segment_hint(stream)
}

pub(super) fn tcp_activation_state(
    stream: &TcpStream,
) -> Option<ripdpi_proxy_runtime_adapter::platform::tcp::TcpActivationState> {
    ripdpi_proxy_runtime_adapter::desync_platform::tcp_activation_state(stream)
}

pub(super) fn seqovl_supported() -> bool {
    ripdpi_proxy_runtime_adapter::desync_platform::seqovl_supported()
}

#[allow(clippy::too_many_arguments)]
pub(super) fn send_prepared_with_runtime_platform(
    writer: &mut TcpStream,
    config: &ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig,
    group: &ripdpi_proxy_runtime_adapter::model::config::DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    context: ActivationContext,
    resolved_fake_ttl: Option<u8>,
    strategy_family_override: Option<&'static str>,
    ttl_unavailable: &AtomicBool,
    pcap_hook: Option<&PcapHook>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    ripdpi_proxy_runtime_adapter::desync_platform::send_prepared_with_runtime_platform(
        writer,
        config,
        group,
        payload,
        progress,
        context,
        resolved_fake_ttl,
        strategy_family_override,
        ttl_unavailable,
        pcap_hook,
    )
}
