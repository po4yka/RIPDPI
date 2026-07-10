//! NDJSON parser for shared-priors bundles.
//!
//! Each line is one record. Legacy combo priors remain untagged:
//!
//! ```json
//! { "combo_hash": 12345678901234567890, "alpha": 12.0, "beta": 4.0 }
//! { "record_type": "protocol_threat", "protocol_class": "vless", "network_scope_key": "<sha256>", "state": "active_broad", "expires_at_unix": 1900000000 }
//! ```
//!
//! The format is intentionally narrow:
//! - Combo identity is a u64 hash computed by the strategy crate.
//!   so the priors can travel without leaking the combo definition itself.
//! - Posteriors are floats so the file can carry partial-attempt averages.
//! - Records are line-oriented so the file can be partially streamed and
//!   recovers from a single bad record without losing the rest.

use std::collections::HashMap;

use crate::{PriorParams, ProtocolThreatPriors};

/// Hard cap on accepted payload size (uncompressed). 256 KiB is about 4x the
/// 64 KiB compressed target for roughly 1000 arms; oversized inputs are
/// rejected with [`SharedPriorsError::PayloadTooLarge`] before any parsing is
/// attempted, so a malicious or corrupted bundle cannot stall startup.
pub const MAX_RAW_PAYLOAD: usize = 256 * 1024;

/// Errors surfaced when loading shared priors. Per-record errors are
/// returned alongside the successfully parsed records via [`Loaded`]; only
/// "the whole bundle is unusable" cases short-circuit with `Err`.
#[derive(Debug)]
pub enum SharedPriorsError {
    /// The raw input exceeded [`MAX_RAW_PAYLOAD`].
    PayloadTooLarge { actual: usize, limit: usize },
    /// A tagged protocol-threat record was invalid or duplicated. Threat
    /// records are all-or-nothing so a bad record cannot silently weaken the
    /// verified snapshot.
    InvalidThreatRecord { line: usize, reason: String },
}

impl std::fmt::Display for SharedPriorsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::PayloadTooLarge { actual, limit } => {
                write!(f, "shared priors payload {actual} bytes exceeds max {limit} bytes")
            }
            Self::InvalidThreatRecord { line, reason } => {
                write!(f, "invalid protocol threat record at line {line}: {reason}")
            }
        }
    }
}

impl std::error::Error for SharedPriorsError {}

/// Result of parsing a shared-priors bundle. Successful records are exposed
/// via [`Self::priors`]; per-line parse errors are accumulated in
/// [`Self::skipped`] so callers can log them without aborting the load.
#[derive(Debug, Default)]
pub struct Loaded {
    /// Parsed records keyed by combo hash. Duplicate hashes within a single
    /// bundle keep the *last* record encountered.
    pub priors: HashMap<u64, PriorParams>,
    /// Verified active-broad protocol records keyed by protocol class and
    /// opaque network scope hash.
    pub protocol_threats: ProtocolThreatPriors,
    /// `(line_number, reason)` for every record that failed validation.
    /// Empty on a clean parse.
    pub skipped: Vec<(usize, String)>,
}

/// Parse a newline-delimited JSON shared-priors bundle.
///
/// Lines that are blank or start with `#` (comment marker) are ignored.
/// Records that fail to parse, or whose `alpha` / `beta` are non-positive
/// or non-finite, are recorded in [`Loaded::skipped`] and dropped from
/// [`Loaded::priors`]; the rest of the bundle still loads.
pub fn parse(input: &str) -> Result<Loaded, SharedPriorsError> {
    if input.len() > MAX_RAW_PAYLOAD {
        return Err(SharedPriorsError::PayloadTooLarge { actual: input.len(), limit: MAX_RAW_PAYLOAD });
    }

    let mut loaded = Loaded::default();
    for (index, raw_line) in input.lines().enumerate() {
        let line_number = index + 1;
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        match parse_line(line) {
            Ok(ParsedRecord::Combo(hash, params)) => {
                loaded.priors.insert(hash, params);
            }
            Ok(ParsedRecord::ProtocolThreat(record)) => {
                if !loaded.protocol_threats.insert(
                    record.protocol_class,
                    record.network_scope_key,
                    record.expires_at_unix,
                ) {
                    return Err(SharedPriorsError::InvalidThreatRecord {
                        line: line_number,
                        reason: "duplicate protocol_class/network_scope_key pair".to_owned(),
                    });
                }
            }
            Err(LineError::Skippable(reason)) => {
                loaded.skipped.push((line_number, reason));
            }
            Err(LineError::InvalidThreat(reason)) => {
                return Err(SharedPriorsError::InvalidThreatRecord { line: line_number, reason });
            }
        }
    }
    Ok(loaded)
}

