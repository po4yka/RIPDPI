#![forbid(unsafe_code)]

mod ipv4;
mod ipv6;
mod rst;
mod split;
mod tcp;
mod types;
mod udp;

pub use rst::build_fake_rst_packet;
pub use tcp::build_tcp_fragment_pair;
pub use types::{
    BuildError, IpFragmentPair, Ipv6ExtHeaders, TcpFragmentSpec, TcpTimestampOption, UdpFragmentSpec, TCP_FLAG_ACK,
    TCP_FLAG_AE, TCP_FLAG_CWR, TCP_FLAG_ECE, TCP_FLAG_FIN, TCP_FLAG_PSH, TCP_FLAG_R1, TCP_FLAG_R2, TCP_FLAG_R3,
    TCP_FLAG_RST, TCP_FLAG_SYN, TCP_FLAG_URG,
};
pub use udp::build_udp_fragment_pair;

#[cfg(test)]
mod tests;
