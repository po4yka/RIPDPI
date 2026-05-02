use crate::types::DesyncAction;
use ripdpi_config::TcpChainStep;
use ripdpi_ipfrag::Ipv6ExtHeaders;

fn ipv6_ext_from_tcp_step(step: &TcpChainStep) -> Ipv6ExtHeaders {
    let extensions = step.ipv6_extension_payload();
    Ipv6ExtHeaders {
        hop_by_hop: extensions.hop_by_hop,
        dest_opt: extensions.dest_opt,
        dest_opt_fragmentable: extensions.dest_opt2,
        routing: extensions.routing,
        second_frag_next_override: extensions.second_frag_next_override,
    }
}

pub(super) fn push_ipfrag2_or_fallback(
    actions: &mut Vec<DesyncAction>,
    step: &TcpChainStep,
    tampered: &[u8],
    lp: i64,
    pos: i64,
    round: i64,
) {
    if round == 1 && pos > 0 && pos < tampered.len() as i64 {
        let payload = step.ip_frag_payload();
        actions.push(DesyncAction::WriteIpFragmentedTcp {
            bytes: tampered.to_vec(),
            split_offset: pos as usize,
            disorder: payload.is_some_and(|payload| payload.disorder),
            ipv6_ext: ipv6_ext_from_tcp_step(step),
        });
    } else {
        actions.push(DesyncAction::Write(tampered[lp as usize..].to_vec()));
    }
}
