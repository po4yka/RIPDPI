use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HttpProbeStatus {
    Ok,
    Blockpage,
    Unreachable,
    NotRun,
}
