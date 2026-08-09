use thiserror::Error;

mod packet;
mod parser;
mod state;

pub use self::packet::DnsRewriteResult;
pub use self::state::DnsCache;

pub(crate) fn servfail_response(query: &[u8]) -> Result<Vec<u8>, DnsCacheError> {
    packet::servfail_response(query)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsCacheEntry {
    pub host: String,
    pub real_ip: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(super) struct DnsCacheKey {
    pub(super) host: String,
    pub(super) real_ip: u32,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum DnsCacheError {
    #[error("cache size must be greater than zero")]
    EmptyCache,
    #[error("cache size {size} exceeds the {available} allocatable addresses in the synthetic network")]
    CapacityExceedsNetwork { size: usize, available: u32 },
    #[error("synthetic netmask is not contiguous")]
    NonContiguousNetmask,
    #[error("synthetic network contains host bits outside the netmask")]
    NetworkHasHostBits,
    #[error("could not reserve storage for {size} DNS cache records")]
    AllocationFailed { size: usize },
    #[error("all synthetic DNS mappings are leased by active flows")]
    AllMappingsLeased,
    #[error("response buffer smaller than request")]
    BufferTooSmall,
    #[error("truncated or malformed DNS packet")]
    Truncated,
    #[error("dns message parse failed: {0}")]
    DnsParse(String),
    #[error("dns message serialization failed: {0}")]
    DnsEncode(String),
}

impl DnsCache {
    pub fn rewrite_response(&mut self, query: &[u8], upstream: &[u8]) -> Result<DnsRewriteResult, DnsCacheError> {
        packet::rewrite_response(self, query, upstream)
    }

    pub fn servfail_response(&self, query: &[u8]) -> Result<Vec<u8>, DnsCacheError> {
        let _ = self;
        servfail_response(query)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
    use hickory_proto::rr::rdata::{A, AAAA};
    use hickory_proto::rr::{Name, RData, Record, RecordType};
    use std::net::{Ipv4Addr, Ipv6Addr};

    const NET: u32 = 0xC612_0000;
    const MASK: u32 = 0xFFFE_0000;

    fn build_query(name: &str) -> Vec<u8> {
        let mut message = Message::new(0x1234, MessageType::Query, OpCode::Query);
        message.metadata.recursion_desired = true;
        message.add_query(Query::new(Name::from_ascii(name).expect("name"), RecordType::A));
        message.to_vec().expect("query encodes")
    }

    fn build_response(name: &str, ips: &[Ipv4Addr], include_aaaa: bool) -> Vec<u8> {
        let mut message = Message::response(0x1234, OpCode::Query);
        message.metadata.recursion_desired = true;
        message.metadata.recursion_available = true;
        message.metadata.response_code = ResponseCode::NoError;
        message.add_query(Query::new(Name::from_ascii(name).expect("name"), RecordType::A));

        for ip in ips {
            message.add_answer(Record::from_rdata(Name::from_ascii(name).expect("name"), 60, RData::A(A(*ip))));
        }

        if include_aaaa {
            message.add_answer(Record::from_rdata(
                Name::from_ascii(name).expect("name"),
                120,
                RData::AAAA(AAAA(Ipv6Addr::LOCALHOST)),
            ));
        }

        message.to_vec().expect("response encodes")
    }

    fn a_answers(packet: &[u8]) -> Vec<Ipv4Addr> {
        let message = Message::from_vec(packet).expect("dns packet parses");
        message
            .answers
            .iter()
            .filter_map(|record| match &record.data {
                RData::A(address) => Some(address.0),
                _ => None,
            })
            .collect()
    }

    fn aaaa_answers(packet: &[u8]) -> Vec<Ipv6Addr> {
        let message = Message::from_vec(packet).expect("dns packet parses");
        message
            .answers
            .iter()
            .filter_map(|record| match &record.data {
                RData::AAAA(address) => Some(address.0),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn rewrite_response_maps_a_records_and_preserves_reverse_lookup() {
        let mut cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
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
        let mut cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", &[Ipv4Addr::new(203, 0, 113, 10)], false);

        let first = cache.rewrite_response(&query, &upstream).expect("first rewrite");
        let second = cache.rewrite_response(&query, &upstream).expect("second rewrite");

        assert_eq!(a_answers(&first.response), a_answers(&second.response));
        assert_eq!(second.cache_hits, 1);
        assert_eq!(second.cache_misses, 0);
    }

    #[test]
    fn mapping_identity_canonicalizes_host_case_and_trailing_dot() {
        let mut cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
        let real_ip = u32::from(Ipv4Addr::new(203, 0, 113, 10));

        let (first, first_hit) = cache.find("One.Example.", real_ip).expect("first mapping");
        let (second, second_hit) = cache.find("one.example", real_ip).expect("canonical mapping");

        assert!(!first_hit);
        assert!(second_hit);
        assert_eq!(first, second);
        assert_eq!(cache.lookup(first).expect("reverse mapping").host, "one.example");
    }

    #[test]
    fn rewritten_synthetic_answers_are_not_cacheable_across_tunnel_sessions() {
        let mut cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", &[Ipv4Addr::new(203, 0, 113, 10)], false);

        let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
        let message = Message::from_vec(&rewritten.response).expect("rewritten response parses");
        let synthetic_answer =
            message.answers.iter().find(|record| matches!(&record.data, RData::A(_))).expect("synthetic A answer");

        assert_eq!(synthetic_answer.ttl, 0);
    }

    #[test]
    fn rewrite_response_strips_aaaa_records() {
        let mut cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", &[Ipv4Addr::new(203, 0, 113, 10)], true);

        let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
        // AAAA records must be stripped so Happy Eyeballs cannot bypass the tunnel.
        assert!(aaaa_answers(&rewritten.response).is_empty(), "AAAA records should be stripped");
        // A records are still rewritten as before.
        let answers = a_answers(&rewritten.response);
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0].octets()[0..2], [198, 18]);
    }

    #[test]
    fn rewrite_response_handles_aaaa_only_response() {
        let mut cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
        let query = build_query("fixture.test");
        // Response with no A records, only AAAA.
        let upstream = build_response("fixture.test", &[], true);

        let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
        assert!(aaaa_answers(&rewritten.response).is_empty(), "AAAA records should be stripped");
        assert!(a_answers(&rewritten.response).is_empty(), "no A records expected");
        // The response should still be a valid DNS message.
        let msg = Message::from_vec(&rewritten.response).expect("valid DNS message");
        assert!(msg.answers.is_empty());
    }

    #[test]
    fn servfail_response_sets_error_and_keeps_question() {
        let cache = DnsCache::new(NET, MASK, 8).expect("valid cache");
        let query = build_query("fixture.test");

        let response = cache.servfail_response(&query).expect("servfail builds");
        let message = Message::from_vec(&response).expect("response parses");
        assert_eq!(message.metadata.response_code, ResponseCode::ServFail);
        assert_eq!(message.queries.len(), 1);
        assert!(message.answers.is_empty());
    }

    #[test]
    fn eviction_removes_stale_reverse_mapping() {
        let mut cache = DnsCache::new(NET, MASK, 1).expect("valid cache");
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

    #[test]
    fn leased_mapping_is_refcounted_and_never_evicted() {
        let mut cache = DnsCache::new(NET, MASK, 1).expect("valid cache");
        let first = cache
            .rewrite_response(
                &build_query("a.test"),
                &build_response("a.test", &[Ipv4Addr::new(203, 0, 113, 10)], false),
            )
            .expect("first rewrite");
        let first_ip = u32::from(a_answers(&first.response)[0]);
        cache.pin(first_ip);
        cache.pin(first_ip);
        cache.unpin(first_ip);

        let second = cache.rewrite_response(
            &build_query("b.test"),
            &build_response("b.test", &[Ipv4Addr::new(203, 0, 113, 20)], false),
        );
        assert!(second.is_err(), "a cache with only leased slots must reject a new mapping");
        assert_eq!(cache.lookup(first_ip).expect("leased reverse mapping must remain").host, "a.test");

        cache.unpin(first_ip);
        cache
            .rewrite_response(
                &build_query("b.test"),
                &build_response("b.test", &[Ipv4Addr::new(203, 0, 113, 20)], false),
            )
            .expect("released slot may be reused");
    }

    #[test]
    fn generation_reset_drops_unleased_mappings_without_overwriting_pinned_holes() {
        let mut cache = DnsCache::new(NET, MASK, 3).expect("valid cache");
        let first = cache
            .rewrite_response(
                &build_query("first.test"),
                &build_response("first.test", &[Ipv4Addr::new(203, 0, 113, 1)], false),
            )
            .expect("first mapping");
        let second = cache
            .rewrite_response(
                &build_query("pinned.test"),
                &build_response("pinned.test", &[Ipv4Addr::new(203, 0, 113, 2)], false),
            )
            .expect("pinned mapping");
        let first_ip = u32::from(a_answers(&first.response)[0]);
        let pinned_ip = u32::from(a_answers(&second.response)[0]);
        assert!(cache.pin(pinned_ip));

        cache.reset_unleased();

        assert!(cache.lookup(first_ip).is_none());
        assert_eq!(cache.lookup(pinned_ip).expect("pinned mapping survives").host, "pinned.test");
        cache
            .rewrite_response(
                &build_query("new.test"),
                &build_response("new.test", &[Ipv4Addr::new(203, 0, 113, 3)], false),
            )
            .expect("empty hole is reusable");
        assert_eq!(cache.lookup(pinned_ip).expect("pinned slot not overwritten").host, "pinned.test");
    }

    #[test]
    fn sparse_large_cache_reset_inspects_only_live_mappings() {
        let mut cache = DnsCache::new(NET, MASK, 65_536).expect("large cache");
        for index in 0..8_u32 {
            cache.find(&format!("sparse-{index}.test"), 0xcb00_7100 + index).expect("mapping");
        }

        let before = cache.reset_inspection_count();
        cache.reset_unleased();

        assert_eq!(cache.reset_inspection_count() - before, 8, "reset cost must scale with live mappings");
    }

    #[test]
    fn reclaimed_large_cache_slot_is_allocated_in_one_step_without_scanning_pinned_prefix() {
        let mut cache = DnsCache::new(NET, MASK, 10_000).expect("default-sized cache");
        let mut pinned = Vec::new();
        for index in 0..128_u32 {
            let (ip, _) = cache.find(&format!("filled-{index}.test"), 0xcb00_7100 + index).expect("mapping");
            if index < 64 {
                assert!(cache.pin(ip));
                pinned.push(ip);
            }
        }
        cache.reset_unleased();
        let before = cache.allocation_step_count();

        cache.find("reclaimed.test", 0xcb00_71ff).expect("reclaimed mapping");

        assert_eq!(cache.allocation_step_count() - before, 1, "reclaimed allocation must be O(1)");
        for ip in pinned {
            assert!(cache.lookup(ip).is_some(), "pinned mapping must survive reset and reclaimed allocation");
        }
    }

    #[test]
    fn construction_rejects_invalid_capacity_without_panicking() {
        assert!(matches!(DnsCache::new(NET, MASK, 0), Err(DnsCacheError::EmptyCache)));
        assert_eq!(
            DnsCache::new(NET, 0xffff_ffff, 1).err(),
            Some(DnsCacheError::CapacityExceedsNetwork { size: 1, available: 0 })
        );
    }
}
