use crate::{TcpChainStep, TcpChainStepKind};

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

    pub fn apply_ip_frag_payload(&mut self, payload: TcpIpFragPayload) {
        self.fragment_count = payload.fragment_count;
        self.min_fragment_size = payload.min_fragment_size;
        self.max_fragment_size = payload.max_fragment_size;
        self.ip_frag_disorder = payload.disorder;
        self.ipv6_hop_by_hop = payload.ipv6_extensions.hop_by_hop;
        self.ipv6_dest_opt = payload.ipv6_extensions.dest_opt;
        self.ipv6_dest_opt2 = payload.ipv6_extensions.dest_opt2;
        self.ipv6_routing = payload.ipv6_extensions.routing;
        self.ipv6_frag_next_override = payload.ipv6_extensions.second_frag_next_override;
    }

    pub fn ip_frag_payload(&self) -> Option<TcpIpFragPayload> {
        if self.kind == TcpChainStepKind::IpFrag2 {
            Some(TcpIpFragPayload {
                fragment_count: self.fragment_count,
                min_fragment_size: self.min_fragment_size,
                max_fragment_size: self.max_fragment_size,
                disorder: self.ip_frag_disorder,
                ipv6_extensions: self.ipv6_extension_payload(),
            })
        } else {
            None
        }
    }

    pub const fn ipv6_extension_payload(&self) -> TcpIpv6ExtensionPayload {
        TcpIpv6ExtensionPayload {
            hop_by_hop: self.ipv6_hop_by_hop,
            dest_opt: self.ipv6_dest_opt,
            dest_opt2: self.ipv6_dest_opt2,
            routing: self.ipv6_routing,
            second_frag_next_override: self.ipv6_frag_next_override,
        }
    }

    pub(crate) const fn fragment_storage_active(&self) -> bool {
        self.fragment_count != 0 || self.min_fragment_size != 0 || self.max_fragment_size != 0 || self.ip_frag_disorder
    }
}
