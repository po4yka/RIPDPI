mod capability;
mod conversion;
mod fake_tcp;
mod flagged_payload;
mod fragmentation;
mod multi_disorder;
mod ordered_segments;
mod payload_sender;
mod seq_overlap;
mod socket_options;

use std::net::TcpStream;

use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;

use crate::sync::AtomicBool;

pub use ripdpi_desync_runtime::{primary_tcp_strategy_family, OutboundSendError, OutboundSendOutcome, PcapHook};

pub struct RuntimeTcpDesyncPlatform;

pub fn tcp_segment_hint(stream: &TcpStream) -> Option<ripdpi_desync::TcpSegmentHint> {
    capability::tcp_segment_hint(stream)
}

pub fn tcp_activation_state(stream: &TcpStream) -> Option<crate::platform::tcp::TcpActivationState> {
    capability::tcp_activation_state(stream)
}

pub fn seqovl_supported() -> bool {
    capability::seqovl_supported()
}

#[allow(clippy::too_many_arguments)]
pub fn send_prepared_with_runtime_platform(
    writer: &mut TcpStream,
    config: &ripdpi_config::RuntimeConfig,
    group: &ripdpi_config::DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    context: ripdpi_desync::ActivationContext,
    resolved_fake_ttl: Option<u8>,
    strategy_family_override: Option<&'static str>,
    ttl_unavailable: &AtomicBool,
    pcap_hook: Option<&PcapHook>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    ripdpi_desync_runtime::send_prepared_with_group(
        writer,
        &RuntimeTcpDesyncPlatform,
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

pub fn apply_tcp_capability_policy<'a>(
    group: &'a ripdpi_config::DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
    payload: &[u8],
    progress: ripdpi_session::OutboundProgress,
) -> (std::borrow::Cow<'a, ripdpi_config::DesyncGroup>, Option<&'static str>) {
    ripdpi_desync_runtime::apply_tcp_capability_policy(group, capability, payload, progress)
}
