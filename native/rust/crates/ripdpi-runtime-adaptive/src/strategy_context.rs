mod authority;
mod direct_path_capability;
mod hint_merge;
mod payload_classification;
mod preferred_targets;

use ripdpi_config::RuntimeConfig;

pub use direct_path_capability::{
    capability_blocks_transport, capability_preserves_udp_transport, capability_requires_desync_fallback,
    capability_udp_clean, direct_path_capability_for_route, direct_path_capability_for_targets,
};
pub use hint_merge::merge_udp_hints_with_capability;
pub use payload_classification::{classify_learning_payload, retry_lane_for_payload, LearningPayloadClassification};
pub use preferred_targets::{preferred_targets_for_transport, PreferredTargetsDecision};

pub fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.adaptive.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
}

#[cfg(test)]
mod tests;
