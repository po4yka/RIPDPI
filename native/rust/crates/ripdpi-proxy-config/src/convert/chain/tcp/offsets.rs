use ripdpi_config::{OffsetExpr, TcpChainStepKind};

use crate::types::{ProxyConfigError, ProxyUiTcpChainStep};

use super::ensure_hostfake_allows_offset;
use crate::convert::legacy_payload_adapter::parse_offset_expr_field;

pub(crate) fn parse_step_offset(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<OffsetExpr, ProxyConfigError> {
    let offset = parse_offset_expr_field(Some(step.marker.as_str()), "0", field_name)?;
    ensure_hostfake_allows_offset(kind, offset.base.is_adaptive(), field_name)?;
    Ok(offset)
}

pub(crate) fn parse_midhost_offset(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<Option<OffsetExpr>, ProxyConfigError> {
    let midhost_offset = Some(str::trim(step.midhost_marker.as_str()))
        .filter(|value| !value.is_empty())
        .map(ripdpi_config::parse_offset_expr)
        .transpose()
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} midhostMarker")))?;

    if kind == TcpChainStepKind::HostFake && midhost_offset.is_some_and(|value| value.base.is_adaptive()) {
        return Err(ProxyConfigError::InvalidConfig(format!(
            "Adaptive markers are not supported for {field_name} midhostMarker"
        )));
    }

    Ok(midhost_offset)
}
