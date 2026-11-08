use std::collections::HashMap;
use std::sync::Arc;

use crate::session::TargetAddr;

use super::super::association_state::{UDP_DESTINATION_CAPACITY, UdpDestination};

fn canonical_target(target: &TargetAddr) -> TargetAddr {
    match target {
        TargetAddr::Ip(addr) => TargetAddr::Ip(*addr),
        TargetAddr::Domain(host, port) => TargetAddr::Domain(host.trim_end_matches('.').to_ascii_lowercase(), *port),
        TargetAddr::ResolvedDomain(host, pinned) => {
            TargetAddr::ResolvedDomain(host.trim_end_matches('.').to_ascii_lowercase(), *pinned)
        }
    }
}

pub(super) fn remember_destination(
    destinations: &mut HashMap<TargetAddr, Arc<UdpDestination>>,
    destination: &Arc<UdpDestination>,
) -> bool {
    if !destination.is_live() {
        return false;
    }
    let target = canonical_target(&destination.target);
    if let Some(current) = destinations.get(&target)
        && current.is_live()
        && (current.response_source != destination.response_source || current.synthetic_ip != destination.synthetic_ip)
    {
        return false;
    }
    if destinations.len() >= UDP_DESTINATION_CAPACITY && !destinations.contains_key(&target) {
        destinations.retain(|_, current| current.is_live());
        if destinations.len() >= UDP_DESTINATION_CAPACITY && !destinations.contains_key(&target) {
            return false;
        }
    }
    destinations.insert(target, Arc::clone(destination));
    true
}