#[derive(serde::Deserialize)]
struct ComboRecord {
    combo_hash: u64,
    alpha: f64,
    beta: f64,
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct ProtocolThreatRecord {
    record_type: String,
    protocol_class: String,
    network_scope_key: String,
    state: String,
    expires_at_unix: i64,
}

enum ParsedRecord {
    Combo(u64, PriorParams),
    ProtocolThreat(ProtocolThreatRecord),
}

enum LineError {
    Skippable(String),
    InvalidThreat(String),
}

fn parse_line(line: &str) -> Result<ParsedRecord, LineError> {
    let value: serde_json::Value = serde_json::from_str(line).map_err(|err| {
        let reason = format!("invalid json: {err}");
        if line.contains("\"record_type\"") && line.contains("\"protocol_threat\"") {
            LineError::InvalidThreat(reason)
        } else {
            LineError::Skippable(reason)
        }
    })?;
    if value.get("record_type").and_then(serde_json::Value::as_str) == Some("protocol_threat") {
        return parse_protocol_threat(value).map(ParsedRecord::ProtocolThreat).map_err(LineError::InvalidThreat);
    }

    let record: ComboRecord =
        serde_json::from_value(value).map_err(|err| LineError::Skippable(format!("invalid json: {err}")))?;
    if !record.alpha.is_finite() || record.alpha <= 0.0 {
        return Err(LineError::Skippable(format!("alpha {} must be positive and finite", record.alpha)));
    }
    if !record.beta.is_finite() || record.beta <= 0.0 {
        return Err(LineError::Skippable(format!("beta {} must be positive and finite", record.beta)));
    }
    Ok(ParsedRecord::Combo(record.combo_hash, PriorParams { alpha: record.alpha, beta: record.beta }))
}

fn parse_protocol_threat(value: serde_json::Value) -> Result<ProtocolThreatRecord, String> {
    let record: ProtocolThreatRecord = serde_json::from_value(value).map_err(|err| format!("invalid json: {err}"))?;
    if record.record_type != "protocol_threat" {
        return Err("record_type must be protocol_threat".to_owned());
    }
    if record.state != "active_broad" {
        return Err("state must be active_broad".to_owned());
    }
    if !is_canonical_protocol_class(&record.protocol_class) {
        return Err("protocol_class must be lowercase snake_case ASCII".to_owned());
    }
    if !is_sha256_hex(&record.network_scope_key) {
        return Err("network_scope_key must be 64 lowercase hexadecimal characters".to_owned());
    }
    if record.expires_at_unix <= 0 {
        return Err("expires_at_unix must be positive".to_owned());
    }
    Ok(record)
}

fn is_canonical_protocol_class(value: &str) -> bool {
    value.split('_').all(|segment| {
        !segment.is_empty() && segment.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
    })
}

fn is_sha256_hex(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_loads_no_records() {
        let loaded = parse("").expect("parse must succeed");
        assert!(loaded.priors.is_empty());
        assert!(loaded.skipped.is_empty());
    }

    #[test]
    fn parses_well_formed_ndjson_bundle() {
        let input = "\
{\"combo_hash\": 1, \"alpha\": 12.0, \"beta\": 4.0}
{\"combo_hash\": 2, \"alpha\": 3.5, \"beta\": 1.5}
";
        let loaded = parse(input).expect("parse must succeed");
        assert_eq!(loaded.priors.len(), 2);
        assert!(loaded.protocol_threats.is_empty());
        assert!(loaded.skipped.is_empty());
        let p1 = loaded.priors.get(&1).expect("combo 1");
        assert!((p1.alpha - 12.0).abs() < f64::EPSILON);
        assert!((p1.beta - 4.0).abs() < f64::EPSILON);
    }

    #[test]
    fn skips_blank_and_comment_lines() {
        let input = "\n# header comment\n{\"combo_hash\": 7, \"alpha\": 2.0, \"beta\": 5.0}\n\n";
        let loaded = parse(input).expect("parse must succeed");
        assert_eq!(loaded.priors.len(), 1);
        assert!(loaded.skipped.is_empty(), "comments and blanks should not be reported as skipped");
    }

    #[test]
    fn rejects_oversized_payload() {
        let big = "x".repeat(MAX_RAW_PAYLOAD + 1);
        let err = parse(&big).expect_err("oversized payload should fail");
        match err {
            SharedPriorsError::PayloadTooLarge { actual, limit } => {
                assert_eq!(actual, MAX_RAW_PAYLOAD + 1);
                assert_eq!(limit, MAX_RAW_PAYLOAD);
            }
            SharedPriorsError::InvalidThreatRecord { .. } => panic!("expected oversized payload error"),
        }
    }

    #[test]
    fn skips_records_with_non_positive_alpha_or_beta() {
        let input = "\
{\"combo_hash\": 1, \"alpha\": 0.0, \"beta\": 1.0}
{\"combo_hash\": 2, \"alpha\": 1.0, \"beta\": -1.0}
{\"combo_hash\": 3, \"alpha\": 4.0, \"beta\": 2.0}
";
        let loaded = parse(input).expect("parse must succeed");
        assert_eq!(loaded.priors.len(), 1, "only the third record should survive");
        assert!(loaded.priors.contains_key(&3));
        assert_eq!(loaded.skipped.len(), 2);
    }

    #[test]
    fn skips_records_with_non_finite_values() {
        // serde_json rejects NaN / Infinity literals before we even see them,
        // so the error class for these inputs is "invalid json" rather than
        // "non-finite". Guarding the parser against both keeps callers safe
        // even if the upstream decoder later starts accepting them.
        let input = "\
{\"combo_hash\": 1, \"alpha\": 1e400, \"beta\": 1.0}
";
        let loaded = parse(input).expect("parse must succeed");
        assert!(loaded.priors.is_empty());
        assert_eq!(loaded.skipped.len(), 1);
    }

    #[test]
    fn skips_malformed_records_but_keeps_valid_ones() {
        let input = "\
not json at all
{\"combo_hash\": 42, \"alpha\": 9.0, \"beta\": 3.0}
{\"combo_hash\": \"not a number\", \"alpha\": 1.0, \"beta\": 1.0}
";
        let loaded = parse(input).expect("parse must succeed");
        assert_eq!(loaded.priors.len(), 1);
        assert!(loaded.priors.contains_key(&42));
        assert_eq!(loaded.skipped.len(), 2);
        let (line1, _) = &loaded.skipped[0];
        let (line2, _) = &loaded.skipped[1];
        assert_eq!(*line1, 1);
        assert_eq!(*line2, 3);
    }

    #[test]
    fn duplicate_combo_hash_keeps_last_record() {
        let input = "\
{\"combo_hash\": 5, \"alpha\": 1.0, \"beta\": 1.0}
{\"combo_hash\": 5, \"alpha\": 7.0, \"beta\": 2.0}
";
        let loaded = parse(input).expect("parse must succeed");
        assert_eq!(loaded.priors.len(), 1);
        let p = loaded.priors.get(&5).expect("combo 5");
        assert!((p.alpha - 7.0).abs() < f64::EPSILON);
        assert!((p.beta - 2.0).abs() < f64::EPSILON);
    }

    #[test]
    fn parses_tagged_protocol_threat_alongside_legacy_combo() {
        let scope = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let input = format!(
            "{{\"combo_hash\":1,\"alpha\":2.0,\"beta\":1.0}}\n{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"vless_reality\",\"network_scope_key\":\"{scope}\",\"state\":\"active_broad\",\"expires_at_unix\":2000}}\n"
        );
        let loaded = parse(&input).expect("mixed bundle must parse");

        assert_eq!(loaded.priors.len(), 1);
        assert_eq!(loaded.protocol_threats.len(), 1);
        assert_eq!(loaded.protocol_threats.beta_pseudo_failures("vless_reality", scope, 1000), 3.0);
        assert_eq!(loaded.protocol_threats.beta_pseudo_failures("vless_reality", scope, 2000), 0.0);
    }

    #[test]
    fn invalid_or_duplicate_protocol_threat_rejects_bundle() {
        let scope = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        let malformed = r#"{"record_type":"protocol_threat","protocol_class":"vless""#;
        assert!(matches!(parse(malformed), Err(SharedPriorsError::InvalidThreatRecord { line: 1, .. })));

        let invalid = format!(
            "{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"VLESS\",\"network_scope_key\":\"{scope}\",\"state\":\"active_broad\",\"expires_at_unix\":2000}}\n"
        );
        assert!(matches!(parse(&invalid), Err(SharedPriorsError::InvalidThreatRecord { line: 1, .. })));

        let duplicate = format!(
            "{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"vless\",\"network_scope_key\":\"{scope}\",\"state\":\"active_broad\",\"expires_at_unix\":2000}}\n{{\"record_type\":\"protocol_threat\",\"protocol_class\":\"vless\",\"network_scope_key\":\"{scope}\",\"state\":\"active_broad\",\"expires_at_unix\":3000}}\n"
        );
        assert!(matches!(parse(&duplicate), Err(SharedPriorsError::InvalidThreatRecord { line: 2, .. })));
    }
}
