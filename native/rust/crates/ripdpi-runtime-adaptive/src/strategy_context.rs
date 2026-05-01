mod authority;
mod direct_path_capability;
mod hint_merge;
mod host_taxonomy;
mod learning_context;
mod payload_classification;
mod reachability;
mod resolver_health;

pub use direct_path_capability::{
    capability_blocks_transport, capability_preserves_udp_transport, capability_requires_desync_fallback,
    capability_udp_clean, direct_path_capability_for_route, direct_path_capability_for_targets,
};
pub use hint_merge::merge_udp_hints_with_capability;
pub use host_taxonomy::hosting_family_context;
pub use learning_context::{network_scope_key, tcp_learning_context, udp_learning_context};
pub use reachability::reachability_set_context;

#[cfg(test)]
mod tests;
