use ripdpi_config::RuntimeConfig;
pub use ripdpi_proxy_runtime_desync_adapter::model::config::{
    first_response_bytes_limit, first_response_settings, first_response_timeout, first_response_timeout_count_limit,
    FirstResponseSettings,
};

use super::protect_path_owned;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResponseFailureEvidenceSettings {
    pub protect_path: Option<String>,
}

pub fn response_failure_evidence_settings(config: &RuntimeConfig) -> ResponseFailureEvidenceSettings {
    ResponseFailureEvidenceSettings { protect_path: protect_path_owned(config) }
}

#[cfg(test)]
mod tests {
    use ripdpi_config::RuntimeConfig;

    use super::*;

    #[test]
    fn response_failure_evidence_settings_project_protect_path() {
        let mut config = RuntimeConfig::default();
        config.process.protect_path = Some("/tmp/protect.sock".to_string());

        assert_eq!(
            response_failure_evidence_settings(&config),
            ResponseFailureEvidenceSettings { protect_path: Some("/tmp/protect.sock".to_string()) },
        );
    }
}
