//! NDJSON parser for shared-priors bundles (P4.4.4, ADR-011).
//!
//! Each line is one record:
//!
//! ```json
//! { "combo_hash": 12345678901234567890, "alpha": 12.0, "beta": 4.0 }
//! ```
//!
//! Per ADR-011, the format is intentionally narrow:
//! - Combo identity is a u64 hash (see [`super::loader::canonical_combo_hash`])
//!   so the priors can travel without leaking the combo definition itself.
//! - Posteriors are floats so the file can carry partial-attempt averages.
//! - Records are line-oriented so the file can be partially streamed and
//!   recovers from a single bad record without losing the rest.

use std::collections::HashMap;

use crate::strategy_evolver::thompson_sampling::BetaParams;

/// Hard cap on accepted payload size (uncompressed). 256 KiB ≈ 4× the 64 KiB
/// compressed budget the ADR proposes for ~1000 arms; oversized inputs are
/// rejected with [`SharedPriorsError::PayloadTooLarge`] before any parsing
/// is attempted, so a malicious or corrupted bundle cannot stall startup.
pub const MAX_RAW_PAYLOAD: usize = 256 * 1024;

/// Errors surfaced when loading shared priors. Per-record errors are
/// returned alongside the successfully parsed records via [`Loaded`]; only
/// "the whole bundle is unusable" cases short-circuit with `Err`.
#[derive(Debug)]
pub enum SharedPriorsError {
    /// The raw input exceeded [`MAX_RAW_PAYLOAD`].
    PayloadTooLarge { actual: usize, limit: usize },
}

impl std::fmt::Display for SharedPriorsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::PayloadTooLarge { actual, limit } => {
                write!(f, "shared priors payload {actual} bytes exceeds max {limit} bytes")
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
    pub priors: HashMap<u64, BetaParams>,
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
            Ok((hash, params)) => {
                loaded.priors.insert(hash, params);
            }
            Err(reason) => {
                loaded.skipped.push((line_number, reason));
            }
        }
    }
    Ok(loaded)
}

#[derive(serde::Deserialize)]
struct Record {
    combo_hash: u64,
    alpha: f64,
    beta: f64,
}

fn parse_line(line: &str) -> Result<(u64, BetaParams), String> {
    let record: Record = serde_json::from_str(line).map_err(|err| format!("invalid json: {err}"))?;
    if !record.alpha.is_finite() || record.alpha <= 0.0 {
        return Err(format!("alpha {} must be positive and finite", record.alpha));
    }
    if !record.beta.is_finite() || record.beta <= 0.0 {
        return Err(format!("beta {} must be positive and finite", record.beta));
    }
    Ok((record.combo_hash, BetaParams { alpha: record.alpha, beta: record.beta }))
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
        assert!(loaded.skipped.is_empty());
        let p1 = loaded.priors.get(&1).expect("combo 1");
        assert!((p1.alpha - 12.0).abs() < f64::EPSILON);
        assert!((p1.beta - 4.0).abs() < f64::EPSILON);
    }

    #[test]
    fn skips_blank_and_comment_lines() {
        let input = "\

# header comment
{\"combo_hash\": 7, \"alpha\": 2.0, \"beta\": 5.0}

";
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
}
