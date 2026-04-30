mod full_matrix;
mod opportunistic;
mod primary;
mod rooted;

use super::prelude::*;

pub use full_matrix::build_full_matrix_tcp_candidates;
pub use opportunistic::build_opportunistic_candidates;
pub use primary::build_primary_candidates;
pub use rooted::build_rooted_candidates;

/// Builds the full TCP candidate set for strategy probing: primary +
/// opportunistic + rooted. The probe runner needs all candidates so it can
/// measure effectiveness across platforms; capability filtering (via
/// [`enumerate_capable_candidates`]) is the caller's responsibility when
/// *promoting* a winner for a non-root context.
pub fn build_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_primary_candidates(base);
    candidates.extend(build_opportunistic_candidates(base));
    candidates.extend(build_rooted_candidates(base));
    candidates
}

pub(crate) fn allows_direct_tfo_candidates(base: &ProxyUiConfig) -> bool {
    !base.upstream_relay.enabled || base.upstream_relay.kind.eq_ignore_ascii_case("off")
}
