use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn parse_socks5_udp_packet<'a>(
        &self,
        packet: &'a [u8],
        resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, &'a [u8])> {
        runtime_parse_socks5_udp_packet(&self.udp_packet_parser, packet, resolve_name)
    }
    pub(in crate::runtime) fn encode_socks5_udp_packet(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
        encode_socks5_udp_packet(target, payload)
    }
    pub(in crate::runtime) fn classify_udp_payload(&self, payload: &[u8]) -> UdpPayloadInfo {
        runtime_classify_udp_payload(&self.udp_payload_classifier, payload)
    }
    pub(in crate::runtime) fn udp_flow_limit(&self) -> usize {
        self.udp_flow_limit
    }
    pub(in crate::runtime) fn udp_flow_at_capacity(
        flow_exists: bool,
        active_flow_count: usize,
        flow_limit: usize,
    ) -> bool {
        udp_flow_at_capacity(flow_exists, active_flow_count, flow_limit)
    }
    pub(in crate::runtime) fn udp_flow_group_policy(&self, group_index: usize) -> Option<UdpFlowGroupPolicy> {
        let group = udp_group_settings_with(&self.udp_group_settings, group_index)?;
        Some(UdpFlowGroupPolicy {
            socket: RuntimeUdpSocketSettings { bind_low_port: group.socket.bind_low_port },
            packet: runtime_udp_packet_settings(group.packet),
            source_rebind: RuntimeUdpSourceRebindPolicy { after_handshake: group.source_rebind.after_handshake },
        })
    }
    pub(in crate::runtime) fn should_rebind_udp_flow_source_port(
        policy: RuntimeUdpSourceRebindPolicy,
        quic_migrated: bool,
        round_count: u32,
        inbound_payload: &[u8],
    ) -> bool {
        should_rebind_udp_source_port_with(policy.into_adapter(), quic_migrated, round_count, inbound_payload)
    }
}
