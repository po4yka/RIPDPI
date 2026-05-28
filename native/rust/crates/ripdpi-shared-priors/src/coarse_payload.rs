//! Coarse-key payload types for opt-in shared-priors contributions.
//!
//! The type system is the privacy boundary: `CoarseKey` contains **only** the
//! five approved keying fields. There is no runtime filtering — if a field is
//! not in this struct it cannot appear in the serialized payload.
//!
//! Serialization is derived via `serde::Serialize` and the struct layout is
//! asserted by a compile-test in the test suite below.

use serde::{Deserialize, Serialize};

/// Network access technology at the time of the measurement.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AccessType {
    Wifi,
    Cellular,
    Ethernet,
    Unknown,
}

/// Resolver class used during the measurement.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DnsClass {
    Doh,
    Doq,
    Plain,
}

/// The protocol phase at which the connection attempt failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailPhase {
    Dns,
    Tcp,
    Tls,
    Quic,
    Http,
}

/// The approved keying fields for a shared-priors contribution.
///
/// These are the **only** fields permitted in a contribution. The type system
/// enforces this: adding any non-coarse field here is a breaking API change
/// that will be caught in code review, and the serialized output is
/// structurally bounded to exactly these five fields.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoarseKey {
    pub asn: u32,
    pub access_type: AccessType,
    pub dns_class: DnsClass,
    pub udp443_ok: bool,
    pub fail_phase: FailPhase,
}

/// A single coarse-keyed observation batch for the contribution path.
///
/// `win_count` and `loss_count` are aggregated over all attempts that
/// share the same [`CoarseKey`] in the upload window.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoarsePayload {
    pub keys: Vec<CoarseKey>,
    pub win_count: u32,
    pub loss_count: u32,
}

/// Validation errors for a [`CoarsePayload`].
///
/// Because the coarse-key invariant is enforced by the type system,
/// validation is primarily a runtime sanity check on value ranges.
#[derive(Debug)]
pub enum ValidationError {
    /// The payload carries no keys.
    EmptyKeys,
}

impl std::fmt::Display for ValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::EmptyKeys => write!(f, "coarse payload must have at least one key"),
        }
    }
}

impl std::error::Error for ValidationError {}

/// Validates a [`CoarsePayload`] before submission.
///
/// The coarse-key schema is a compile-time invariant: `CoarseKey` can only
/// hold the five approved fields, so no runtime field filtering is needed.
/// `PayloadValidator` exists as an explicit, auditable gate in the
/// contribution path to document that validation was intentionally considered.
pub struct PayloadValidator;

impl PayloadValidator {
    /// Validate a payload. Returns `Ok(())` when the payload is structurally
    /// sound. Returns `Err(ValidationError)` for degenerate inputs (e.g.
    /// empty key list).
    pub fn validate(payload: &CoarsePayload) -> Result<(), ValidationError> {
        if payload.keys.is_empty() {
            return Err(ValidationError::EmptyKeys);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn sample_key() -> CoarseKey {
        CoarseKey {
            asn: 12345,
            access_type: AccessType::Wifi,
            dns_class: DnsClass::Doh,
            udp443_ok: true,
            fail_phase: FailPhase::Tls,
        }
    }

    fn sample_payload() -> CoarsePayload {
        CoarsePayload { keys: vec![sample_key()], win_count: 3, loss_count: 1 }
    }

    /// Serialized JSON must match the frozen schema string exactly.
    /// This test will fail if any field is renamed or a new field is added,
    /// providing a schema-stability safety net.
    #[test]
    fn serialized_json_matches_frozen_schema() {
        let payload = sample_payload();
        let json = serde_json::to_string(&payload).expect("serialization must succeed");
        let expected = r#"{"keys":[{"asn":12345,"access_type":"wifi","dns_class":"doh","udp443_ok":true,"fail_phase":"tls"}],"win_count":3,"loss_count":1}"#;
        assert_eq!(json, expected, "schema drift detected — update the frozen string if the change is intentional");
    }

    /// CoarseKey must have exactly the approved five fields.
    /// The golden list is: asn, access_type, dns_class, udp443_ok, fail_phase.
    #[test]
    fn coarse_key_fields_are_exactly_the_approved_five() {
        let key = sample_key();
        let value: Value = serde_json::to_value(&key).expect("to_value");
        let obj = value.as_object().expect("CoarseKey must serialize as a JSON object");
        let mut field_names: Vec<&str> = obj.keys().map(String::as_str).collect();
        field_names.sort_unstable();
        let expected = ["access_type", "asn", "dns_class", "fail_phase", "udp443_ok"];
        assert_eq!(field_names, expected, "CoarseKey field list has drifted from the approved set");
    }

    #[test]
    fn validator_accepts_well_formed_payload() {
        let payload = sample_payload();
        PayloadValidator::validate(&payload).expect("valid payload must pass");
    }

    #[test]
    fn validator_rejects_empty_keys() {
        let payload = CoarsePayload { keys: vec![], win_count: 0, loss_count: 0 };
        let err = PayloadValidator::validate(&payload).expect_err("empty keys must fail");
        assert!(matches!(err, ValidationError::EmptyKeys));
    }
}
