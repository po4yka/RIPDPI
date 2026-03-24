use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::num::NonZeroUsize;

use hickory_proto::op::Message;
use hickory_proto::rr::rdata::A;
use hickory_proto::rr::RData;
use lru::LruCache;
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsCacheEntry {
    pub host: String,
    pub real_ip: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsRewriteResult {
    pub response: Vec<u8>,
    pub host: String,
    pub cache_hits: u64,
    pub cache_misses: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct DnsCacheKey {
    host: String,
    real_ip: u32,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum DnsCacheError {
    #[error("cache size must be greater than zero")]
    EmptyCache,
    #[error("response buffer smaller than request")]
    BufferTooSmall,
    #[error("truncated or malformed DNS packet")]
    Truncated,
    #[error("dns message parse failed: {0}")]
    DnsParse(String),
    #[error("dns message serialization failed: {0}")]
    DnsEncode(String),
}

/// LRU DNS cache that maps real IPv4 answers to synthetic IPv4 addresses.
///
/// The cache allocates addresses from the range `[net, net+max)` and preserves
/// a reverse mapping so later tunnel sessions can turn a synthetic destination
/// back into the original upstream IPv4 address.
pub struct DnsCache {
    lru: LruCache<DnsCacheKey, usize>,
    rev: HashMap<u32, DnsCacheEntry>,
    records: Vec<Option<DnsCacheKey>>,
    net: u32,
    mask: u32,
    max: usize,
    next_free: usize,
}

impl DnsCache {
    pub fn new(net: u32, mask: u32, max: usize) -> Self {
        debug_assert!(max > 0, "max must be non-zero");
        debug_assert!((max as u64) <= ((!mask) as u64), "max exceeds addressable range");
        let capacity = NonZeroUsize::new(max).expect("max must be > 0");
        Self {
            lru: LruCache::new(capacity),
            rev: HashMap::new(),
            records: vec![None; max],
            net,
            mask,
            max,
            next_free: 0,
        }
    }

    pub fn lookup(&mut self, ip: u32) -> Option<DnsCacheEntry> {
        if ip & self.mask != self.net {
            return None;
        }

        let entry = self.rev.get(&ip)?.clone();
        self.lru.get(&DnsCacheKey { host: entry.host.clone(), real_ip: entry.real_ip });
        Some(entry)
    }

    pub fn rewrite_response(&mut self, query: &[u8], upstream: &[u8]) -> Result<DnsRewriteResult, DnsCacheError> {
        let host = primary_question_name(upstream).or_else(|_| primary_question_name(query))?;
        let mut message = Message::from_vec(upstream).map_err(|err| DnsCacheError::DnsParse(err.to_string()))?;
        let mut cache_hits = 0u64;
        let mut cache_misses = 0u64;

        for record in message.answers_mut().iter_mut() {
            let replacement = match record.data() {
                Some(RData::A(address)) => {
                    let (mapped, hit) = self.find(&host, u32::from(address.0))?;
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
                record.set_data(Some(data));
            }
        }

        Ok(DnsRewriteResult {
            response: message.to_vec().map_err(|err| DnsCacheError::DnsEncode(err.to_string()))?,
            host,
            cache_hits,
            cache_misses,
        })
    }

    pub fn servfail_response(&self, query: &[u8]) -> Result<Vec<u8>, DnsCacheError> {
        let _ = self;
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

    fn find(&mut self, host: &str, real_ip: u32) -> Result<(u32, bool), DnsCacheError> {
        if self.max == 0 {
            return Err(DnsCacheError::EmptyCache);
        }

        let key = DnsCacheKey { host: host.to_string(), real_ip };

        if let Some(&idx) = self.lru.get(&key) {
            return Ok((self.net | idx as u32, true));
        }

        let idx = if self.next_free < self.max {
            let idx = self.next_free;
            self.next_free += 1;
            idx
        } else {
            let (evicted, evicted_idx) = self.lru.pop_lru().ok_or(DnsCacheError::EmptyCache)?;
            let evicted_ip = self.net | evicted_idx as u32;
            self.rev.remove(&evicted_ip);
            self.records[evicted_idx] = None;
            let _ = evicted;
            evicted_idx
        };

        let fake_ip = self.net | idx as u32;
        self.lru.put(key.clone(), idx);
        self.records[idx] = Some(key.clone());
        self.rev.insert(fake_ip, DnsCacheEntry { host: key.host, real_ip });
        Ok((fake_ip, false))
    }
}

fn primary_question_name(packet: &[u8]) -> Result<String, DnsCacheError> {
    let message = Message::from_vec(packet).map_err(|err| DnsCacheError::DnsParse(err.to_string()))?;
    let query = message.query().ok_or(DnsCacheError::Truncated)?;
    Ok(query.name().to_utf8().trim_end_matches('.').to_string())
}

fn dns_question_end(packet: &[u8]) -> Result<usize, DnsCacheError> {
    let question_count = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = skip_dns_name(packet, offset)?;
        offset = offset.checked_add(4).ok_or(DnsCacheError::Truncated)?;
        if offset > packet.len() {
            return Err(DnsCacheError::Truncated);
        }
    }
    Ok(offset)
}

fn skip_dns_name(packet: &[u8], mut offset: usize) -> Result<usize, DnsCacheError> {
    loop {
        let Some(length) = packet.get(offset).copied() else {
            return Err(DnsCacheError::Truncated);
        };
        if length & 0b1100_0000 == 0b1100_0000 {
            if offset + 1 >= packet.len() {
                return Err(DnsCacheError::Truncated);
            }
            return Ok(offset + 2);
        }
        offset += 1;
        if length == 0 {
            return Ok(offset);
        }
        offset += length as usize;
        if offset > packet.len() {
            return Err(DnsCacheError::Truncated);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
    use hickory_proto::rr::rdata::{A, AAAA};
    use hickory_proto::rr::{Name, Record, RecordType};
    use std::net::Ipv6Addr;

    const NET: u32 = 0xC612_0000;
    const MASK: u32 = 0xFFFE_0000;

    fn build_query(name: &str) -> Vec<u8> {
        let mut message = Message::new();
        message
            .set_id(0x1234)
            .set_message_type(MessageType::Query)
            .set_op_code(OpCode::Query)
            .set_recursion_desired(true)
            .add_query(Query::query(Name::from_ascii(name).expect("name"), RecordType::A));
        message.to_vec().expect("query encodes")
    }

    fn build_response(name: &str, ips: &[Ipv4Addr], include_aaaa: bool) -> Vec<u8> {
        let mut message = Message::new();
        message
            .set_id(0x1234)
            .set_message_type(MessageType::Response)
            .set_op_code(OpCode::Query)
            .set_recursion_desired(true)
            .set_recursion_available(true)
            .set_response_code(ResponseCode::NoError)
            .add_query(Query::query(Name::from_ascii(name).expect("name"), RecordType::A));

        for ip in ips {
            let mut record = Record::with(Name::from_ascii(name).expect("name"), RecordType::A, 60);
            record.set_data(Some(RData::A(A(*ip))));
            message.add_answer(record);
        }

        if include_aaaa {
            let mut record = Record::with(Name::from_ascii(name).expect("name"), RecordType::AAAA, 120);
            record.set_data(Some(RData::AAAA(AAAA(Ipv6Addr::LOCALHOST))));
            message.add_answer(record);
        }

        message.to_vec().expect("response encodes")
    }

    fn a_answers(packet: &[u8]) -> Vec<Ipv4Addr> {
        let message = Message::from_vec(packet).expect("dns packet parses");
        message
            .answers()
            .iter()
            .filter_map(|record| match record.data() {
                Some(RData::A(address)) => Some(address.0),
                _ => None,
            })
            .collect()
    }

    fn aaaa_answers(packet: &[u8]) -> Vec<Ipv6Addr> {
        let message = Message::from_vec(packet).expect("dns packet parses");
        message
            .answers()
            .iter()
            .filter_map(|record| match record.data() {
                Some(RData::AAAA(address)) => Some(address.0),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn rewrite_response_maps_a_records_and_preserves_reverse_lookup() {
        let mut cache = DnsCache::new(NET, MASK, 8);
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", &[Ipv4Addr::new(203, 0, 113, 10)], false);

        let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
        let answers = a_answers(&rewritten.response);
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0].octets()[0..2], [198, 18]);
        assert_eq!(rewritten.host, "fixture.test");
        assert_eq!(rewritten.cache_hits, 0);
        assert_eq!(rewritten.cache_misses, 1);

        let reverse = cache.lookup(u32::from(answers[0])).expect("reverse lookup must resolve mapped IP");
        assert_eq!(reverse.host, "fixture.test");
        assert_eq!(Ipv4Addr::from(reverse.real_ip), Ipv4Addr::new(203, 0, 113, 10));
    }

    #[test]
    fn rewrite_response_reuses_existing_mapping_for_same_host_and_real_ip() {
        let mut cache = DnsCache::new(NET, MASK, 8);
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", &[Ipv4Addr::new(203, 0, 113, 10)], false);

        let first = cache.rewrite_response(&query, &upstream).expect("first rewrite");
        let second = cache.rewrite_response(&query, &upstream).expect("second rewrite");

        assert_eq!(a_answers(&first.response), a_answers(&second.response));
        assert_eq!(second.cache_hits, 1);
        assert_eq!(second.cache_misses, 0);
    }

    #[test]
    fn rewrite_response_preserves_non_a_answers() {
        let mut cache = DnsCache::new(NET, MASK, 8);
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", &[Ipv4Addr::new(203, 0, 113, 10)], true);

        let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
        assert_eq!(aaaa_answers(&rewritten.response), vec![Ipv6Addr::LOCALHOST]);
    }

    #[test]
    fn servfail_response_sets_error_and_keeps_question() {
        let cache = DnsCache::new(NET, MASK, 8);
        let query = build_query("fixture.test");

        let response = cache.servfail_response(&query).expect("servfail builds");
        let message = Message::from_vec(&response).expect("response parses");
        assert_eq!(message.response_code(), ResponseCode::ServFail);
        assert_eq!(message.queries().len(), 1);
        assert!(message.answers().is_empty());
    }

    #[test]
    fn eviction_removes_stale_reverse_mapping() {
        let mut cache = DnsCache::new(NET, MASK, 1);
        let first = cache
            .rewrite_response(
                &build_query("a.test"),
                &build_response("a.test", &[Ipv4Addr::new(203, 0, 113, 10)], false),
            )
            .expect("first rewrite");
        let first_ip = a_answers(&first.response)[0];

        let second = cache
            .rewrite_response(
                &build_query("b.test"),
                &build_response("b.test", &[Ipv4Addr::new(203, 0, 113, 20)], false),
            )
            .expect("second rewrite");
        let second_ip = a_answers(&second.response)[0];

        let remapped = cache.lookup(u32::from(second_ip)).expect("reverse lookup remains present");
        assert_eq!(first_ip, second_ip);
        assert_eq!(remapped.host, "b.test");
        assert_eq!(Ipv4Addr::from(remapped.real_ip), Ipv4Addr::new(203, 0, 113, 20));
    }
}
