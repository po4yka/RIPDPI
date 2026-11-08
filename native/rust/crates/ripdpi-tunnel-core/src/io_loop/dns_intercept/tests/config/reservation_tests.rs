use std::net::Ipv4Addr;

use crate::dns_cache::DnsCache;

use super::{mapdns_config, parse_dns_cache, tunnel_config_with_mapdns};

const NET: u32 = 0xc612_0000;
const MASK: u32 = 0xfffe_0000;
const TINY_NET: u32 = 0xc000_0200;

fn tiny_config(listener: &str, capacity: u32) -> ripdpi_tunnel_config::Config {
    let mut mapdns = mapdns_config(capacity);
    mapdns.address = listener.to_string();
    mapdns.network = Some("192.0.2.0".to_string());
    mapdns.netmask = Some("255.255.255.252".to_string());
    tunnel_config_with_mapdns(Some(mapdns))
}

#[test]
fn default_listener_is_excluded_from_synthetic_answers() {
    let mut mapdns = mapdns_config(64);
    mapdns.address = "198.18.0.53".to_string();
    let config = tunnel_config_with_mapdns(Some(mapdns));
    let mut cache = parse_dns_cache(&config, None).expect("valid config").expect("DNS cache");

    let mut issued = Vec::new();
    for index in 0..54 {
        let (ip, _) = cache.find(&format!("host-{index}.test"), 0xcb00_7101 + index).expect("mapping");
        assert_ne!(Ipv4Addr::from(ip), Ipv4Addr::new(198, 18, 0, 53), "the DNS listener has its own owner");
        issued.push(ip);
    }
    assert_eq!(issued[52], NET + 52);
    assert_eq!(issued[53], NET + 54);
}

#[test]
fn custom_listener_is_skipped_and_reservation_is_idempotent() {
    for (listener, expected) in [
        ("192.0.2.0", (TINY_NET + 1, TINY_NET + 2)),
        ("192.0.2.1", (TINY_NET, TINY_NET + 2)),
        ("192.0.2.2", (TINY_NET, TINY_NET + 1)),
    ] {
        let config = tiny_config(listener, 2);
        let mut cache = parse_dns_cache(&config, None).expect("valid config").expect("DNS cache");
        let first = cache.find("first.test", 0xcb00_7101).expect("first mapping").0;
        let second = cache.find("second.test", 0xcb00_7102).expect("second mapping").0;

        let mut cache =
            parse_dns_cache(&config, Some(cache)).expect("same reservation remains valid").expect("DNS cache");

        assert_eq!((first, second), expected);
        assert!(cache.find("third.test", 0xcb00_7103).is_err(), "only two non-listener addresses exist");
        assert_eq!(cache.lookup(first).expect("first mapping remains").host, "first.test");
        assert_eq!(cache.lookup(second).expect("second mapping remains").host, "second.test");
    }
}

#[test]
fn reservation_rejects_a_capacity_that_includes_the_listener() {
    for (listener, mask, capacity) in [("192.0.2.1", "255.255.255.252", 3), ("192.0.2.0", "255.255.255.254", 1)] {
        let mut config = tiny_config(listener, capacity);
        config.mapdns.as_mut().expect("mapdns").netmask = Some(mask.to_string());

        let error = parse_dns_cache(&config, None).err().expect("listener needs one of the allocatable addresses");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
    }
}

#[test]
fn listeners_outside_the_pool_or_at_broadcast_do_not_reduce_capacity() {
    for listener in ["198.51.100.53", "192.0.2.3"] {
        let config = tiny_config(listener, 3);
        let mut cache = parse_dns_cache(&config, None).expect("listener needs no reservation").expect("DNS cache");
        for index in 0..3 {
            let (ip, _) = cache.find(&format!("host-{index}.test"), 0xcb00_7101 + index).expect("mapping");
            assert_eq!(ip, TINY_NET + index);
        }
    }
}

#[test]
fn supplied_cache_cannot_reserve_an_address_that_was_already_issued() {
    let mut mapdns = mapdns_config(1);
    mapdns.address = "198.18.0.0".to_string();
    let config = tunnel_config_with_mapdns(Some(mapdns));
    for state in 0..3 {
        let mut cache = DnsCache::new(NET, MASK, 1).expect("valid cache");
        cache.find("first.test", 0xcb00_7101).expect("listener address was issued");
        if state > 0 {
            cache.find("second.test", 0xcb00_7102).expect("old mapping is evicted");
        }
        if state > 1 {
            cache.reset_unleased();
        }

        let error = parse_dns_cache(&config, Some(cache)).err().expect("issued address cannot become a listener");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
    }
}

#[test]
fn supplied_cache_cannot_release_its_original_listener_reservation() {
    let first_config = tiny_config("192.0.2.1", 1);
    let cache = parse_dns_cache(&first_config, None).expect("first reservation").expect("DNS cache");
    let changed_config = tiny_config("192.0.2.2", 1);

    let error = parse_dns_cache(&changed_config, Some(cache)).err().expect("reservation cannot change in one session");

    assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
}

#[test]
fn leased_allocation_failure_does_not_consume_an_address_after_the_listener() {
    let config = tiny_config("192.0.2.1", 1);
    let mut cache = parse_dns_cache(&config, None).expect("valid config").expect("DNS cache");
    let first = cache.find("first.test", 0xcb00_7101).expect("first mapping").0;
    assert!(cache.pin(first));
    assert!(cache.find("second.test", 0xcb00_7102).is_err());
    cache.unpin(first);

    let second = cache.find("second.test", 0xcb00_7102).expect("last available mapping").0;

    assert_eq!(second, TINY_NET + 2);
    assert!(cache.find("third.test", 0xcb00_7103).is_err());
}
