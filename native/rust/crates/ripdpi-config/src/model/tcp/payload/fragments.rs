use crate::TcpChainStep;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpIpFragPayload {
    pub fragment_count: i32,
    pub min_fragment_size: i32,
    pub max_fragment_size: i32,
    pub disorder: bool,
    pub ipv6_extensions: TcpIpv6ExtensionPayload,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpIpv6ExtensionPayload {
    pub hop_by_hop: bool,
    pub dest_opt: bool,
    pub dest_opt2: bool,
    pub routing: bool,
    pub second_frag_next_override: Option<u8>,
}

impl TcpChainStep {
    pub fn with_ip_frag_payload(mut self, payload: TcpIpFragPayload) -> Self {
        self.apply_ip_frag_payload(payload);
        self
    }

    pub(crate) fn apply_ip_frag_payload(&mut self, payload: TcpIpFragPayload) {
        self.payload.set_ip_frag_payload(payload);
    }

    pub fn ip_frag_payload(&self) -> Option<TcpIpFragPayload> {
        self.payload.ip_frag_payload()
    }

    pub const fn ipv6_extension_payload(&self) -> TcpIpv6ExtensionPayload {
        self.payload.ipv6_extension_payload()
    }

    pub(crate) const fn fragment_storage_active(&self) -> bool {
        self.payload.fragment_storage_active()
    }
}
