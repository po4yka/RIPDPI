mod error;
mod plan;
mod post_connect;
mod socket;
mod socks;

pub(in crate::runtime::routing) use plan::{connect_target_candidates_via_group, group_requests_direct_syn_data_tfo};
pub(in crate::runtime) use socket::connect_socket;
#[cfg(test)]
pub(in crate::runtime) use socks::encode_upstream_socks_connect;
