use std::net::Ipv4Addr;

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::rdata::A;
use hickory_proto::rr::{Name, RData, Record, RecordType};

use super::{DnsCache, DnsCacheError};

const NET: u32 = 0xc612_0000;
const MASK: u32 = 0xfffe_0000;

fn exchange(host: &str, addresses: &[Ipv4Addr]) -> (Vec<u8>, Vec<u8>) {
    let name = Name::from_ascii(host).expect("test host");
    let question = Query::new(name.clone(), RecordType::A);
    let mut query = Message::new(1, MessageType::Query, OpCode::Query);
    query.add_query(question.clone());
    let mut response = Message::response(1, OpCode::Query);
    response.add_query(question);
    for address in addresses {
        response.add_answer(Record::from_rdata(name.clone(), 60, RData::A(A(*address))));
    }
    (query.to_vec().expect("query"), response.to_vec().expect("response"))
}

fn answer_ips(bytes: &[u8]) -> Vec<u32> {
    Message::from_vec(bytes)
        .expect("response")
        .answers
        .iter()
        .filter_map(|record| match &record.data {
            RData::A(address) => Some(u32::from(address.0)),
            _ => None,
        })
        .collect()
}

#[test]
fn response_that_exceeds_available_mappings_fails_and_releases_temporary_leases() {
    let mut cache = DnsCache::new(NET, MASK, 2).expect("cache");
    let (active, _) = cache.find("active.test", 0xcb00_7101).expect("active mapping");
    assert!(cache.pin(active));
    let (query, upstream) = exchange("multi.test", &[Ipv4Addr::new(203, 0, 113, 2), Ipv4Addr::new(203, 0, 113, 3)]);

    assert_eq!(cache.rewrite_response(&query, &upstream), Err(DnsCacheError::AllMappingsLeased));

    let (query, upstream) = exchange("after-error.test", &[Ipv4Addr::new(203, 0, 113, 4)]);
    let response = cache.rewrite_response(&query, &upstream).expect("temporary lease released after error");
    assert_eq!(cache.lookup(answer_ips(&response.response)[0]).expect("new mapping").real_ip, 0xcb00_7104);
    assert_eq!(cache.lookup(active).expect("external flow mapping").host, "active.test");
    assert_eq!(cache.lease_count(active), 1);
}

#[test]
fn successful_response_preserves_each_mapping_and_releases_temporary_leases() {
    let mut cache = DnsCache::new(NET, MASK, 2).expect("cache");
    let real_ips = [Ipv4Addr::new(203, 0, 113, 1), Ipv4Addr::new(203, 0, 113, 2)];
    let (query, upstream) = exchange("multi.test", &real_ips);

    let response = cache.rewrite_response(&query, &upstream).expect("two mappings fit");

    let synthetic_ips = answer_ips(&response.response);
    assert_eq!(synthetic_ips.len(), 2);
    assert_ne!(synthetic_ips[0], synthetic_ips[1]);
    for (synthetic, real) in synthetic_ips.into_iter().zip(real_ips) {
        assert_eq!(cache.lookup(synthetic).expect("each answer has a live mapping").real_ip, u32::from(real));
        assert_eq!(cache.lease_count(synthetic), 0);
    }
    let (query, upstream) = exchange("after-success.test", &[Ipv4Addr::new(203, 0, 113, 3)]);
    cache.rewrite_response(&query, &upstream).expect("temporary leases released after success");
}

#[test]
fn repeated_cache_hits_preserve_an_external_flow_lease() {
    let mut cache = DnsCache::new(NET, MASK, 2).expect("cache");
    let first_real = Ipv4Addr::new(203, 0, 113, 1);
    let second_real = Ipv4Addr::new(203, 0, 113, 2);
    let (active, _) = cache.find("multi.test", u32::from(first_real)).expect("active mapping");
    assert!(cache.pin(active));
    let (query, upstream) = exchange("multi.test", &[first_real, first_real, second_real]);

    let response = cache.rewrite_response(&query, &upstream).expect("duplicate records share a mapping");

    let synthetic_ips = answer_ips(&response.response);
    assert_eq!(synthetic_ips.len(), 3);
    assert_eq!(&synthetic_ips[..2], &[active, active]);
    assert_ne!(synthetic_ips[2], active);
    assert_eq!(cache.lookup(synthetic_ips[2]).expect("second mapping").real_ip, u32::from(second_real));
    assert_eq!(cache.lease_count(active), 1);
    let (query, upstream) = exchange("after-success.test", &[Ipv4Addr::new(203, 0, 113, 3)]);
    cache.rewrite_response(&query, &upstream).expect("unleased mapping can be replaced");
    assert_eq!(cache.lookup(active).expect("external flow remains pinned").host, "multi.test");
}
