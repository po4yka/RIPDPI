use crate::types::DesyncError;
use ripdpi_config::{TcpChainStep, TcpChainStepKind};

pub(super) fn split_tcp_chain(chain: &[TcpChainStep]) -> Result<(Vec<TcpChainStep>, Vec<TcpChainStep>), DesyncError> {
    let mut prelude_steps = Vec::new();
    let mut send_steps = Vec::new();
    let mut saw_send_step = false;

    for (index, step) in chain.iter().enumerate() {
        if step.kind().is_tls_prelude() {
            if saw_send_step {
                return Err(DesyncError);
            }
            prelude_steps.push(step.clone());
        } else {
            saw_send_step = true;
            if matches!(step.kind(), TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
                && index + 1 != chain.len()
            {
                return Err(DesyncError);
            }
            send_steps.push(step.clone());
        }
    }

    Ok((prelude_steps, send_steps))
}
