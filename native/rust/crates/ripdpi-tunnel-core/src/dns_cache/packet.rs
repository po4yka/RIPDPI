use std::net::Ipv4Addr;

use hickory_proto::op::Message;
use hickory_proto::rr::RData;
use hickory_proto::rr::rdata::A;

use super::parser::{dns_question_end, primary_question_name};
use super::{DnsCache, DnsCacheError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsRewriteResult {
    pub response: Vec<u8>,
    pub host: String,
    pub cache_hits: u64,
    pub cache_misses: u64,
}

pub(super) fn rewrite_response(
    cache: &mut DnsCache,
    query: &[u8],
    upstream: &[u8],
) -> Result<DnsRewriteResult, DnsCacheError> {
    let host = primary_question_name(upstream).or_else(|_| primary_question_name(query))?;
    let mut message = Message::from_vec(upstream).map_err(|err| DnsCacheError::DnsParse(err.to_string()))?;
    let mut cache_hits = 0u64;
    let mut cache_misses = 0u64;

    for record in &mut message.answers {
        let replacement = match &record.data {
            RData::A(address) => {
                let (mapped, hit) = cache.find(&host, u32::from(address.0))?;
                if hit {
                    cache_hits += 1;
                } else {
                    cache_misses += 1;
                }
                Some(RData::A(A(Ipv4Addr::from(mapped))))
            }
            _ => None,
        };
        if let Some(data) = replacement {
            record.data = data;
            // The reverse map is owned by this native tunnel session. Android's
            // resolver cache can outlive that session (and even the app
            // process), so caching a synthetic address would let a later
            // session receive an address it cannot reverse. Keep the mapping in
            // our LRU for live flows, but require the platform resolver to ask
            // again after a tunnel restart.
            record.ttl = 0;
        }
    }

    if !cache.ipv6_enabled {
        strip_aaaa_records(&mut message);
    }

    Ok(DnsRewriteResult {
        response: message.to_vec().map_err(|err| DnsCacheError::DnsEncode(err.to_string()))?,
        host,
        cache_hits,
        cache_misses,
    })
}

pub(super) fn servfail_response(query: &[u8]) -> Result<Vec<u8>, DnsCacheError> {
    if query.len() < 12 {
        return Err(DnsCacheError::Truncated);
    }

    let question_end = dns_question_end(query)?;
    let recursion_desired = u16::from_be_bytes([query[2], query[3]]) & 0x0100;
    let flags = 0x8000u16 | 0x0080u16 | recursion_desired | 0x0002u16;
    let mut response = Vec::with_capacity(question_end);
    response.extend_from_slice(&query[0..2]);
    response.extend_from_slice(&flags.to_be_bytes());
    response.extend_from_slice(&query[4..6]);
    response.extend_from_slice(&0u16.to_be_bytes());
    response.extend_from_slice(&0u16.to_be_bytes());
    response.extend_from_slice(&0u16.to_be_bytes());
    response.extend_from_slice(&query[12..question_end]);
    Ok(response)
}

fn strip_aaaa_records(message: &mut Message) {
    // Strip AAAA records from answers, additionals, and name-servers so
    // Android's Happy Eyeballs algorithm cannot select an address family that
    // the active VPN interface does not route.
    message.answers.retain(|record| !matches!(&record.data, RData::AAAA(_)));
    message.additionals.retain(|record| !matches!(&record.data, RData::AAAA(_)));
    message.authorities.retain(|record| !matches!(&record.data, RData::AAAA(_)));
}
