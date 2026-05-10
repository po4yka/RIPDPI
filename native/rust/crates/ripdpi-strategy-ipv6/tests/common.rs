#![allow(dead_code)]

use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, Dissect, FlowDirection, FlowId, RuntimeCapability, StrategyContext,
};

pub fn ipv6_tcp_packet() -> Vec<u8> {
    let mut packet = Vec::new();
    packet.extend_from_slice(&[
        0x60, 0, 0, 0, 0, 20, 6, 64, 0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x20, 0x01, 0x0d,
        0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02,
    ]);
    packet.extend_from_slice(&[0x9c, 0x40, 0x01, 0xbb, 0, 0, 0, 1, 0, 0, 0, 0, 0x50, 0x02, 0x20, 0, 0, 0, 0, 0]);
    let checksum = ripdpi_strategy_ipv6::ipv6_tcp_checksum(&packet).expect("checksum");
    packet[56..58].copy_from_slice(&checksum.to_be_bytes());
    packet
}

pub fn vpn_ipv6_context<'a>(
    dissect: &'a Dissect,
    conn: &'a ConnectionState,
    caps: &'a Capabilities,
    payload: &'a [u8],
) -> StrategyContext<'a> {
    StrategyContext { dissect, conn, caps, flow_id: FlowId(1), payload, direction: FlowDirection::Outbound }
}

pub fn vpn_caps() -> Capabilities {
    Capabilities { tier: Default::default(), available: vec![RuntimeCapability::VpnMode] }
}
