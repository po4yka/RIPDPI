use ripdpi_config::RuntimeConfig;
use serde::{Deserialize, Serialize};

use super::runtime_context::{ProxyLogContext, ProxyRuntimeContext};
use super::ui::ProxyUiConfig;

#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum ProxyConfigError {
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
    #[error(
        "unsupported native config schemaVersion {found}; this build supports {SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION}"
    )]
    UnsupportedSchemaVersion { found: u32 },
}

/// The single native-config wire schema version this build understands.
///
/// Mirrors the Kotlin `NativeProxyConfigSchemaVersion` constant. A payload
/// carrying any other version is rejected by [`validate_schema_version`].
pub(crate) const SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION: u32 = 2;

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
        schema_version: u32,
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
        schema_version: u32,
    },
}

impl ProxyConfigPayload {
    /// The required `schemaVersion` envelope value carried by this payload.
    pub(crate) fn schema_version(&self) -> u32 {
        match self {
            ProxyConfigPayload::CommandLine { schema_version, .. } | ProxyConfigPayload::Ui { schema_version, .. } => {
                *schema_version
            }
        }
    }
}

/// Rejects a payload whose `schemaVersion` is not [`SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION`].
pub(crate) fn validate_schema_version(payload: &ProxyConfigPayload) -> Result<(), ProxyConfigError> {
    let found = payload.schema_version();
    if found == SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION {
        Ok(())
    } else {
        Err(ProxyConfigError::UnsupportedSchemaVersion { found })
    }
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
