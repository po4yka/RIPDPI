use ripdpi_config::RuntimeConfig;
use serde::{Deserialize, Serialize};

use super::runtime_context::{ProxyLogContext, ProxyRuntimeContext};
use super::ui::ProxyUiConfig;

#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum ProxyConfigError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "snake_case", rename_all_fields = "camelCase")]
#[allow(clippy::large_enum_variant)]
pub enum ProxyConfigPayload {
    CommandLine {
        args: Vec<String>,
        #[serde(default)]
        host_autolearn_store_path: Option<String>,
        #[serde(default)]
        runtime_context: Option<ProxyRuntimeContext>,
        #[serde(default)]
        log_context: Option<ProxyLogContext>,
        #[serde(default)]
        session_overrides: Option<ProxySessionOverrides>,
    },
    Ui {
        #[serde(default)]
        strategy_preset: Option<String>,
        #[serde(flatten)]
        config: ProxyUiConfig,
        #[serde(default)]
        runtime_context: Option<ProxyRuntimeContext>,
        #[serde(default)]
        log_context: Option<ProxyLogContext>,
        #[serde(default)]
        session_overrides: Option<ProxySessionOverrides>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxySessionOverrides {
    #[serde(default)]
    pub listen_port_override: Option<i32>,
    #[serde(default)]
    pub auth_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeConfigEnvelope {
    pub config: RuntimeConfig,
    pub runtime_context: Option<ProxyRuntimeContext>,
    pub log_context: Option<ProxyLogContext>,
    pub native_log_level: Option<String>,
}
