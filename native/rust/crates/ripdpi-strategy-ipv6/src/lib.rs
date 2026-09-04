//! IPv6 extension-header desync strategies.

#![forbid(unsafe_code)]

use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, RuntimeCapability, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyStepDescriptor, StrategyStepFactory, StrategyStepParams, StrategyStepRegistration,
    StrategyVerdict,
};

const IPV6_HEADER_LEN: usize = 40;
const IPV6_PAYLOAD_LEN_OFFSET: usize = 4;
const IPV6_NEXT_HEADER_OFFSET: usize = 6;
const TCP_NEXT_HEADER: u8 = 6;
const HOP_BY_HOP_NEXT_HEADER: u8 = 0;
const ROUTING_NEXT_HEADER: u8 = 43;
const DEST_OPTS_NEXT_HEADER: u8 = 60;
const EXT_HEADER_LEN: usize = 8;
const TCP_CHECKSUM_OFFSET: usize = 16;

/// IPv6 extension header variant inserted before TCP.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Ipv6ExtType {
    /// Hop-by-hop options header.
    HopByHop,
    /// Destination options header.
    #[default]
    DestOpts,
    /// Routing header.
    Routing,
}

/// IPv6 extension header injection strategy.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Ipv6ExtHdrStrategy {
    ext_type: Ipv6ExtType,
}

impl Ipv6ExtHdrStrategy {
    /// Creates a strategy for the supplied extension header type.
    pub const fn new(ext_type: Ipv6ExtType) -> Self {
        Self { ext_type }
    }

    /// Returns the configured extension header type.
    pub const fn ext_type(self) -> Ipv6ExtType {
        self.ext_type
    }
}

impl DesyncStrategy for Ipv6ExtHdrStrategy {
    fn id(&self) -> &str {
        "ipv6_ext"
    }

    fn matches(&self, ctx: &StrategyContext<'_>) -> bool {
        ctx.dissect.is_ipv6
    }

    fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        if !self.matches(ctx) {
            return Ok(());
        }
        if !ctx.caps.has(RuntimeCapability::VpnMode) && tier_rank(ctx.caps.tier) < tier_rank(CapabilityTier::Tier3) {
            return Ok(());
        }
        if let Some(packet) = apply_ipv6_ext_header(ctx.payload, self.ext_type) {
            plan.actions.push(DesyncAction::RawSend(packet));
            plan.verdict = StrategyVerdict::Apply;
        }
        Ok(())
    }

    fn describe(&self) -> StrategyDescriptor {
        StrategyDescriptor {
            id: "ipv6_ext".to_owned(),
            label: "IPv6 extension header injection".to_owned(),
            required_tier: CapabilityTier::Tier3,
            required_capabilities: vec![RuntimeCapability::VpnMode],
            ..StrategyDescriptor::default()
        }
    }
}

/// Applies extension-header insertion to a raw packet.
pub fn apply_ipv6_ext_header(packet: &[u8], ext_type: Ipv6ExtType) -> Option<Vec<u8>> {
    if packet.len() < IPV6_HEADER_LEN || packet[0] >> 4 != 6 || packet[IPV6_NEXT_HEADER_OFFSET] != TCP_NEXT_HEADER {
        return None;
    }

    let payload_len =
        u16::from_be_bytes([*packet.get(IPV6_PAYLOAD_LEN_OFFSET)?, *packet.get(IPV6_PAYLOAD_LEN_OFFSET + 1)?]);
    let payload_len_usize = usize::from(payload_len);
    if packet.len() < IPV6_HEADER_LEN + payload_len_usize || payload_len_usize < min_tcp_header_len(packet) {
        return None;
    }
    let next_payload_len = payload_len.checked_add(EXT_HEADER_LEN as u16)?;

    let mut output = Vec::with_capacity(IPV6_HEADER_LEN + usize::from(next_payload_len));
    output.extend_from_slice(&packet[..IPV6_HEADER_LEN]);
    output[IPV6_PAYLOAD_LEN_OFFSET..IPV6_PAYLOAD_LEN_OFFSET + 2].copy_from_slice(&next_payload_len.to_be_bytes());
    output[IPV6_NEXT_HEADER_OFFSET] = ext_type.next_header();
    output.extend_from_slice(&extension_header(ext_type));
    output.extend_from_slice(&packet[IPV6_HEADER_LEN..IPV6_HEADER_LEN + payload_len_usize]);

    let tcp_offset = IPV6_HEADER_LEN + EXT_HEADER_LEN;
    output[tcp_offset + TCP_CHECKSUM_OFFSET..tcp_offset + TCP_CHECKSUM_OFFSET + 2].copy_from_slice(&[0, 0]);
    let checksum = ipv6_tcp_checksum(&output)?;
    output[tcp_offset + TCP_CHECKSUM_OFFSET..tcp_offset + TCP_CHECKSUM_OFFSET + 2]
        .copy_from_slice(&checksum.to_be_bytes());
    Some(output)
}

/// Calculates the TCP checksum expected for an IPv6 packet.
pub fn ipv6_tcp_checksum(packet: &[u8]) -> Option<u16> {
    if packet.len() < IPV6_HEADER_LEN || packet[0] >> 4 != 6 {
        return None;
    }
    let (tcp_offset, tcp_len) = tcp_segment_bounds(packet)?;
    let checksum_offset = tcp_offset + TCP_CHECKSUM_OFFSET;
    if packet.len() < checksum_offset + 2 || tcp_len < min_tcp_header_len_at(packet, tcp_offset)? {
        return None;
    }

    let mut tcp_segment = packet[tcp_offset..tcp_offset + tcp_len].to_vec();
    tcp_segment[TCP_CHECKSUM_OFFSET..TCP_CHECKSUM_OFFSET + 2].copy_from_slice(&[0, 0]);

    let mut sum = checksum_sum(&packet[8..24]);
    sum += checksum_sum(&packet[24..40]);
    sum += checksum_sum(&(tcp_len as u32).to_be_bytes());
    sum += u32::from(TCP_NEXT_HEADER);
    sum += checksum_sum(&tcp_segment);
    let checksum = finalize_checksum(sum);
    Some(if checksum == 0 { 0xffff } else { checksum })
}