pub(super) fn destination_for_target(
    destinations: &HashMap<TargetAddr, Arc<UdpDestination>>,
    target: TargetAddr,
) -> Option<Arc<UdpDestination>> {
    let target = canonical_target(&target);
    if let Some(destination) = destinations.get(&target).filter(|destination| destination.is_live()) {
        return Some(Arc::clone(destination));
    }
    let TargetAddr::Domain(host, port) = target else {
        return None;
    };
    let mut matched: Option<&Arc<UdpDestination>> = None;
    for (known, candidate) in destinations {
        if let TargetAddr::ResolvedDomain(known_host, pinned) = known
            && *known_host == host
            && pinned.port() == port
            && candidate.is_live()
        {
            if matched.is_some_and(|current| current.response_source != candidate.response_source) {
                return None;
            }
            matched = Some(candidate);
        }
    }
    matched.cloned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;

    fn remember_response_source(
        destinations: &mut HashMap<TargetAddr, Arc<UdpDestination>>,
        host: &str,
        pinned: SocketAddr,
        source: SocketAddr,
    ) -> bool {
        remember_destination(
            destinations,
            &Arc::new(UdpDestination::new(TargetAddr::ResolvedDomain(host.to_owned(), pinned), pinned, source, None)),
        )
    }

    fn response_source_for_target(
        destinations: &HashMap<TargetAddr, Arc<UdpDestination>>,
        target: TargetAddr,
    ) -> Option<SocketAddr> {
        destination_for_target(destinations, target).map(|destination| destination.response_source)
    }

    #[test]
    fn numeric_reply_requires_a_live_admitted_destination() {
        let known: SocketAddr = "203.0.113.1:443".parse().unwrap();
        let unknown: SocketAddr = "203.0.113.2:443".parse().unwrap();
        let destination = Arc::new(UdpDestination::new(TargetAddr::Ip(known), known, known, None));
        let mut destinations = HashMap::new();
        assert!(remember_destination(&mut destinations, &destination));
        assert_eq!(response_source_for_target(&destinations, TargetAddr::Ip(known)), Some(known));
        assert_eq!(response_source_for_target(&destinations, TargetAddr::Ip(unknown)), None);
        destination.retire();
        assert_eq!(response_source_for_target(&destinations, TargetAddr::Ip(known)), None);
        assert!(!remember_destination(&mut destinations, &destination), "retired identities cannot be revived");
    }

    #[test]
    fn domain_responses_are_attributed_by_host_not_arrival_order() {
        let first: SocketAddr = "198.18.0.1:1001".parse().unwrap();
        let second: SocketAddr = "198.18.0.2:1002".parse().unwrap();
        let first_pinned: SocketAddr = "203.0.113.7:443".parse().unwrap();
        let second_pinned: SocketAddr = "203.0.113.7:53".parse().unwrap();
        let mut sources = HashMap::new();
        remember_response_source(&mut sources, "one.example", first_pinned, first);
        remember_response_source(&mut sources, "two.example", second_pinned, second);

        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("two.example".into(), second_pinned)),
            Some(second)
        );
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("one.example".into(), first_pinned)),
            Some(first)
        );
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("unknown.example".into(), first_pinned)),
            None
        );
    }

    #[test]
    fn resolved_responses_distinguish_multi_a_records_and_canonicalize_host_case() {
        let first_pinned: SocketAddr = "203.0.113.10:443".parse().unwrap();
        let second_pinned: SocketAddr = "203.0.113.11:443".parse().unwrap();
        let first_source: SocketAddr = "198.18.0.10:443".parse().unwrap();
        let second_source: SocketAddr = "198.18.0.11:443".parse().unwrap();
        let mut sources = HashMap::new();
        remember_response_source(&mut sources, "Multi.Example.", first_pinned, first_source);
        remember_response_source(&mut sources, "MULTI.EXAMPLE", second_pinned, second_source);

        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("multi.example".into(), second_pinned)),
            Some(second_source)
        );
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::ResolvedDomain("MULTI.EXAMPLE.".into(), first_pinned)),
            Some(first_source)
        );
    }

    #[test]
    fn ambiguous_domain_response_has_no_source() {
        let mut sources = HashMap::new();
        let first_pinned: SocketAddr = "203.0.113.10:443".parse().unwrap();
        let second_pinned: SocketAddr = "203.0.113.11:443".parse().unwrap();
        let first_source: SocketAddr = "198.18.0.10:443".parse().unwrap();
        let second_source: SocketAddr = "198.18.0.11:443".parse().unwrap();
        remember_response_source(&mut sources, "multi.example", first_pinned, first_source);
        assert_eq!(
            response_source_for_target(&sources, TargetAddr::Domain("multi.example".into(), 443)),
            Some(first_source)
        );
        remember_response_source(&mut sources, "multi.example", second_pinned, second_source);
        assert_eq!(response_source_for_target(&sources, TargetAddr::Domain("multi.example".into(), 443)), None);
    }

    #[test]
    fn response_source_table_remains_bounded() {
        let mut sources = HashMap::new();
        for index in 0..(UDP_DESTINATION_CAPACITY + 32) {
            let source: SocketAddr = format!("198.18.0.1:{}", index + 1).parse().unwrap();
            let pinned: SocketAddr = format!("203.0.113.1:{}", index + 1).parse().unwrap();
            remember_response_source(&mut sources, &format!("host-{index}.example"), pinned, source);
        }

        assert_eq!(sources.len(), UDP_DESTINATION_CAPACITY);
    }

    #[test]
    fn full_response_table_preserves_admitted_sources() {
        let mut sources = HashMap::new();
        for index in 0..=UDP_DESTINATION_CAPACITY {
            let source = SocketAddr::new("198.18.0.1".parse().unwrap(), index as u16 + 1);
            let pinned = SocketAddr::new("203.0.113.1".parse().unwrap(), index as u16 + 1);
            remember_response_source(&mut sources, &format!("host-{index}.example"), pinned, source);
        }

        for index in 0..UDP_DESTINATION_CAPACITY {
            let pinned = SocketAddr::new("203.0.113.1".parse().unwrap(), index as u16 + 1);
            let source = SocketAddr::new("198.18.0.1".parse().unwrap(), index as u16 + 1);
            assert_eq!(
                response_source_for_target(
                    &sources,
                    TargetAddr::ResolvedDomain(format!("host-{index}.example"), pinned)
                ),
                Some(source),
                "admitting a new target must not evict an admitted response identity"
            );
        }
        let excess = SocketAddr::new("203.0.113.1".parse().unwrap(), UDP_DESTINATION_CAPACITY as u16 + 1);
        assert_eq!(
            response_source_for_target(
                &sources,
                TargetAddr::ResolvedDomain(format!("host-{UDP_DESTINATION_CAPACITY}.example"), excess)
            ),
            None,
            "the excess target must not displace a live mapping"
        );
    }
}
