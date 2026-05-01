use ripdpi_config::{RotationCandidate, RotationPolicy};

use crate::types::{ProxyConfigError, ProxyUiTcpRotationConfig};

use super::tcp::{parse_proxy_tcp_chain, synthesize_tlsrec_prelude_for_bare_hostfake};
use super::validation::validate_tcp_chain;

pub(crate) fn parse_tcp_rotation(
    rotation: Option<&ProxyUiTcpRotationConfig>,
) -> Result<Option<RotationPolicy>, ProxyConfigError> {
    let Some(rotation) = rotation else {
        return Ok(None);
    };
    if rotation.candidates.is_empty() {
        return Err(ProxyConfigError::InvalidConfig(
            "chains.tcpRotation must declare at least one candidate".to_string(),
        ));
    }
    if rotation.fails == 0 {
        return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation fails must be positive".to_string()));
    }
    if rotation.retrans == 0 {
        return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation retrans must be positive".to_string()));
    }
    if rotation.time_secs == 0 {
        return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation timeSecs must be positive".to_string()));
    }

    let candidates = rotation
        .candidates
        .iter()
        .enumerate()
        .map(|(index, candidate)| {
            let field_name = format!("chains.tcpRotation.candidates[{index}].tcpSteps");
            let mut tcp_chain = parse_proxy_tcp_chain(&candidate.tcp_steps, &field_name)?;
            synthesize_tlsrec_prelude_for_bare_hostfake(&mut tcp_chain);
            validate_tcp_chain(&tcp_chain)?;
            Ok(RotationCandidate { tcp_chain })
        })
        .collect::<Result<Vec<_>, ProxyConfigError>>()?;
    Ok(Some(RotationPolicy {
        fails: rotation.fails,
        retrans: rotation.retrans,
        seq: rotation.seq,
        rst: rotation.rst,
        time_secs: rotation.time_secs,
        cancel_on_failure: rotation.cancel_on_failure.unwrap_or(true),
        candidates,
    }))
}
