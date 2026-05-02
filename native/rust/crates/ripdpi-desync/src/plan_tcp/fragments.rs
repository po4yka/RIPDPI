use crate::types::DesyncAction;
use ripdpi_config::TcpChainStep;
use ripdpi_ipfrag::Ipv6ExtHeaders;

fn ipv6_ext_from_tcp_step(step: &TcpChainStep) -> Ipv6ExtHeaders {
    Ipv6ExtHeaders {
        hop_by_hop: step.ipv6_hop_by_hop,
        dest_opt: step.ipv6_dest_opt,
        dest_opt_fragmentable: step.ipv6_dest_opt2,
        routing: step.ipv6_routing,
        second_frag_next_override: step.ipv6_frag_next_override,
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
        actions.push(DesyncAction::WriteIpFragmentedTcp {
            bytes: tampered.to_vec(),
            split_offset: pos as usize,
            disorder: step.ip_frag_disorder,
            ipv6_ext: ipv6_ext_from_tcp_step(step),
        });
    } else {
        actions.push(DesyncAction::Write(tampered[lp as usize..].to_vec()));
    }
}
