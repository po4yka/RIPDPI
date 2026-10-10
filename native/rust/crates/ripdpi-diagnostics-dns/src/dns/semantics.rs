use hickory_proto::op::{Message, MessageType, OpCode, ResponseCode};
use hickory_proto::rr::rdata::opt::EdnsOption;
use hickory_proto::rr::{RData, RecordType};
use ripdpi_diagnostics_contracts::types::{DnsResponseOutcome, DnsResponseSemantics};

/// Parse diagnostic facts without relaxing the runtime resolver's strict API.
/// Negative and truncated replies are facts, but never usable address answers.
pub fn parse_dns_response_semantics(
    query: &[u8],
    response: &[u8],
) -> Result<(Vec<String>, DnsResponseSemantics), String> {
    let request = Message::from_vec(query).map_err(|_| "dns_malformed_query")?;
    let reply = Message::from_vec(response).map_err(|_| "dns_malformed_response")?;
    if request.queries.len() != 1
        || request.metadata.message_type != MessageType::Query
        || request.metadata.op_code != OpCode::Query
        || reply.metadata.id != request.metadata.id
        || reply.metadata.message_type != MessageType::Response
        || reply.metadata.op_code != OpCode::Query
        || reply.queries != request.queries
    {
        return Err("dns_response_mismatch".to_string());
    }
    let question = &request.queries[0];
    let query_type = match question.query_type {
        RecordType::A => "A",
        RecordType::AAAA => "AAAA",
        _ => return Err("dns_unsupported_query_type".to_string()),
    };
    let mut names = vec![question.name.clone()];
    while let Some(owner) = names.last() {
        let targets = reply
            .answers
            .iter()
            .filter_map(|record| match &record.data {
                RData::CNAME(target) if record.dns_class == question.query_class && &record.name == owner => {
                    Some(&target.0)
                }
                _ => None,
            })
            .collect::<Vec<_>>();
        let Some(target) = targets.first() else {
            break;
        };
        if targets.iter().any(|other| other != target) || names.contains(target) {
            return Err("dns_invalid_cname_chain".to_string());
        }
        if names.len() >= 17 {
            return Err("dns_cname_chain_too_long".to_string());
        }
        names.push((*target).clone());
    }
    if reply.answers.iter().any(|record| {
        matches!(record.data, RData::A(_) | RData::AAAA(_) | RData::CNAME(_))
            && (record.dns_class != question.query_class || !names.contains(&record.name))
    }) {
        return Err("dns_answer_owner_mismatch".to_string());
    }
    if reply
        .answers
        .iter()
        .any(|record| matches!(record.data, RData::A(_) | RData::AAAA(_)) && names.last() != Some(&record.name))
    {
        return Err("dns_address_at_cname_owner".to_string());
    }
    let mut addresses = Vec::new();
    let mut ttls = Vec::new();
    for record in &reply.answers {
        let address = match (&record.data, question.query_type) {
            (RData::A(ip), RecordType::A) => Some(ip.0.to_string()),
            (RData::AAAA(ip), RecordType::AAAA) => Some(ip.0.to_string()),
            _ => None,
        };
        if let Some(address) = address {
            addresses.push(address);
            ttls.push(record.ttl);
        }
    }
    addresses.sort();
    addresses.dedup();
    let negative_ttl = reply
        .authorities
        .iter()
        .filter_map(|record| match &record.data {
            RData::SOA(soa)
                if record.dns_class == question.query_class
                    && names.last().is_some_and(|name| record.name.zone_of(name)) =>
            {
                Some(record.ttl.min(soa.minimum))
            }
            _ => None,
        })
        .min();
    let outcome = if reply.metadata.truncation {
        DnsResponseOutcome::Truncated
    } else {
        match reply.metadata.response_code {
            ResponseCode::NoError
                if addresses.is_empty()
                    && negative_ttl.is_none()
                    && (names.len() > 1
                        || reply.authorities.iter().any(|record| matches!(record.data, RData::NS(_)))) =>
            {
                DnsResponseOutcome::NotObserved
            }
            ResponseCode::NoError if addresses.is_empty() => DnsResponseOutcome::Nodata,
            ResponseCode::NoError => DnsResponseOutcome::Answer,
            ResponseCode::NXDomain => DnsResponseOutcome::Nxdomain,
            ResponseCode::ServFail => DnsResponseOutcome::Servfail,
            ResponseCode::Refused => DnsResponseOutcome::Refused,
            _ => DnsResponseOutcome::OtherRcode,
        }
    };
    let mut facts = DnsResponseSemantics::unobserved(query_type, outcome);
    facts.rcode = Some(u16::from(reply.metadata.response_code));
    facts.truncated = Some(reply.metadata.truncation);
    facts.authoritative = Some(reply.metadata.authoritative);
    facts.recursion_available = Some(reply.metadata.recursion_available);
    facts.authenticated_data = Some(reply.metadata.authentic_data);
    facts.has_soa = Some(reply.authorities.iter().any(|record| matches!(record.data, RData::SOA(_))));
    facts.cname_targets = names
        .iter()
        .skip(1)
        .map(|name| name.to_ascii().trim_end_matches('.').to_string())
        .filter(|name| name.len() <= 253)
        .take(16)
        .collect();
    if outcome == DnsResponseOutcome::Answer {
        facts.ttl_min_seconds = ttls.iter().copied().min();
        facts.ttl_max_seconds = ttls.iter().copied().max();
    } else {
        addresses.clear();
    }
    if matches!(outcome, DnsResponseOutcome::Nodata | DnsResponseOutcome::Nxdomain) {
        facts.negative_ttl_seconds = negative_ttl;
    }
    if let Some(edns) = &reply.edns {
        facts.extended_dns_error_codes = edns
            .options()
            .options
            .iter()
            .filter_map(|(_, option)| match option {
                EdnsOption::Unknown(15, data) if data.len() >= 2 => Some(u16::from_be_bytes([data[0], data[1]])),
                _ => None,
            })
            .take(16)
            .collect();
    }
    Ok((addresses, facts))
}

pub(crate) fn transport_semantics(error: &str) -> DnsResponseSemantics {
    let outcome = if matches!(super::classify_udp_dns_error(error), "timeout" | "would_block") {
        DnsResponseOutcome::Timeout
    } else {
        DnsResponseOutcome::TransportError
    };
    DnsResponseSemantics::unobserved("A", outcome)
}

pub(crate) fn semantic_address_result(
    addresses: Vec<String>,
    facts: &DnsResponseSemantics,
) -> Result<Vec<String>, String> {
    match facts.outcome {
        DnsResponseOutcome::Answer => Ok(addresses),
        DnsResponseOutcome::Nxdomain => Err("dns_nxdomain".to_string()),
        DnsResponseOutcome::Nodata => Err("dns_nodata".to_string()),
        DnsResponseOutcome::Servfail => Err("dns_servfail".to_string()),
        DnsResponseOutcome::Refused => Err("dns_refused".to_string()),
        DnsResponseOutcome::Truncated => Err("dns_truncated".to_string()),
        _ => Err("dns_response_unusable".to_string()),
    }
}

#[cfg(test)]
mod tests;