/// Returns a boxed built-in IPv6 strategy for a stable ID.
pub fn strategy_by_id(id: &str) -> Option<Box<dyn DesyncStrategy>> {
    match id {
        "ipv6_ext" => Some(Box::new(Ipv6ExtHdrStrategy::default())),
        _ => None,
    }
}

/// Builds an [`Ipv6ExtHdrStrategy`] from the parsed config step's parameters.
///
/// Called with [`StrategyStepParams::default`] when `ipv6_ext` is registered as
/// a bare built-in technique — `ext_type` then defaults to
/// [`Ipv6ExtType::default`] (`DestOpts`), matching the historic
/// `Ipv6ExtHdrStrategy::default()`.
fn build_ipv6_ext(params: &StrategyStepParams) -> Box<dyn DesyncStrategy> {
    let ext_type = params.ext_type.as_deref().and_then(Ipv6ExtType::parse).unwrap_or_default();
    Box::new(Ipv6ExtHdrStrategy::new(ext_type))
}

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static IPV6_EXT_REGISTRATION: StrategyStepRegistration = StrategyStepRegistration {
    descriptor: StrategyStepDescriptor {
        id: "ipv6_ext",
        label: "IPv6 extension header injection",
        aliases: &["ipv6Ext"],
        required_tier: CapabilityTier::Tier3,
        required_capabilities: &[RuntimeCapability::VpnMode],
        needs_parameters: true,
        parameter_schema: "ext_type: hopbyhop|destopts|routing (default destopts)",
    },
    factory: StrategyStepFactory::Configured(build_ipv6_ext),
};

impl Ipv6ExtType {
    /// Parses the YAML `ext_type` value used by strategy packs.
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "hopbyhop" | "hop_by_hop" => Some(Self::HopByHop),
            "destopts" | "dest_opts" | "destination_options" => Some(Self::DestOpts),
            "routing" => Some(Self::Routing),
            _ => None,
        }
    }

    const fn next_header(self) -> u8 {
        match self {
            Self::HopByHop => HOP_BY_HOP_NEXT_HEADER,
            Self::DestOpts => DEST_OPTS_NEXT_HEADER,
            Self::Routing => ROUTING_NEXT_HEADER,
        }
    }
}

fn extension_header(ext_type: Ipv6ExtType) -> [u8; EXT_HEADER_LEN] {
    match ext_type {
        Ipv6ExtType::HopByHop | Ipv6ExtType::DestOpts => [TCP_NEXT_HEADER, 0, 1, 4, 0, 0, 0, 0],
        Ipv6ExtType::Routing => [TCP_NEXT_HEADER, 0, 4, 0, 0, 0, 0, 0],
    }
}

fn tcp_segment_bounds(packet: &[u8]) -> Option<(usize, usize)> {
    let payload_len = usize::from(u16::from_be_bytes([
        *packet.get(IPV6_PAYLOAD_LEN_OFFSET)?,
        *packet.get(IPV6_PAYLOAD_LEN_OFFSET + 1)?,
    ]));
    let total_len = IPV6_HEADER_LEN.checked_add(payload_len)?;
    if packet.len() < total_len {
        return None;
    }
    match packet[IPV6_NEXT_HEADER_OFFSET] {
        TCP_NEXT_HEADER => Some((IPV6_HEADER_LEN, payload_len)),
        HOP_BY_HOP_NEXT_HEADER | DEST_OPTS_NEXT_HEADER | ROUTING_NEXT_HEADER => {
            if payload_len < EXT_HEADER_LEN || packet.len() < IPV6_HEADER_LEN + EXT_HEADER_LEN {
                return None;
            }
            let ext_offset = IPV6_HEADER_LEN;
            let ext_len = (usize::from(packet[ext_offset + 1]) + 1) * 8;
            if ext_len > payload_len || packet.get(ext_offset).copied()? != TCP_NEXT_HEADER {
                return None;
            }
            Some((IPV6_HEADER_LEN + ext_len, payload_len - ext_len))
        }
        _ => None,
    }
}

fn min_tcp_header_len(packet: &[u8]) -> usize {
    min_tcp_header_len_at(packet, IPV6_HEADER_LEN).unwrap_or(usize::MAX)
}

fn min_tcp_header_len_at(packet: &[u8], tcp_offset: usize) -> Option<usize> {
    let data_offset = packet.get(tcp_offset + 12)? >> 4;
    let header_len = usize::from(data_offset) * 4;
    (header_len >= 20).then_some(header_len)
}

fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let (chunks, remainder) = bytes.as_chunks::<2>();
    for chunk in chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(&last) = remainder.first() {
        sum += u32::from(last) << 8;
    }
    sum
}

fn finalize_checksum(mut sum: u32) -> u16 {
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

fn tier_rank(tier: CapabilityTier) -> u8 {
    match tier {
        CapabilityTier::Tier0 => 0,
        CapabilityTier::Tier1 => 1,
        CapabilityTier::Tier2 => 2,
        CapabilityTier::Tier3 => 3,
    }
}
