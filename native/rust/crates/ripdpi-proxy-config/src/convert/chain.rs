mod activation;
mod ipv6;
mod rotation;
mod tcp;
mod udp;
mod validation;

use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};
#[cfg(not(feature = "privileged-packets"))]
use ripdpi_config::{DesyncGroupActionSettings, TcpFakeOrdering, TcpFakePayload, TcpFlagOverrides, UdpChainStepKind};
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

use crate::types::{ProxyConfigError, ProxyUiChainConfig};

pub use tcp::parse_tcp_chain_step_kind;
pub use udp::parse_udp_chain_step_kind;

pub(crate) fn apply_chain_section(
    group: &mut DesyncGroup,
    chains: &ProxyUiChainConfig,
    #[cfg(feature = "privileged-packets")] root_mode: bool,
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
    #[cfg(feature = "privileged-packets")]
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

#[cfg(feature = "privileged-packets")]
fn is_fake_rst(step: &TcpChainStep) -> bool {
    step.kind() == TcpChainStepKind::FakeRst
}

#[cfg(not(feature = "privileged-packets"))]
fn keep_tcp_step(kind: TcpChainStepKind) -> bool {
    !matches!(kind, TcpChainStepKind::FakeRst | TcpChainStepKind::IpFrag2 | TcpChainStepKind::MultiDisorder)
}

#[cfg(not(feature = "privileged-packets"))]
fn with_socket_step_options(step: TcpChainStep) -> TcpChainStep {
    step.with_fake_payload(TcpFakePayload {
        ordering: TcpFakeOrdering::before_each_duplicate(),
        fake_flags: TcpFlagOverrides::disabled(),
        original_flags: TcpFlagOverrides::disabled(),
    })
}

#[cfg(not(feature = "privileged-packets"))]
fn keep_udp_step(kind: UdpChainStepKind) -> bool {
    kind != UdpChainStepKind::IpFrag2Udp
}

#[cfg(not(feature = "privileged-packets"))]
fn clear_orphaned_preludes(steps: &mut Vec<TcpChainStep>, original_len: usize) {
    if steps.len() < original_len && steps.iter().all(|step| step.kind().is_tls_prelude()) {
        steps.clear();
    }
}

#[cfg(not(feature = "privileged-packets"))]
pub(super) fn normalize_command_line_group(group: &mut DesyncGroup) {
    fn normalize_tcp(steps: &mut Vec<TcpChainStep>) {
        let original_len = steps.len();
        steps.retain(|step| keep_tcp_step(step.kind()));
        clear_orphaned_preludes(steps, original_len);
    }

    normalize_tcp(&mut group.actions.tcp_chain);
    for candidate in group.actions.rotation_policy.iter_mut().flat_map(|policy| &mut policy.candidates) {
        normalize_tcp(&mut candidate.tcp_chain);
    }
    group.actions.udp_chain.retain(|step| keep_udp_step(step.kind));
    normalize_packet_options(&mut group.actions);
}

#[cfg(not(feature = "privileged-packets"))]
pub(super) fn normalize_packet_options(actions: &mut DesyncGroupActionSettings) {
    actions.md5sig = false;
    actions.ip_id_mode = None;
    actions.fake_tls_secondary_profile = None;
    actions.fake_tcp_timestamp_enabled = false;
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
