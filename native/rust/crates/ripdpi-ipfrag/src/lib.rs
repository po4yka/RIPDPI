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
pub use types::{BuildError, IpFragmentPair, Ipv6ExtHeaders, TcpFragmentSpec, TcpTimestampOption, UdpFragmentSpec};
pub use udp::build_udp_fragment_pair;

#[cfg(test)]
mod tests;
