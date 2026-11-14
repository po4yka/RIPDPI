use crate::{ConfigError, MAX_QUIC_SNI_SPLIT_COUNT, UdpChainStep, UdpChainStepKind};

use super::CliState;

pub(super) fn push_step(state: &mut CliState, arg: &str) -> Result<(), ConfigError> {
    let group = state.group();
    if group.actions.udp_chain.iter().filter(|step| step.kind == UdpChainStepKind::QuicSniSplit).count()
        >= MAX_QUIC_SNI_SPLIT_COUNT as usize
    {
        return Err(ConfigError::invalid(arg, Some(format!("at most {MAX_QUIC_SNI_SPLIT_COUNT} per group"))));
    }
    group.actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::QuicSniSplit, 1));
    Ok(())
}
