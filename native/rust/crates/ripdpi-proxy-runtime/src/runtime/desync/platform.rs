use std::net::TcpStream;

use ripdpi_proxy_runtime_adapter::desync_platform::TcpActivationProbe;
use ripdpi_proxy_runtime_adapter::desync_platform::{OutboundSendError, OutboundSendOutcome, PcapHook};
use ripdpi_proxy_runtime_adapter::model::desync::{
    ActivationContext, ActivationTransport, AdaptivePlannerHints, TcpSegmentHint,
};
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use crate::sync::AtomicBool;

pub(super) fn tcp_segment_hint(stream: &TcpStream) -> Option<TcpSegmentHint> {
    ripdpi_proxy_runtime_adapter::desync_platform::tcp_segment_hint(stream)
}

pub(super) fn tcp_activation_state(stream: &TcpStream) -> Option<TcpActivationProbe> {
    ripdpi_proxy_runtime_adapter::desync_platform::tcp_activation_state(stream)
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

pub(crate) fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    payload: Option<&[u8]>,
    tcp_segment_hint: Option<TcpSegmentHint>,
    tcp_activation_state: Option<TcpActivationProbe>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    ripdpi_proxy_runtime_adapter::desync_platform::activation_context_from_progress(
        progress,
        transport,
        payload,
        tcp_segment_hint,
        tcp_activation_state,
        resolved_fake_ttl,
        adaptive,
    )
}
