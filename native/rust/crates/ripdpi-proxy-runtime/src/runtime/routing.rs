mod connect;
mod failure;
mod policy;
mod retry;

pub(super) use connect::connect_socket;
pub(super) use failure::{
    advance_route_for_failure, classify_response_failure, emit_failure_classified, note_block_signal_for_failure,
    should_track_strategy_target,
};
pub(super) use policy::{
    note_route_success, note_route_success_for_transport, preferred_targets_for_transport, runtime_supports_trigger,
    select_route, select_route_for_transport,
};
pub(super) use retry::{
    connect_target, connect_target_with_route, reconnect_target, reconnect_target_without_tfo,
    route_uses_direct_syn_data_tfo,
};

#[cfg(test)]
pub(super) use connect::encode_upstream_socks_connect;
#[cfg(test)]
pub(super) use failure::trigger_flag;
#[cfg(test)]
pub(super) use ripdpi_proxy_runtime_adapter::response_triggers::{failure_penalizes_strategy, failure_trigger_mask};
