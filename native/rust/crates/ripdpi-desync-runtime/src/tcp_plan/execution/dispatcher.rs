use ripdpi_config::TcpChainStepKind;

use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use super::{basic_stream, fake_family, fake_rst, hostfake, invalid, ip_fragmentation, ttl_sensitive};
use crate::tcp_fake_family::TcpStepControl;
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    match input.kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData | TcpChainStepKind::SeqOverlap | TcpChainStepKind::Oob => {
            basic_stream::execute(ctx, input)
        }
        TcpChainStepKind::Disorder | TcpChainStepKind::Disoob => ttl_sensitive::execute(ctx, input),
        TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder => {
            fake_family::execute(ctx, input)
        }
        TcpChainStepKind::IpFrag2 => ip_fragmentation::execute(ctx, input),
        TcpChainStepKind::HostFake => hostfake::execute(ctx, input),
        TcpChainStepKind::FakeRst => fake_rst::execute(ctx, input),
        TcpChainStepKind::MultiDisorder => invalid::execute_multi_disorder_step(),
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => invalid::execute_tls_prelude_step(),
        _ => invalid::execute_unknown_step(),
    }
}
