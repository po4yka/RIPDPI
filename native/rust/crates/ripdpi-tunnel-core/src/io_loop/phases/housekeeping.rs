use super::super::state::LoopState;
use super::super::tcp_accept::gc_stale_pending_listens;
use super::super::{PENDING_LISTEN_GC_INTERVAL, PENDING_LISTEN_TIMEOUT};

pub(in crate::io_loop) fn gc_pending_listens(state: &mut LoopState) {
    state.loop_iteration = state.loop_iteration.wrapping_add(1);
    if state.loop_iteration.is_multiple_of(PENDING_LISTEN_GC_INTERVAL) {
        super::super::udp_assoc::expire_udp_destinations(
            &mut state.udp_associations,
            &mut state.dns_cache,
            state.runtime.udp_idle_timeout,
        );
        gc_stale_pending_listens(&mut state.pending_listens, &mut state.socket_set, PENDING_LISTEN_TIMEOUT);
        state
            .runtime
            .tun_egress_interceptor
            .expire_idle_udp_flows(state.runtime.udp_idle_timeout, &|src| state.udp_associations.contains_key(&src));
    }
}
