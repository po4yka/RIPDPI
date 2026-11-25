use hickory_proto::op::Message;
use hickory_proto::rr::RData;

use super::compression::has_malformed_compression_pointers;
use super::scoring::compute_tampering_score;
use super::wire_header::parse_header;

#[derive(Debug, Clone, Default)]
pub struct DnsResponseAnalysis {
    pub aa_flag: bool,
    pub tc_flag: bool,
    pub ra_flag: bool,
    pub rcode: u8,

    pub answer_count: u16,
    pub authority_count: u16,
    pub additional_count: u16,

    pub min_ttl: Option<u32>,
    pub max_ttl: Option<u32>,
    pub ttl_uniform: bool,

    pub has_edns0: bool,

    pub cname_targets: Vec<String>,

    pub response_size: usize,

    pub tampering_score: u32,

    pub malformed_pointers: bool,

    pub signals: Vec<&'static str>,
}

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
            if let RData::CNAME(name) = &record.data {
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
