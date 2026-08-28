use std::collections::HashMap;
use std::net::SocketAddr;

use crate::dns_cache::DnsCache;

use super::super::association_state::UdpAssociation;

pub(super) fn lease_udp_attribution(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    src: SocketAddr,
    registration_id: ripdpi_flow_app_attribution::FlowRegistrationId,
) {
    if let Some(association) = associations.get_mut(&src) {
        let request = registration_id.request();
        // Repeated packets may carry the same identity. Refresh its LRU position
        // without evicting the registration still owned by this association.
        if association.attribution_ids.get(&request) == Some(&registration_id) {
            return;
        }
        if let Some((_request, removed)) = association.attribution_ids.push(request, registration_id) {
            let _ = ripdpi_flow_app_attribution::evict_flow_if_current(removed);
        }
    }
}

pub(in crate::io_loop::udp_assoc) fn lease_udp_mapping(
    associations: &mut HashMap<SocketAddr, UdpAssociation>,
    dns_cache: &mut Option<DnsCache>,
    src: SocketAddr,
    synthetic_ip: Option<u32>,
) {
    let (Some(association), Some(cache), Some(ip)) = (associations.get_mut(&src), dns_cache.as_mut(), synthetic_ip)
    else {
        return;
    };
    if association.leased_synthetic_ips.insert(ip) && !cache.pin(ip) {
        association.leased_synthetic_ips.remove(&ip);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // cancel-safe: the only spawned task is aborted and joined before asserting the ownership bound.
    #[tokio::test]
    async fn udp_attribution_ownership_stays_bounded_after_tuple_and_generation_churn() {
        let src: SocketAddr = "192.0.2.221:53191".parse().expect("source");
        let association = test_association();
        let mut associations = HashMap::from([(src, association)]);
        for port in 40_000..40_256 {
            let dst = SocketAddr::new("198.51.100.221".parse().expect("destination"), port);
            for _ in 0..2 {
                let observation = ripdpi_flow_app_attribution::note_flow(17, src, dst);
                lease_udp_attribution(&mut associations, src, observation.registration_id);
                // Simulate expiry/LRU removal while the multiplexed association remains live.
                let cleanup = ripdpi_flow_app_attribution::note_flow(17, src, dst);
                assert!(ripdpi_flow_app_attribution::evict_flow_if_current(cleanup.registration_id));
            }
        }
        let retained = finish_association(&mut associations, src).await;
        assert!(retained <= 64, "one UDP association retained {retained} stale attribution tokens");
    }

    // cancel-safe: the sole worker is aborted and joined, and owned registrations are released.
    #[tokio::test]
    async fn udp_attribution_replaces_stale_generation_without_evicting_current_refresh() {
        let src: SocketAddr = "192.0.2.222:53192".parse().expect("source");
        let dst: SocketAddr = "198.51.100.222:40000".parse().expect("destination");
        let mut associations = HashMap::from([(src, test_association())]);
        lease_udp_attribution(
            &mut associations,
            src,
            ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id,
        );
        assert!(ripdpi_flow_app_attribution::evict_flow_if_current(
            ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id
        ));
        lease_udp_attribution(
            &mut associations,
            src,
            ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id,
        );
        lease_udp_attribution(
            &mut associations,
            src,
            ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id,
        );
        let current = ripdpi_flow_app_attribution::lookup_flow_uid(17, src, dst);
        let retained = finish_association(&mut associations, src).await;
        assert_eq!(retained, 1, "one tuple owns only its latest generation");
        assert_eq!(current, ripdpi_flow_app_attribution::FlowUidLookup::Pending);
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(17, src, dst),
            ripdpi_flow_app_attribution::FlowUidLookup::Missing
        );
    }

    // cancel-safe: the sole worker is aborted and joined, and owned registrations are released.
    #[tokio::test]
    async fn udp_attribution_capacity_evicts_oldest_registration_but_preserves_refreshed_tuple() {
        let src: SocketAddr = "192.0.2.223:53193".parse().expect("source");
        let mut associations = HashMap::from([(src, test_association())]);
        let destination = |port| SocketAddr::new("198.51.100.223".parse().expect("destination"), port);
        for port in 40_000..40_064 {
            lease_udp_attribution(
                &mut associations,
                src,
                ripdpi_flow_app_attribution::note_flow(17, src, destination(port)).registration_id,
            );
        }
        lease_udp_attribution(
            &mut associations,
            src,
            ripdpi_flow_app_attribution::note_flow(17, src, destination(40_000)).registration_id,
        );
        lease_udp_attribution(
            &mut associations,
            src,
            ripdpi_flow_app_attribution::note_flow(17, src, destination(40_064)).registration_id,
        );
        let oldest = ripdpi_flow_app_attribution::lookup_flow_uid(17, src, destination(40_001));
        let refreshed = ripdpi_flow_app_attribution::lookup_flow_uid(17, src, destination(40_000));
        let newest = ripdpi_flow_app_attribution::lookup_flow_uid(17, src, destination(40_064));
        let retained = finish_association(&mut associations, src).await;
        assert_eq!(retained, 64);
        assert_eq!(oldest, ripdpi_flow_app_attribution::FlowUidLookup::Missing);
        assert_eq!(refreshed, ripdpi_flow_app_attribution::FlowUidLookup::Pending);
        assert_eq!(newest, ripdpi_flow_app_attribution::FlowUidLookup::Pending);
    }

    fn test_association() -> UdpAssociation {
        let (outbound, _receiver) = tokio::sync::mpsc::channel(1);
        UdpAssociation {
            id: 1,
            activity_generation: 0,
            outbound,
            cancel: tokio_util::sync::CancellationToken::new(),
            last_activity: std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0)),
            worker: tokio::spawn(std::future::pending()),
            leased_synthetic_ips: std::collections::HashSet::new(),
            attribution_ids: lru::LruCache::new(
                crate::io_loop::udp_assoc::association_state::UDP_ATTRIBUTION_ID_CAPACITY,
            ),
        }
    }

    /// # Cancel safety
    /// The worker is aborted before awaiting its join; tokens are synchronously released first.
    async fn finish_association(associations: &mut HashMap<SocketAddr, UdpAssociation>, src: SocketAddr) -> usize {
        let association = associations.remove(&src).expect("association remains live");
        let retained = association.attribution_ids.len();
        for (_, registration_id) in association.attribution_ids {
            let _ = ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
        }
        association.worker.abort();
        assert!(association.worker.await.expect_err("worker aborted").is_cancelled());
        retained
    }
}
