mod error;
mod plan;
mod post_connect;
mod socket;
mod socks;

pub(in crate::runtime::routing) use plan::connect_target_candidates_via_group;
#[cfg(test)]
pub(in crate::runtime) use ripdpi_proxy_runtime_adapter::model::session::encode_upstream_socks_connect;
pub(in crate::runtime) use socket::connect_socket;
