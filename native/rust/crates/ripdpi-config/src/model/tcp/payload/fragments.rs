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
