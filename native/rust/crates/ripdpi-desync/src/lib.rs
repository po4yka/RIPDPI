#![forbid(unsafe_code)]

mod fake;
mod offset;
mod plan_tcp;
mod plan_udp;
mod proto;
mod tls_prelude;
mod types;

pub use fake::{
    build_fake_packet, build_fake_region_bytes, build_hostfake_bytes, build_seqovl_fake_prefix, resolve_hostfake_span,
};
pub use plan_tcp::plan_tcp;
pub use plan_udp::plan_udp;
pub use tls_prelude::apply_tamper;
pub use types::{
    activation_filter_matches, ActivationContext, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
    AdaptiveUdpBurstProfile, DesyncAction, DesyncError, DesyncPlan, FakePacketPlan, HostFakeSpan, PlannedStep,
    ProtoInfo, TamperResult, TcpSegmentHint,
};

#[cfg(test)]
mod tests;
