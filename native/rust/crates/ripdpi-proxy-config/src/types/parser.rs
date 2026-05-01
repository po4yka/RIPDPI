use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiParserEvasionConfig {
    pub host_mixed_case: bool,
    pub domain_mixed_case: bool,
    pub host_remove_spaces: bool,
    #[serde(default)]
    pub http_method_eol: bool,
    #[serde(default)]
    pub http_unix_eol: bool,
    #[serde(default)]
    pub http_method_space: bool,
    #[serde(default)]
    pub http_host_pad: bool,
    #[serde(default)]
    pub http_host_extra_space: bool,
    #[serde(default)]
    pub http_host_tab: bool,
}
