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
