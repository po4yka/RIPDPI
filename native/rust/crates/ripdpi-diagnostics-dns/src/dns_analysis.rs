//! DNS response analysis for tampering detection.
//!
//! Inspects raw DNS response bytes to extract protocol-level anomaly signals
//! that indicate forged responses (middlebox injection, ISP DNS hijacking, etc.).
//! Uses a two-layer approach: raw byte header parsing (always works) plus
//! hickory-proto `Message` parsing (best-effort, graceful fallback).

mod compression;
mod records;
mod response;
mod scoring;
mod wire_header;

#[cfg(test)]
mod tests;

pub use compression::has_malformed_compression_pointers;
pub use records::{DnsComparisonResult, DnsRecord, DnsRecordSet, compare_dns_responses, parse_record_set};
pub use response::{DnsResponseAnalysis, analyze_dns_response};
