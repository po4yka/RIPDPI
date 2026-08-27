mod endpoint;
mod parse;
mod tcp_flags;
mod tcp_reset;
mod udp_response;

pub(crate) use endpoint::endpoint_to_socketaddr;
pub(crate) use parse::tcp_packet_endpoints;
pub(crate) use tcp_flags::{TcpFlowKey, is_injected_rst, tcp_syn_flow_key};
pub(crate) use tcp_reset::build_tcp_reset;
pub(crate) use udp_response::build_udp_response;

#[cfg(test)]
mod icmp;
#[cfg(test)]
pub(crate) use icmp::build_udp_port_unreachable;
#[cfg(test)]
pub(crate) use parse::tcp_dst_port;
#[cfg(test)]
mod test_fixtures;
#[cfg(test)]
pub(crate) use tcp_flags::is_tcp_syn;
#[cfg(test)]
pub(crate) use test_fixtures::{
    build_ipv4_tcp_ack_packet, build_ipv4_tcp_syn_packet, build_ipv6_tcp_syn_packet, checksum_sum, finalize_checksum,
    tcp_seq_ack,
};

#[cfg(test)]
mod tests {
    include!("packet/tests.rs");
}
