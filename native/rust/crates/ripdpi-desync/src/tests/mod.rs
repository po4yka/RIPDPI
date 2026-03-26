mod fake;
mod offset;
mod plan_tcp;
mod plan_udp;
mod tls_prelude;

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
    parse_tls, second_level_domain_span, tamper_quic_initial_split_sni, tamper_quic_version, tls_marker_info,
    HttpFakeProfile, OracleRng, TlsFakeProfile, UdpFakeProfile, DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS, IS_HTTP,
    MH_METHODEOL, MH_UNIXEOL, QUIC_V2_VERSION,
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
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

pub(super) fn tlsrandrec_step(marker: i64, count: i32, min_size: i32, max_size: i32) -> TcpChainStep {
    TcpChainStep {
        kind: TcpChainStepKind::TlsRandRec,
        offset: OffsetExpr::absolute(marker),
        activation_filter: None,
        midhost_offset: None,
        fake_host_template: None,
        fragment_count: count,
        min_fragment_size: min_size,
        max_fragment_size: max_size,
    }
}
