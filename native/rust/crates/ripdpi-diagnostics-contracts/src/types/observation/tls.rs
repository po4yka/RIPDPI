use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TlsProbeStatus {
    Ok,
    HandshakeFailed,
    VersionSplit,
    CertInvalid,
    NotRun,
}
