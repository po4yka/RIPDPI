mod error;
mod plan;
mod post_connect;
mod socket;
mod socks;

pub(in crate::runtime::routing) use plan::connect_target_candidates_via_group;
pub(in crate::runtime) use socket::connect_socket;
