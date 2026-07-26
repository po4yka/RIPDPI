use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use hickory_proto::op::Message;
use hickory_proto::rr::RData;

use crate::Stats;
use crate::dns_cache::DnsCache;

use super::super::{parse_mapdns_runtime, resolve_mapped_target};
use super::support::{build_query, build_response, mapdns_config, test_mapdns, tunnel_config_with_mapdns};

#[test]
fn normalized_default_mapdns_network_preserves_reverse_lookup() {
    let config = tunnel_config_with_mapdns(Some(mapdns_config(8)));
    let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");
    let mut cache = DnsCache::new(runtime.synthetic_net, runtime.synthetic_mask, 8).expect("valid cache");
    let query = build_query("fixture.test");
    let upstream = build_response("fixture.test", Ipv4Addr::new(203, 0, 113, 10));
    let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
    let message = Message::from_vec(&rewritten.response).expect("rewritten response parses");
    let synthetic_ip = message
        .answers
        .iter()
        .find_map(|record| match &record.data {
            RData::A(address) => Some(address.0),
            _ => None,
        })
        .expect("rewritten ipv4 answer");
    let stats = Arc::new(Stats::default());
    let resolved =
        resolve_mapped_target(&stats, &mut Some(cache), None, SocketAddr::new(IpAddr::V4(synthetic_ip), 443));

    assert_eq!(resolved, Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)));
}

#[test]
fn resolve_mapped_target_returns_none_for_unmapped_synthetic_ip() {
    let mapdns = test_mapdns();
    let cache = DnsCache::new(mapdns.synthetic_net, mapdns.synthetic_mask, 8).expect("valid cache");
    let stats = Arc::new(Stats::default());
    let synthetic_dns = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 53)), 853);
    let resolved = resolve_mapped_target(&stats, &mut Some(cache), None, synthetic_dns);

    assert_eq!(resolved, None);
}
