mod activation;
mod ipv6;
mod rotation;
mod tcp;
mod udp;
mod validation;

use ripdpi_config::DesyncGroup;
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

use crate::types::{ProxyConfigError, ProxyUiChainConfig};

pub use tcp::parse_tcp_chain_step_kind;
pub use udp::parse_udp_chain_step_kind;

pub(crate) fn apply_chain_section(
    group: &mut DesyncGroup,
    chains: &ProxyUiChainConfig,
) -> Result<(), ProxyConfigError> {
    group.matches.any_protocol = chains.any_protocol;
    group.matches.payload_disable = parse_payload_disable(&chains.payload_disable);
    if let Some(filter) = activation::parse_proxy_activation_filter(
        chains.group_activation_filter.as_ref(),
        "chains.groupActivationFilter",
        false,
    )? {
        group.set_activation_filter(filter);
    }

    group.actions.tcp_chain = tcp::parse_proxy_tcp_chain(&chains.tcp_steps, "chains.tcpSteps")?;
    tcp::synthesize_tlsrec_prelude_for_bare_hostfake(&mut group.actions.tcp_chain);
    validation::validate_tcp_chain(&group.actions.tcp_chain)?;
    group.actions.rotation_policy = rotation::parse_tcp_rotation(chains.tcp_rotation.as_ref())?;

    group.actions.udp_chain = udp::parse_proxy_udp_chain(&chains.udp_steps)?;
    validation::validate_udp_chain(&group.actions.udp_chain)?;
    Ok(())
}

fn parse_payload_disable(names: &[String]) -> u32 {
    let mut mask = 0u32;
    for name in names {
        match name.as_str() {
            "http" => mask |= IS_HTTP,
            "tls" | "https" => mask |= IS_HTTPS,
            _ => {}
        }
    }
    mask
}
