use android_support::NativeEventRecord;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,
    pub(crate) level: String,
    pub(crate) message: String,
    pub(crate) created_at: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) runtime_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) policy_signature: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) fingerprint_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) diagnostics_session_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) subsystem: Option<String>,
}

impl From<NativeEventRecord> for NativeRuntimeEvent {
    fn from(value: NativeEventRecord) -> Self {
        Self {
            source: value.source,
            level: value.level,
            message: value.message,
            created_at: value.created_at,
            kind: value.kind,
            runtime_id: value.runtime_id,
            mode: value.mode,
            policy_signature: value.policy_signature,
            fingerprint_hash: value.fingerprint_hash,
            diagnostics_session_id: value.diagnostics_session_id,
            subsystem: value.subsystem,
        }
    }
}
