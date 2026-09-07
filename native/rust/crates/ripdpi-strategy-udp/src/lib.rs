//! UDP packet-level desync strategies.

#![forbid(unsafe_code)]

use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, L7Protocol, RuntimeCapability, StrategyContext,
    StrategyDescriptor, StrategyError, StrategyStepDescriptor, StrategyStepFactory, StrategyStepParams,
    StrategyStepRegistration, StrategyVerdict,
};

const IPV4_HEADER_MIN_LEN: usize = 20;
const IPV6_HEADER_LEN: usize = 40;
const UDP_HEADER_LEN: usize = 8;
const UDP_NEXT_HEADER: u8 = 17;

/// UDP length falsification strategy.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UdpLenStrategy {
    delta: i16,
}

impl UdpLenStrategy {
    /// Creates a strategy that adds `delta` to the UDP length field.
    pub const fn new(delta: i16) -> Self {
        Self { delta }
    }
}

impl Default for UdpLenStrategy {
    fn default() -> Self {
        Self { delta: 4 }
    }
}

impl DesyncStrategy for UdpLenStrategy {
    fn id(&self) -> &str {
        "udplen"
    }

    fn matches(&self, ctx: &StrategyContext<'_>) -> bool {
        let udp_ports_present = ctx.dissect.src_port != 0 || ctx.dissect.dst_port != 0;
        udp_ports_present && matches!(ctx.dissect.proto, L7Protocol::Quic(_) | L7Protocol::Unknown)
    }

    fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        if !ctx.caps.has(RuntimeCapability::VpnMode) && tier_rank(ctx.caps.tier) < tier_rank(CapabilityTier::Tier3) {
            return Ok(());
        }
        if let Some(packet) = apply_udplen(ctx.payload, self.delta) {
            plan.actions.push(DesyncAction::RawSend(packet));
            plan.verdict = StrategyVerdict::Apply;
        }
        Ok(())
    }

    fn describe(&self) -> StrategyDescriptor {
        StrategyDescriptor {
            id: "udplen".to_owned(),
            label: "UDP length falsification".to_owned(),
            supported_protocols: vec![L7Protocol::Quic(Default::default()), L7Protocol::Unknown],
            required_tier: CapabilityTier::Tier3,
            required_capabilities: vec![RuntimeCapability::VpnMode],
        }
    }
}

/// Applies UDP length falsification to a raw IPv4 or IPv6 packet.
pub fn apply_udplen(packet: &[u8], delta: i16) -> Option<Vec<u8>> {
    let version = packet.first()? >> 4;
    match version {
        4 => apply_udplen_ipv4(packet, delta),
        6 => apply_udplen_ipv6(packet, delta),
        _ => None,
    }
}

/// Calculates the checksum that should be stored in an IPv6 UDP packet.
pub fn ipv6_udp_checksum(packet: &[u8]) -> Option<u16> {
    if packet.len() < IPV6_HEADER_LEN + UDP_HEADER_LEN || packet[0] >> 4 != 6 || packet[6] != UDP_NEXT_HEADER {
        return None;
    }
    let udp_offset = IPV6_HEADER_LEN;
    let udp_len = u16::from_be_bytes([packet[udp_offset + 4], packet[udp_offset + 5]]) as u32;
    let mut udp_segment = packet[udp_offset..].to_vec();
    udp_segment[6] = 0;
    udp_segment[7] = 0;

    let mut sum = checksum_sum(&packet[8..24]);
    sum += checksum_sum(&packet[24..40]);
    sum += checksum_sum(&udp_len.to_be_bytes());
    sum += u32::from(UDP_NEXT_HEADER);
    sum += checksum_sum(&udp_segment);
    let checksum = finalize_checksum(sum);
    Some(if checksum == 0 { 0xffff } else { checksum })
}

/// Returns a boxed built-in UDP strategy for a stable ID.
pub fn strategy_by_id(id: &str) -> Option<Box<dyn DesyncStrategy>> {
    match id {
        "udplen" => Some(Box::new(UdpLenStrategy::default())),
        _ => None,
    }
}

/// Builds a [`UdpLenStrategy`] from the parsed config step's parameters.
///
/// Called with [`StrategyStepParams::default`] when `udplen` is registered as a
/// bare built-in technique — `delta` then defaults to 4, matching the historic
/// `UdpLenStrategy::default()`.
fn build_udplen(params: &StrategyStepParams) -> Box<dyn DesyncStrategy> {
    let delta = params.delta.unwrap_or(4).clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16;
    Box::new(UdpLenStrategy::new(delta))
}

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static UDPLEN_REGISTRATION: StrategyStepRegistration = StrategyStepRegistration {
    descriptor: StrategyStepDescriptor {
        id: "udplen",
        label: "UDP length falsification",
        aliases: &[],
        required_tier: CapabilityTier::Tier3,
        required_capabilities: &[RuntimeCapability::VpnMode],
        needs_parameters: true,
        parameter_schema: "delta: signed UDP length-field delta (default 4)",
    },
    factory: StrategyStepFactory::Configured(build_udplen),
};

fn apply_udplen_ipv4(packet: &[u8], delta: i16) -> Option<Vec<u8>> {
    if packet.len() < IPV4_HEADER_MIN_LEN || packet[9] != UDP_NEXT_HEADER {
        return None;
    }
    let ihl = usize::from(packet[0] & 0x0f) * 4;
    if ihl < IPV4_HEADER_MIN_LEN || packet.len() < ihl + UDP_HEADER_LEN {
        return None;
    }
    let udp_length_offset = ihl + 4;
    let next_length = adjusted_udp_len(packet, udp_length_offset, delta)?;
    let mut output = packet.to_vec();
    output[udp_length_offset..udp_length_offset + 2].copy_from_slice(&next_length.to_be_bytes());
    output[ihl + 6..ihl + 8].copy_from_slice(&[0, 0]);
    Some(output)
}

fn apply_udplen_ipv6(packet: &[u8], delta: i16) -> Option<Vec<u8>> {
    if packet.len() < IPV6_HEADER_LEN + UDP_HEADER_LEN || packet[6] != UDP_NEXT_HEADER {
        return None;
    }
    let udp_length_offset = IPV6_HEADER_LEN + 4;
    let next_length = adjusted_udp_len(packet, udp_length_offset, delta)?;
    let mut output = packet.to_vec();
    output[udp_length_offset..udp_length_offset + 2].copy_from_slice(&next_length.to_be_bytes());
    output[IPV6_HEADER_LEN + 6..IPV6_HEADER_LEN + 8].copy_from_slice(&[0, 0]);
    let checksum = ipv6_udp_checksum(&output)?;
    output[IPV6_HEADER_LEN + 6..IPV6_HEADER_LEN + 8].copy_from_slice(&checksum.to_be_bytes());
    Some(output)
}

fn adjusted_udp_len(packet: &[u8], offset: usize, delta: i16) -> Option<u16> {
    let current = u16::from_be_bytes([*packet.get(offset)?, *packet.get(offset + 1)?]);
    let next = i32::from(current) + i32::from(delta);
    (UDP_HEADER_LEN as i32..=i32::from(u16::MAX)).contains(&next).then_some(next as u16)
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
