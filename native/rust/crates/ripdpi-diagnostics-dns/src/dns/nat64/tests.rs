use super::*;
use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;
use std::time::Duration;

#[test]
fn rfc6052_all_prefix_lengths_roundtrip_and_synthesize_reference() {
    for length in [32, 40, 48, 56, 64, 96] {
        let prefix = Nat64Prefix { address: "2001:db8::".parse().unwrap(), length };
        let sentinels: Vec<_> = [170, 171]
            .into_iter()
            .map(|last| prefix.synthesize(Ipv4Addr::new(192, 0, 0, last)).unwrap().to_string())
            .collect();
        assert_eq!(discover_nat64_prefixes(&sentinels), Ok(vec![prefix]), "length {length}");
        let target = prefix.synthesize(Ipv4Addr::new(1, 1, 1, 1)).unwrap();
        assert!(!sentinels.contains(&target.to_string()));
        if length != 96 {
            assert_eq!(target.octets()[8], 0);
        }
    }
    let prefix = Nat64Prefix { address: "64:ff9b::".parse().unwrap(), length: 96 };
    assert_eq!(prefix.synthesize(Ipv4Addr::new(1, 1, 1, 1)).unwrap().to_string(), "64:ff9b::101:101");
}
#[test]
fn malformed_reserved_octet_suffix_non_sentinel_and_ambiguous_prefix_rejected() {
    let prefix = Nat64Prefix { address: "2001:db8::".parse().unwrap(), length: 32 };
    let valid = prefix.synthesize(Ipv4Addr::new(192, 0, 0, 170)).unwrap();
    for position in [8, 15] {
        let mut bytes = valid.octets();
        bytes[position] = 1;
        assert!(discover_nat64_prefixes(&[Ipv6Addr::from(bytes).to_string()]).is_err());
    }
    assert!(discover_nat64_prefixes(&["2001:db8::1".into()]).is_err());
    assert!(discover_nat64_prefixes(&["192.0.0.170".into()]).is_err());
    let other = Nat64Prefix { address: "64:ff9b::".parse().unwrap(), length: 96 }
        .synthesize(Ipv4Addr::new(192, 0, 0, 171))
        .unwrap();
    assert_eq!(discover_nat64_prefixes(&[valid.to_string(), other.to_string()]).unwrap().len(), 2);
    assert!(discover_nat64_prefixes(&vec![valid.to_string(); 17]).is_err());
    assert_eq!(discover_nat64_prefixes(&[]), Ok(vec![]));
}
#[test]
fn discovery_uses_only_snapshot_literals_and_preserves_query_binding() {
    let cancel = AtomicBool::new(false);
    let mut peers = Vec::new();
    let result = discover_with(&["1.2.3.4".into(), "dns.example".into()], &cancel, |peer, query| {
        peers.push(peer);
        let mut response = query.to_vec();
        response[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
        response[6..8].copy_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&[0xc0, 0x0c, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16]);
        response.extend_from_slice(&"64:ff9b::c000:aa".parse::<Ipv6Addr>().unwrap().octets());
        Ok(response)
    });
    assert_eq!(peers, ["1.2.3.4:53".parse::<SocketAddr>().unwrap()]);
    assert_eq!(result.status, "DISCOVERED");
    let invalid = discover_with(&["1.2.3.4".into()], &cancel, |_, query| {
        let mut response = query.to_vec();
        response[0] ^= 1;
        Ok(response)
    });
    assert_eq!(invalid.status, "INVALID");
}
#[test]
fn no_resolver_cancel_and_deadline_do_not_send_requests() {
    let missing = discover_network_nat64(&[], &AtomicBool::new(false));
    assert_eq!(missing.reason, Some("no_network_dns"));
    assert_eq!(missing.attempts, 0);
    let stopped = discover_with(&["1.1.1.1".into()], &AtomicBool::new(true), |_, _| panic!("cancelled query"));
    assert_eq!(stopped.reason, Some("cancelled"));
    with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
        let result = discover_with(&["1.1.1.1".into()], &AtomicBool::new(false), |_, _| panic!("expired query"));
        assert_eq!(result.reason, Some("deadline_exceeded"));
    });
}

#[test]
fn repeated_discovery_sentinel_inside_prefix_is_ambiguous() {
    let prefix = Nat64Prefix { address: "c000:aa::".parse().unwrap(), length: 32 };
    let answer = prefix.synthesize(Ipv4Addr::new(192, 0, 0, 170)).unwrap();
    assert_eq!(discover_nat64_prefixes(&[answer.to_string()]), Err("ambiguous_prefix"));
}

#[test]
fn usable_primary_does_not_query_a_silent_secondary() {
    let mut calls = 0;
    let result = discover_with(&["1.2.3.4".into(), "5.6.7.8".into()], &AtomicBool::new(false), |_, query| {
        calls += 1;
        assert_eq!(calls, 1, "a valid primary is sufficient");
        let mut response = query.to_vec();
        response[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
        response[6..8].copy_from_slice(&1u16.to_be_bytes());
        response.extend_from_slice(&[0xc0, 0x0c, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16]);
        response.extend_from_slice(&"64:ff9b::c000:aa".parse::<Ipv6Addr>().unwrap().octets());
        Ok(response)
    });
    assert_eq!(result.status, "DISCOVERED");
    assert_eq!(result.attempts, 1);
}

#[test]
fn ipv4_mapped_answers_cannot_authorize_nat64_transport() {
    assert_eq!(discover_nat64_prefixes(&["::ffff:c000:aa".into()]), Err("invalid_dns64_response"));
}

#[test]
fn ambiguous_first_sentinel_can_use_the_other_sentinel() {
    let prefix = Nat64Prefix { address: "c000:aa::".parse().unwrap(), length: 96 };
    let answers = [
        prefix.synthesize(Ipv4Addr::new(192, 0, 0, 170)).unwrap().to_string(),
        prefix.synthesize(Ipv4Addr::new(192, 0, 0, 171)).unwrap().to_string(),
    ];
    assert_eq!(discover_nat64_prefixes(&answers), Ok(vec![prefix]));
}
#[test]
fn multi_prefix_order_is_preserved_and_limit_is_enforced() {
    let answers: Vec<_> = (1..=5)
        .rev()
        .map(|n| {
            Nat64Prefix { address: format!("2001:db8:{n}::").parse().unwrap(), length: 96 }
                .synthesize(Ipv4Addr::new(192, 0, 0, 170))
                .unwrap()
                .to_string()
        })
        .collect();
    let prefixes = discover_nat64_prefixes(&answers[..4]).unwrap();
    assert_eq!(prefixes[0].address.to_string(), "2001:db8:5::");
    assert_eq!(prefixes[3].address.to_string(), "2001:db8:2::");
    assert_eq!(discover_nat64_prefixes(&answers), Err("invalid_dns64_response"));
}
