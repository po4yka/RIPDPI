use hickory_proto::op::Message;
use hickory_proto::rr::RData;

use super::compression::has_malformed_compression_pointers;
use super::scoring::compute_tampering_score;
use super::wire_header::parse_header;

/// Tampering signals extracted from a raw DNS response.
#[derive(Debug, Clone, Default)]
pub struct DnsResponseAnalysis {
    // Header flags (from raw bytes).
    pub aa_flag: bool,
    pub tc_flag: bool,
    pub ra_flag: bool,
    pub rcode: u8,

    // Section counts.
    pub answer_count: u16,
    pub authority_count: u16,
    pub additional_count: u16,

    // TTL analysis (from answer section via hickory).
    pub min_ttl: Option<u32>,
    pub max_ttl: Option<u32>,
    pub ttl_uniform: bool,

    // EDNS0 / OPT record presence.
    pub has_edns0: bool,

    // CNAME targets found in answer section.
    pub cname_targets: Vec<String>,

    // Raw response size in bytes.
    pub response_size: usize,

    // Composite tampering score (0-100).
    pub tampering_score: u32,

    // Malformed compression pointer detected.
    pub malformed_pointers: bool,

    // Names of triggered anomaly signals.
    pub signals: Vec<&'static str>,
}

/// Analyze a raw DNS response packet for tampering indicators.
///
/// Returns useful results even on malformed packets: the raw byte layer
/// always extracts header flags and section counts from the first 12 bytes.
pub fn analyze_dns_response(packet: &[u8]) -> DnsResponseAnalysis {
    let mut analysis = DnsResponseAnalysis { response_size: packet.len(), ..Default::default() };

    if let Some(header) = parse_header(packet) {
        analysis.aa_flag = header.aa_flag;
        analysis.tc_flag = header.tc_flag;
        analysis.ra_flag = header.ra_flag;
        analysis.rcode = header.rcode;
        analysis.answer_count = header.answer_count;
        analysis.authority_count = header.authority_count;
        analysis.additional_count = header.additional_count;
    }

    if let Ok(message) = Message::from_vec(packet) {
        let mut ttls: Vec<u32> = Vec::new();

        for record in &message.answers {
            ttls.push(record.ttl);
            if let RData::CNAME(ref name) = &record.data {
                analysis.cname_targets.push(name.to_string());
            }
        }

        if !ttls.is_empty() {
            let min = *ttls.iter().min().expect("ttls is not empty");
            let max = *ttls.iter().max().expect("ttls is not empty");
            analysis.min_ttl = Some(min);
            analysis.max_ttl = Some(max);
            analysis.ttl_uniform = min == max;
        }

        analysis.has_edns0 = message.edns.is_some();
    }

    analysis.malformed_pointers = has_malformed_compression_pointers(packet);
    compute_tampering_score(&mut analysis);

    analysis
}
