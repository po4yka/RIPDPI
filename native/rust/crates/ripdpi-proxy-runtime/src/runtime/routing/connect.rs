#[cfg(all(test, not(feature = "loom")))]
mod candidate_guard_tests;
mod error;
mod plan;
mod post_connect;
mod socket;
mod socks;
pub(in crate::runtime) use socks::connect_remote_domain;

pub(in crate::runtime::routing) use plan::{
    connect_target_candidates_via_group, connect_target_candidates_with_policy, missing_group_error,
};
pub(in crate::runtime) use socket::connect_socket;
