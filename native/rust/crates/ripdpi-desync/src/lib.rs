#![forbid(unsafe_code)]

mod fake;
mod first_flight_ir;
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
pub use first_flight_ir::{
    normalize_quic_initial, normalize_tls_client_hello, DesiredBoundaryPlan, GreaseProfile, QuicCryptoFrameBoundary,
    QuicInitialIr, TlsClientHelloIr, TlsExtensionBoundary, TlsRecordBoundary,
};
pub use tls_prelude::{apply_tls_template_record_choreography, plan_tls_template_first_flight};
pub use types::{
    activation_filter_matches, ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints,
    AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile, DesyncAction, DesyncError, DesyncPlan, FakePacketPlan,
    HostFakeSpan, PlannedStep, ProtoInfo, TamperResult, TcpSegmentHint,
};

#[cfg(test)]
mod tests;
