use crate::types::{ProxyConfigError, ProxyUiTcpChainStep};

pub(crate) fn parse_fake_host_template(
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<Option<String>, ProxyConfigError> {
    Some(str::trim(step.fake_host_template.as_str()))
        .filter(|value| !value.is_empty())
        .map(ripdpi_config::normalize_fake_host_template)
        .transpose()
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} fakeHostTemplate")))
}
