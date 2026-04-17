use crate::types::ProxyConfigError;

pub(crate) fn trim_non_empty(opt: Option<String>) -> Option<String> {
    opt.map(|value| value.trim().to_string()).filter(|value| !value.is_empty())
}

pub(crate) fn parse_hosts(hosts: Option<&str>) -> Result<Vec<String>, ProxyConfigError> {
    let hosts = hosts.unwrap_or_default();
    ripdpi_config::parse_hosts_spec(hosts)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid hosts list".to_string()))
}
