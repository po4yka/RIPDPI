use ripdpi_config::{ActivationFilter, NumericRange};

use crate::types::{ProxyConfigError, ProxyUiActivationFilter, ProxyUiNumericRange};

pub(crate) fn parse_proxy_activation_filter(
    filter: Option<&ProxyUiActivationFilter>,
    field_name: &str,
    allow_tcp_state_predicates: bool,
) -> Result<Option<ActivationFilter>, ProxyConfigError> {
    let Some(filter) = filter else {
        return Ok(None);
    };

    let round = filter
        .round
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.round"), 1))
        .transpose()?
        .flatten();
    let payload_size = filter
        .payload_size
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.payloadSize"), 0))
        .transpose()?
        .flatten();
    let stream_bytes = filter
        .stream_bytes
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.streamBytes"), 0))
        .transpose()?
        .flatten();

    if !allow_tcp_state_predicates
        && (filter.tcp_has_timestamp.is_some()
            || filter.tcp_has_ech.is_some()
            || filter.tcp_window_below.is_some()
            || filter.tcp_mss_below.is_some())
    {
        return Err(ProxyConfigError::InvalidConfig(format!("{field_name} must not declare TCP-state predicates")));
    }

    let filter = ActivationFilter {
        round,
        payload_size,
        stream_bytes,
        tcp_has_timestamp: filter.tcp_has_timestamp,
        tcp_has_ech: filter.tcp_has_ech,
        tcp_window_below: filter.tcp_window_below,
        tcp_mss_below: filter.tcp_mss_below,
    };
    Ok((!filter.is_unbounded()).then_some(filter))
}

fn parse_proxy_numeric_range(
    range: &ProxyUiNumericRange,
    field_name: &str,
    minimum: i64,
) -> Result<Option<NumericRange<i64>>, ProxyConfigError> {
    let start = range.start;
    let end = range.end;
    if start.is_none() && end.is_none() {
        return Ok(None);
    }

    let start = start.or(end).unwrap_or(minimum);
    let end = end.or(Some(start)).unwrap_or(start);
    if start < minimum || end < minimum || start > end {
        return Err(ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")));
    }

    Ok(Some(NumericRange::new(start, end)))
}
