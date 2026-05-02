use ripdpi_config::{parse_tcp_flag_mask, validate_tcp_flag_masks, TcpChainStepKind};

use crate::types::{ProxyConfigError, ProxyUiTcpChainStep};

#[derive(Clone, Copy)]
pub(crate) struct ParsedTcpFlags {
    pub(crate) set: Option<u16>,
    pub(crate) unset: Option<u16>,
    pub(crate) orig_set: Option<u16>,
    pub(crate) orig_unset: Option<u16>,
}

pub(crate) fn parse_tcp_flags(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<ParsedTcpFlags, ProxyConfigError> {
    let tcp_flags_set = parse_tcp_flag_field(&step.tcp_flags_set, field_name, "tcpFlags")?;
    let tcp_flags_unset = parse_tcp_flag_field(&step.tcp_flags_unset, field_name, "tcpFlagsUnset")?;
    let tcp_flags_orig_set = parse_tcp_flag_field(&step.tcp_flags_orig_set, field_name, "tcpFlagsOrig")?;
    let tcp_flags_orig_unset = parse_tcp_flag_field(&step.tcp_flags_orig_unset, field_name, "tcpFlagsOrigUnset")?;

    validate_tcp_flag_masks(kind, tcp_flags_set, tcp_flags_unset, tcp_flags_orig_set, tcp_flags_orig_unset, field_name)
        .map_err(|err| ProxyConfigError::InvalidConfig(err.to_string()))?;

    Ok(ParsedTcpFlags {
        set: tcp_flags_set,
        unset: tcp_flags_unset,
        orig_set: tcp_flags_orig_set,
        orig_unset: tcp_flags_orig_unset,
    })
}

fn parse_tcp_flag_field(value: &str, field_name: &str, label: &str) -> Result<Option<u16>, ProxyConfigError> {
    Some(str::trim(value))
        .filter(|value| !value.is_empty())
        .map(parse_tcp_flag_mask)
        .transpose()
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} {label}: {err}")))
}
