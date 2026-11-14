use crate::{ConfigError, DesyncGroup, MAX_FAKE_BURST_COUNT, UdpChainStep, UdpChainStepKind};

pub(super) fn apply(group: &mut DesyncGroup, value: &str) -> Result<(), ConfigError> {
    let option = "--quic-fake-version";
    let version = u32::from_str_radix(value.trim_start_matches("0x").trim_start_matches("0X"), 16)
        .map_err(|_| ConfigError::invalid(option, Some(value)))?;
    if group.actions.udp_chain.iter().filter(|step| step.kind == UdpChainStepKind::QuicFakeVersion).count()
        >= MAX_FAKE_BURST_COUNT as usize
    {
        return Err(ConfigError::invalid(option, Some(value)));
    }
    group.actions.quic_fake_version = version;
    group.actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::QuicFakeVersion, 1));
    Ok(())
}
