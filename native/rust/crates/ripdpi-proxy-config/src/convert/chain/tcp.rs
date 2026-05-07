mod activation;
mod builder;
mod defaults;
mod fake;
mod flags;
mod hostfake;
mod ipv6;
mod kind;
mod offsets;
mod seq_overlap;
mod tlsrandrec;

use ripdpi_config::{TcpChainStep, TcpChainStepKind};

use crate::types::{ProxyConfigError, ProxyUiTcpChainStep};

use builder::ParsedTcpChainStepFields;

pub use kind::parse_tcp_chain_step_kind;

pub(crate) fn synthesize_tlsrec_prelude_for_bare_hostfake(chain: &mut Vec<TcpChainStep>) {
    defaults::synthesize_tlsrec_prelude_for_bare_hostfake(chain);
}

pub(crate) fn parse_proxy_tcp_chain(
    steps: &[ProxyUiTcpChainStep],
    field_name: &str,
) -> Result<Vec<TcpChainStep>, ProxyConfigError> {
    let mut parsed = Vec::with_capacity(steps.len());

    for step in steps {
        parsed.push(parse_proxy_tcp_chain_step(step, field_name)?);
    }

    Ok(parsed)
}

fn parse_proxy_tcp_chain_step(step: &ProxyUiTcpChainStep, field_name: &str) -> Result<TcpChainStep, ProxyConfigError> {
    let kind = parse_tcp_chain_step_kind(&step.kind)?;
    let offset = offsets::parse_step_offset(kind, step, field_name)?;
    let midhost_offset = offsets::parse_midhost_offset(kind, step, field_name)?;
    let fake_host_template = hostfake::parse_fake_host_template(step, field_name)?;
    let fake_order = fake::parse_fake_order(&step.fake_order)?;
    let fake_seq_mode = fake::parse_fake_seq_mode(&step.fake_seq_mode)?;
    let tcp_flags = flags::parse_tcp_flags(kind, step, field_name)?;
    let seq_overlap = seq_overlap::parse_seq_overlap_fields(kind, step, field_name)?;
    let tlsrandrec = tlsrandrec::parse_tlsrandrec_fragment_fields(kind, step, field_name)?;
    let activation_filter = activation::parse_tcp_activation_filter(step, field_name)?;
    let ipv6_ext = ipv6::parse_tcp_ipv6_extension_profile(step)?;

    ParsedTcpChainStepFields {
        kind,
        offset,
        activation_filter,
        midhost_offset,
        fake_host_template,
        fake_order,
        fake_seq_mode,
        tcp_flags,
        seq_overlap,
        tlsrandrec,
        inter_segment_delay_ms: step.inter_segment_delay_ms,
        ipv6_ext,
        random_fake_host: step.random_fake_host,
    }
    .into_step()
}

pub(crate) fn ensure_hostfake_allows_offset(
    kind: TcpChainStepKind,
    is_adaptive: bool,
    field_name: &str,
) -> Result<(), ProxyConfigError> {
    if kind == TcpChainStepKind::HostFake && is_adaptive {
        return Err(ProxyConfigError::InvalidConfig(format!(
            "Adaptive markers are not supported for {field_name} kind=hostfake"
        )));
    }
    Ok(())
}
