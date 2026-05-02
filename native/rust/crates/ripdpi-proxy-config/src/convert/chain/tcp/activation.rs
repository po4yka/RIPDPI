use ripdpi_config::ActivationFilter;

use crate::types::{ProxyConfigError, ProxyUiTcpChainStep};

use crate::convert::chain::activation::parse_proxy_activation_filter;

pub(crate) fn parse_tcp_activation_filter(
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<Option<ActivationFilter>, ProxyConfigError> {
    parse_proxy_activation_filter(step.activation_filter.as_ref(), &format!("{field_name}.activationFilter"), true)
}
