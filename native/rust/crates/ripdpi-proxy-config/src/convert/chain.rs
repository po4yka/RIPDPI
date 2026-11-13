mod activation;
mod ipv6;
mod rotation;
mod tcp;
mod udp;
mod validation;

use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

use crate::types::{ProxyConfigError, ProxyUiChainConfig};

pub use tcp::parse_tcp_chain_step_kind;
pub use udp::parse_udp_chain_step_kind;

pub(crate) fn apply_chain_section(
    group: &mut DesyncGroup,
    chains: &ProxyUiChainConfig,
    root_mode: bool,
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
    if !root_mode {
        // Like md5sig: without root mode a fake RST cannot be sent, so the step is dropped
        // and a saved config still starts. Kotlin validation rejects new fakerst edits.
        group.actions.tcp_chain.retain(|step| !is_fake_rst(step));
        for candidate in group.actions.rotation_policy.iter_mut().flat_map(|policy| &mut policy.candidates) {
            candidate.tcp_chain.retain(|step| !is_fake_rst(step));
        }
    }

    group.actions.udp_chain = udp::parse_proxy_udp_chain(&chains.udp_steps)?;
    validation::validate_udp_chain(&group.actions.udp_chain)?;
    Ok(())
}

fn is_fake_rst(step: &TcpChainStep) -> bool {
    step.kind() == TcpChainStepKind::FakeRst
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
