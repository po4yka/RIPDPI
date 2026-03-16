use ciadpi_config::RuntimeConfig;
use ripdpi_proxy_config::{
    parse_proxy_config_json as shared_parse_proxy_config_json,
    runtime_config_envelope_from_payload as shared_runtime_config_envelope_from_payload,
    runtime_config_from_command_line as shared_runtime_config_from_command_line,
    runtime_config_from_payload as shared_runtime_config_from_payload,
    runtime_config_from_ui as shared_runtime_config_from_ui, ProxyConfigError, ProxyConfigPayload, ProxyUiConfig,
    RuntimeConfigEnvelope, FAKE_TLS_SNI_MODE_FIXED,
};

use crate::errors::JniProxyError;

pub(crate) const HOSTS_DISABLE: &str = "disable";
pub(crate) const HOSTS_BLACKLIST: &str = "blacklist";
pub(crate) const HOSTS_WHITELIST: &str = "whitelist";

pub(crate) fn default_fake_tls_sni_mode() -> String {
    FAKE_TLS_SNI_MODE_FIXED.to_string()
}

pub(crate) fn runtime_config_envelope_from_payload(
    payload: ProxyConfigPayload,
) -> Result<RuntimeConfigEnvelope, JniProxyError> {
    shared_runtime_config_envelope_from_payload(payload).map_err(proxy_config_error)
}

pub(crate) fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_payload(payload).map_err(proxy_config_error)
}

pub(crate) fn runtime_config_from_command_line(args: Vec<String>) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_command_line(args).map_err(proxy_config_error)
}

pub(crate) fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, JniProxyError> {
    shared_runtime_config_from_ui(payload).map_err(proxy_config_error)
}

pub(crate) fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, JniProxyError> {
    shared_parse_proxy_config_json(json).map_err(proxy_config_error)
}

fn proxy_config_error(err: ProxyConfigError) -> JniProxyError {
    JniProxyError::InvalidConfig(err.to_string())
}
