use super::DnsCache;

const NET: u32 = 0xc612_0000;
const MASK: u32 = 0xfffe_0000;

#[test]
fn default_cache_churn_does_not_reassign_an_issued_address() {
    let mut cache = DnsCache::new(NET, MASK, 10_000).expect("valid cache");
    let (first, _) = cache.find("first.test", 0xcb00_7101).expect("first mapping");
    for index in 1..10_000_u32 {
        cache.find(&format!("other-{index}.test"), 0xcb00_7101 + index).expect("mapping");
    }

    let (replacement, _) = cache.find("replacement.test", 0xcb00_71fe).expect("replacement mapping");

    assert_ne!(first, replacement, "cache eviction must not change an issued address's owner");
    assert!(cache.lookup(first).is_none(), "a retired address must not select the replacement host");
    assert_eq!(cache.lookup(replacement).expect("replacement reverse mapping").host, "replacement.test");
}

#[test]
fn reset_does_not_reissue_addresses_and_preserves_active_leases() {
    let mut cache = DnsCache::new(NET, MASK, 2).expect("valid cache");
    let (retired, _) = cache.find("before-reset.test", 0xcb00_7101).expect("first mapping");
    let (active, _) = cache.find("active.test", 0xcb00_7102).expect("active mapping");
    assert!(cache.pin(active));

    cache.reset_unleased();
    let (fresh, _) = cache.find("after-reset.test", 0xcb00_7103).expect("fresh mapping");

    assert_ne!(fresh, retired, "generation reset must not rewind address issuance");
    assert!(cache.lookup(retired).is_none());
    assert_eq!(cache.lookup(active).expect("active lease survives").host, "active.test");
    assert_eq!(cache.lookup(fresh).expect("fresh mapping").host, "after-reset.test");
}

#[test]
fn address_exhaustion_does_not_wrap_or_remove_the_last_live_mapping() {
    let mut cache = DnsCache::new(0xffff_fffc, 0xffff_fffc, 1).expect("three allocatable addresses");
    let mut issued = Vec::new();
    for index in 0..3 {
        issued.push(cache.find(&format!("host-{index}.test"), 0xcb00_7101 + index).expect("mapping").0);
    }

    let exhausted = cache.find("exhausted.test", 0xcb00_7104);

    assert!(exhausted.is_err(), "all three non-broadcast addresses have already been issued");
    assert_eq!(issued, [0xffff_fffc, 0xffff_fffd, 0xffff_fffe]);
    assert_eq!(cache.lookup(issued[2]).expect("last live mapping survives exhaustion").host, "host-2.test");
    cache.reset_unleased();
    assert!(cache.find("after-reset.test", 0xcb00_7105).is_err(), "reset must not restore exhausted addresses");
}

#[test]
fn rejected_allocation_does_not_consume_the_next_address() {
    let mut cache = DnsCache::new(0xc000_0200, 0xffff_fffc, 1).expect("three allocatable addresses");
    let (first, _) = cache.find("first.test", 0xcb00_7101).expect("first mapping");
    assert!(cache.pin(first));
    assert!(cache.find("second.test", 0xcb00_7102).is_err(), "active mapping cannot be evicted");
    cache.unpin(first);

    let (second, _) = cache.find("second.test", 0xcb00_7102).expect("second mapping");
    let (third, _) = cache.find("third.test", 0xcb00_7103).expect("third mapping");

    assert_eq!((first, second, third), (0xc000_0200, 0xc000_0201, 0xc000_0202));
    assert!(cache.find("fourth.test", 0xcb00_7104).is_err());
}
