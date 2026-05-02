use ripdpi_config::{SeqOverlapFakeMode, TcpChainStepKind};

use crate::types::{
    ProxyConfigError, ProxyUiTcpChainStep, SEQOVL_DEFAULT_OVERLAP_SIZE, SEQOVL_FAKE_MODE_PROFILE, SEQOVL_FAKE_MODE_RAND,
};

#[derive(Clone, Copy)]
pub(crate) struct ParsedSeqOverlap {
    pub(crate) overlap_size: i32,
    pub(crate) fake_mode: SeqOverlapFakeMode,
}

pub(crate) fn parse_seq_overlap_fields(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<ParsedSeqOverlap, ProxyConfigError> {
    match kind {
        TcpChainStepKind::SeqOverlap => {
            let overlap_size = normalize_seqovl_overlap_size(step.overlap_size);
            if !(1..=32).contains(&overlap_size) {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{field_name} kind=seqovl overlapSize must be in 1..=32"
                )));
            }
            Ok(ParsedSeqOverlap { overlap_size, fake_mode: parse_seqovl_fake_mode(&step.fake_mode)? })
        }
        _ => {
            if step.overlap_size != 0 {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{field_name} kind={} must not declare overlapSize",
                    step.kind
                )));
            }
            if !step.fake_mode.trim().is_empty() && !step.fake_mode.eq_ignore_ascii_case(SEQOVL_FAKE_MODE_PROFILE) {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{field_name} kind={} must not declare fakeMode",
                    step.kind
                )));
            }
            Ok(ParsedSeqOverlap { overlap_size: 0, fake_mode: SeqOverlapFakeMode::Profile })
        }
    }
}

fn normalize_seqovl_overlap_size(value: i32) -> i32 {
    if value > 0 {
        value
    } else {
        SEQOVL_DEFAULT_OVERLAP_SIZE
    }
}

fn parse_seqovl_fake_mode(value: &str) -> Result<SeqOverlapFakeMode, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        SEQOVL_FAKE_MODE_PROFILE | "" => Ok(SeqOverlapFakeMode::Profile),
        SEQOVL_FAKE_MODE_RAND => Ok(SeqOverlapFakeMode::Rand),
        _ => Err(ProxyConfigError::InvalidConfig(
            "tcpChainSteps kind=seqovl fakeMode must be profile or rand".to_string(),
        )),
    }
}
