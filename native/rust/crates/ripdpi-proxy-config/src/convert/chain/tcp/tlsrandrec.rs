use ripdpi_config::TcpChainStepKind;

use crate::types::{
    ProxyConfigError, ProxyUiTcpChainStep, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE,
    TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE,
};

#[derive(Clone, Copy)]
pub(crate) struct ParsedTlsRandRec {
    pub(crate) fragment_count: i32,
    pub(crate) min_fragment_size: i32,
    pub(crate) max_fragment_size: i32,
}

pub(crate) fn parse_tlsrandrec_fragment_fields(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<ParsedTlsRandRec, ProxyConfigError> {
    match kind {
        TcpChainStepKind::TlsRandRec => Ok(ParsedTlsRandRec {
            fragment_count: normalize_tlsrandrec_step_field(step.fragment_count, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT),
            min_fragment_size: normalize_tlsrandrec_step_field(
                step.min_fragment_size,
                TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE,
            ),
            max_fragment_size: normalize_tlsrandrec_step_field(
                step.max_fragment_size,
                TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE,
            ),
        }),
        _ => {
            if step.fragment_count != 0 || step.min_fragment_size != 0 || step.max_fragment_size != 0 {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "tlsrandrec fragment fields are only supported for {field_name} kind=tlsrandrec"
                )));
            }
            Ok(ParsedTlsRandRec { fragment_count: 0, min_fragment_size: 0, max_fragment_size: 0 })
        }
    }
}

fn normalize_tlsrandrec_step_field(value: i32, default: i32) -> i32 {
    if value > 0 {
        value
    } else {
        default
    }
}
