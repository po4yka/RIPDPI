use std::net::Ipv4Addr;

use hickory_proto::op::Message;
use hickory_proto::rr::RData;
use hickory_proto::rr::rdata::A;
use hickory_proto::rr::rdata::svcb::{SvcParamKey, SvcParamValue};

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
    let mut response_leases = Vec::new();
    let result = (|| {
        let mut changed = false;
        for record in &mut message.answers {
            let replacement = match &record.data {
                RData::A(address) => {
                    let (mapped, hit) = cache.find(&host, u32::from(address.0))?;
                    // Keep earlier answers live until the complete response is encoded.
                    cache.pin(mapped);
                    response_leases.push(mapped);
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
                changed = true;
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
            changed |= strip_aaaa_records(&mut message);
        }
        changed |= strip_unmapped_addresses(&mut message, cache.ipv6_enabled);
        if changed {
            message.metadata.authentic_data = false;
        }

        Ok(DnsRewriteResult {
            response: message.to_vec().map_err(|err| DnsCacheError::DnsEncode(err.to_string()))?,
            host,
            cache_hits,
            cache_misses,
        })
    })();
    for mapped in response_leases {
        cache.unpin(mapped);
    }
    result
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
    // RFC 6891 6.1.1: a responder MUST answer an OPT request with an OPT; RFC 3225 copies DO.
    if let Some(edns) = Message::from_vec(query).ok().and_then(|message| message.edns) {
        response[10..12].copy_from_slice(&1u16.to_be_bytes());
        let ttl_flags: u16 = if edns.flags().dnssec_ok { 0x8000 } else { 0 };
        response.extend_from_slice(&[0x00, 0x00, 0x29, 0x04, 0xd0, 0x00, 0x00]);
        response.extend_from_slice(&ttl_flags.to_be_bytes());
        response.extend_from_slice(&0u16.to_be_bytes());
    }
    Ok(response)
}

/// Remove real addresses that would bypass the synthetic A mapping.
///
/// Only answer A records are mapped, so A records in additionals/authorities
/// would leak unmapped real IPv4 addresses. HTTPS/SVCB `ipv4hint` is always
/// dropped for the same reason, and `ipv6hint` when tunnel IPv6 is off. Hints
/// are optional in RFC 9460, so clients still resolve A/AAAA. Hints are not
/// rewritten into synthetic addresses. A record that lists a removed hint in
/// `mandatory` is dropped whole (RFC 9460 section 8: a client must ignore a
/// record whose mandatory key it cannot honour). The TTL stays as is: unlike a
/// synthetic A answer, a stripped record holds nothing tied to this tunnel
/// session.
fn strip_unmapped_addresses(message: &mut Message, ipv6_enabled: bool) -> bool {
    let removed = |key: &SvcParamKey| {
        matches!(key, SvcParamKey::Ipv4Hint) || (!ipv6_enabled && matches!(key, SvcParamKey::Ipv6Hint))
    };
    let mut changed = false;
    for section in [&mut message.additionals, &mut message.authorities] {
        let before = section.len();
        section.retain(|record| !matches!(&record.data, RData::A(_)));
        changed |= section.len() != before;
    }
    for section in [&mut message.answers, &mut message.additionals, &mut message.authorities] {
        let before = section.len();
        section.retain_mut(|record| {
            let svcb = match &mut record.data {
                RData::HTTPS(https) => &mut https.0,
                RData::SVCB(svcb) => svcb,
                _ => return true,
            };
            let mandatory_removed = svcb
                .svc_params
                .iter()
                .any(|(_, value)| matches!(value, SvcParamValue::Mandatory(list) if list.0.iter().any(removed)));
            if mandatory_removed {
                return false;
            }
            let params = svcb.svc_params.len();
            svcb.svc_params.retain(|(key, _)| !removed(key));
            changed |= params != svcb.svc_params.len();
            true
        });
        changed |= section.len() != before;
    }
    changed
}

fn strip_aaaa_records(message: &mut Message) -> bool {
    // Strip AAAA records from answers, additionals, and name-servers so
    // Android's Happy Eyeballs algorithm cannot select an address family that
    // the active VPN interface does not route.
    let record_count = message.answers.len() + message.additionals.len() + message.authorities.len();
    message.answers.retain(|record| !matches!(&record.data, RData::AAAA(_)));
    message.additionals.retain(|record| !matches!(&record.data, RData::AAAA(_)));
    message.authorities.retain(|record| !matches!(&record.data, RData::AAAA(_)));
    record_count != message.answers.len() + message.additionals.len() + message.authorities.len()
}
