#![forbid(unsafe_code)]

mod fake;
mod offset;
mod plan_tcp;
mod plan_udp;
mod proto;
mod tls_prelude;
mod types;

pub use self::{plan_tcp::plan_tcp, plan_udp::plan_udp, tls_prelude::apply_tamper};
pub use fake::{
    build_fake_packet, build_fake_region_bytes, build_hostfake_bytes, build_secondary_fake_packet,
    build_seqovl_fake_prefix, resolve_hostfake_span,
};
pub use types::{
    activation_filter_matches, ActivationContext, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
    AdaptiveUdpBurstProfile, DesyncAction, DesyncError, DesyncPlan, FakePacketPlan, HostFakeSpan, PlannedStep,
    ProtoInfo, TamperResult, TcpSegmentHint,
};

#[cfg(test)]
mod tests;
