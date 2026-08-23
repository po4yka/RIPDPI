pub(crate) use super::*;
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResolvedRelayFinalmaskConfig {
    #[serde(default)]
    pub r#type: String,
    #[serde(default)]
    pub header_hex: String,
    #[serde(default)]
    pub trailer_hex: String,
    #[serde(default)]
    pub rand_range: String,
    #[serde(default)]
    pub sudoku_seed: String,
    #[serde(default)]
    pub fragment_packets: i32,
    #[serde(default)]
    pub fragment_min_bytes: i32,
    #[serde(default)]
    pub fragment_max_bytes: i32,
}

impl Default for ResolvedRelayFinalmaskConfig {
    fn default() -> Self {
        Self {
            r#type: "off".to_string(),
            header_hex: String::new(),
            trailer_hex: String::new(),
            rand_range: String::new(),
            sudoku_seed: String::new(),
            fragment_packets: 0,
            fragment_min_bytes: 0,
            fragment_max_bytes: 0,
        }
    }
}
