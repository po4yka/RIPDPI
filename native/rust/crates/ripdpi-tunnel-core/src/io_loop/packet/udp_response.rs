mod datagram;
mod fragmentation;

pub(crate) use datagram::build_udp_response;
pub(crate) use fragmentation::build_udp_response_fragments;

#[cfg(test)]
mod fragmentation_tests;
