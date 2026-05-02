use ripdpi_config::TcpChainStepKind;

use crate::types::ProxyConfigError;

pub fn parse_tcp_chain_step_kind(value: &str) -> Result<TcpChainStepKind, ProxyConfigError> {
    match value {
        "split" => Ok(TcpChainStepKind::Split),
        "syndata" => Ok(TcpChainStepKind::SynData),
        "seqovl" => Ok(TcpChainStepKind::SeqOverlap),
        "disorder" => Ok(TcpChainStepKind::Disorder),
        "multidisorder" => Ok(TcpChainStepKind::MultiDisorder),
        "fake" => Ok(TcpChainStepKind::Fake),
        "fakedsplit" => Ok(TcpChainStepKind::FakeSplit),
        "fakeddisorder" => Ok(TcpChainStepKind::FakeDisorder),
        "hostfake" => Ok(TcpChainStepKind::HostFake),
        "oob" => Ok(TcpChainStepKind::Oob),
        "disoob" => Ok(TcpChainStepKind::Disoob),
        "tlsrec" => Ok(TcpChainStepKind::TlsRec),
        "tlsrandrec" => Ok(TcpChainStepKind::TlsRandRec),
        "ipfrag2" => Ok(TcpChainStepKind::IpFrag2),
        "fakerst" => Ok(TcpChainStepKind::FakeRst),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown tcpChainSteps kind: {value}"))),
    }
}
