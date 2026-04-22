#![forbid(unsafe_code)]

mod client_hello_offsets;
mod fake;
mod first_flight_ir;
mod offset;
mod plan_tcp;
mod plan_udp;
mod proto;
mod tls_prelude;
mod types;

#[rustfmt::skip] pub use self::{plan_tcp::plan_tcp, plan_udp::plan_udp, tls_prelude::apply_tamper};
#[rustfmt::skip] pub use client_hello_offsets::{parse_client_hello_offsets, ClientHelloOffsets, ClientHelloOffsetsError};
#[rustfmt::skip] pub use fake::{build_fake_packet, build_fake_region_bytes, build_hostfake_bytes, build_secondary_fake_packet, build_seqovl_fake_prefix, resolve_hostfake_span};
#[rustfmt::skip] pub use first_flight_ir::{normalize_quic_initial, normalize_tls_client_hello, DesiredBoundaryPlan, GreaseProfile, QuicCryptoFrameBoundary, QuicInitialIr, TlsClientHelloIr, TlsExtensionBoundary, TlsRecordBoundary};
#[rustfmt::skip] pub use proto::init_proto_info;
#[rustfmt::skip] pub use tls_prelude::{apply_tls_template_record_choreography, plan_tls_template_first_flight};
#[rustfmt::skip] pub use types::{activation_filter_matches, ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile, DesyncAction, DesyncError, DesyncPlan, FakePacketPlan, HostFakeSpan, PlannedStep, ProtoInfo, TamperResult, TcpSegmentHint};

#[cfg(test)]
mod tests;
