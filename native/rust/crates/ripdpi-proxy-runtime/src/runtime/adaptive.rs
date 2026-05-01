// Adaptive runtime glue.
//
// Policy-heavy learning and capability decisions live in `ripdpi-runtime-policy`
// and `ripdpi-runtime-adaptive`. This module keeps the proxy-runtime-facing
// facade stable for transport code while splitting lock access, evolver
// fallback, and telemetry adaptation into focused implementation modules.

mod direct_path;
mod evolver;
mod fake_ttl;
mod hints;

pub(super) use direct_path::{
    emit_due_direct_path_learning_timeouts, note_direct_path_all_ips_failed, note_direct_path_quic_success,
    note_direct_path_tcp_success, note_direct_path_tls_post_client_hello_failure, note_direct_path_transport_attempt,
    note_direct_path_udp_failure, note_direct_path_udp_suppressed, now_millis,
};
pub(super) use evolver::{
    note_evolver_failure, note_evolver_success, resolve_tcp_hints_with_evolver, resolve_udp_hints_with_evolver,
};
pub(super) use fake_ttl::{
    note_adaptive_fake_ttl_failure, note_adaptive_fake_ttl_success, note_server_ttl_for_route,
    resolve_adaptive_fake_ttl,
};
pub(super) use hints::{
    note_adaptive_tcp_failure, note_adaptive_tcp_success, note_adaptive_udp_failure, note_adaptive_udp_success,
    resolve_adaptive_tcp_hints, resolve_adaptive_udp_hints,
};
pub(super) use ripdpi_runtime_adaptive::strategy_context::{
    capability_blocks_transport, direct_path_capability_for_route, direct_path_capability_for_targets,
    network_scope_key,
};
