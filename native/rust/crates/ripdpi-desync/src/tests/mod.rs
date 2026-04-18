mod fake;
mod first_flight_ir;
mod offset;
mod phase11_goldens;
mod phase4_goldens;
mod plan_tcp;
mod plan_udp;
mod tls_prelude;

#[allow(dead_code)]
mod rust_packet_seeds {
    include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
}

use crate::fake::normalize_fake_tls_size;
use crate::offset::gen_offset;
use crate::tls_prelude::apply_tls_prelude_steps;
use crate::*;
use ripdpi_config::{
    ActivationFilter, DesyncGroup, NumericRange, OffsetBase, OffsetExpr, QuicFakeProfile, TcpChainStep,
    TcpChainStepKind, UdpChainStep, UdpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
};
use ripdpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat, http_marker_info, parse_http, parse_quic_initial,
    parse_quic_initial_layout, parse_tls, second_level_domain_span, tls_marker_info, HttpFakeProfile, OracleRng,
    TlsFakeProfile, UdpFakeProfile, DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS, IS_HTTP, IS_HTTPS, MH_METHODEOL, MH_UNIXEOL,
    QUIC_V2_VERSION,
};

pub(super) fn split_expr(pos: i64) -> OffsetExpr {
    OffsetExpr::absolute(pos).with_repeat_skip(1, 0)
}

pub(super) fn tcp_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        seqovl_supported: false,
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

pub(super) fn tlsrandrec_step(marker: i64, count: i32, min_size: i32, max_size: i32) -> TcpChainStep {
    TcpChainStep {
        fragment_count: count,
        min_fragment_size: min_size,
        max_fragment_size: max_size,
        ..TcpChainStep::new(TcpChainStepKind::TlsRandRec, OffsetExpr::absolute(marker))
    }
}
