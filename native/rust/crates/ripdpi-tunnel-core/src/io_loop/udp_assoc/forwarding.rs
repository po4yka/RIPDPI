mod allocation;
mod delivery;
mod dispatch;
mod ensure;
mod leases;
mod quic_sni;

pub(in crate::io_loop) use dispatch::forward_udp_payload;
#[cfg(test)]
pub(super) use leases::lease_udp_mapping;
#[cfg(test)]
pub(in crate::io_loop) use quic_sni::record_quic_sni_if_present;
