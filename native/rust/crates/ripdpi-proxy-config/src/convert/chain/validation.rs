use ripdpi_config::{FakeOrder, FakeSeqMode, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind};

use crate::types::ProxyConfigError;

pub(crate) fn validate_tcp_chain(steps: &[TcpChainStep]) -> Result<(), ProxyConfigError> {
    let mut saw_send_step = false;
    let mut saw_ipfrag2 = false;
    let mut saw_seqovl = false;
    let mut send_step_count = 0usize;
    let mut multidisorder_count = 0usize;

    for (index, step) in steps.iter().enumerate() {
        if step.kind.is_tls_prelude() {
            if saw_send_step {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{} must be declared before tcp send steps",
                    tcp_chain_step_kind_label(step.kind)
                )));
            }
        } else {
            saw_send_step = true;
            if step.kind == TcpChainStepKind::SeqOverlap {
                if saw_seqovl {
                    return Err(ProxyConfigError::InvalidConfig(
                        "seqovl must appear at most once per tcp chain".to_string(),
                    ));
                }
                if send_step_count != 0 {
                    return Err(ProxyConfigError::InvalidConfig("seqovl must be the first tcp send step".to_string()));
                }
                if !(1..=32).contains(&step.overlap_size) {
                    return Err(ProxyConfigError::InvalidConfig("seqovl overlapSize must be in 1..=32".to_string()));
                }
                saw_seqovl = true;
            }
            if step.kind == TcpChainStepKind::MultiDisorder {
                multidisorder_count += 1;
            } else if multidisorder_count != 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "multidisorder must be the only tcp send step family".to_string(),
                ));
            }
            if step.kind == TcpChainStepKind::IpFrag2 {
                saw_ipfrag2 = true;
                if index + 1 != steps.len() {
                    return Err(ProxyConfigError::InvalidConfig("ipfrag2 must be the only tcp send step".to_string()));
                }
            } else if saw_ipfrag2 {
                return Err(ProxyConfigError::InvalidConfig("ipfrag2 must be the only tcp send step".to_string()));
            }
            send_step_count += 1;
        }

        if matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder) && index + 1 != steps.len()
        {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "{} must be the last tcp send step",
                tcp_chain_step_kind_label(step.kind)
            )));
        }
        if step.kind.supports_fake_ordering() {
            if step.kind == TcpChainStepKind::HostFake
                && step.fake_order != FakeOrder::BeforeEach
                && step.midhost_offset.is_none()
            {
                return Err(ProxyConfigError::InvalidConfig("hostfake fakeOrder requires midhostMarker".to_string()));
            }
        } else if step.fake_order != FakeOrder::BeforeEach || step.fake_seq_mode != FakeSeqMode::Duplicate {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "{} must not declare fake ordering fields",
                tcp_chain_step_kind_label(step.kind)
            )));
        }
    }

    if multidisorder_count > 0 {
        if send_step_count != multidisorder_count {
            return Err(ProxyConfigError::InvalidConfig(
                "multidisorder must be the only tcp send step family".to_string(),
            ));
        }
        if multidisorder_count < 2 {
            return Err(ProxyConfigError::InvalidConfig("multidisorder must declare at least two markers".to_string()));
        }
    }

    Ok(())
}

pub(crate) fn validate_udp_chain(steps: &[UdpChainStep]) -> Result<(), ProxyConfigError> {
    if steps.iter().any(|step| step.kind == UdpChainStepKind::IpFrag2Udp) && steps.len() != 1 {
        return Err(ProxyConfigError::InvalidConfig("ipfrag2_udp must be the only udp chain step".to_string()));
    }
    Ok(())
}

fn tcp_chain_step_kind_label(kind: TcpChainStepKind) -> &'static str {
    match kind {
        TcpChainStepKind::Split => "split",
        TcpChainStepKind::SynData => "syndata",
        TcpChainStepKind::SeqOverlap => "seqovl",
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::MultiDisorder => "multidisorder",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::TlsRec => "tlsrec",
        TcpChainStepKind::TlsRandRec => "tlsrandrec",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        _ => "unknown",
    }
}
