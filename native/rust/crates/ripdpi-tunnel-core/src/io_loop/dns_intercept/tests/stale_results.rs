use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::RawFd;
use std::sync::{Arc, PoisonError};

use hickory_proto::op::{Message, ResponseCode};
use ripdpi_dns_resolver::EncryptedDnsExchangeSuccess;

use crate::direct_dns_binding::{DirectDnsSocketBinder, TEST_BINDER_LOCK, register_test_direct_dns_socket_binder};
use crate::{IpClass, Stats, TunDevice};

use super::super::{DnsResponse, handle_dns_response};
use super::support::{build_query, build_response, test_dns_cache, test_mapdns};

struct FixedLease(Option<u64>);

impl DirectDnsSocketBinder for FixedLease {
    fn current_generation(&self) -> Option<u64> {
        self.0
    }

    fn is_current(&self, generation: u64) -> bool {
        self.0 == Some(generation)
    }

    fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
        if self.is_current(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
    }
}

#[test]
fn stale_direct_results_preserve_new_encrypted_mappings() {
    let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    for current in [None, Some(12)] {
        for stale_success in [false, true] {
            let _registration = register_test_direct_dns_socket_binder(Arc::new(FixedLease(current)));
            let mut cache = test_dns_cache();
            let mut device = TunDevice::new(1500);
            let stats = Arc::new(Stats::default());
            let mut active_generation = current;
            let src: SocketAddr = "10.0.0.2:53000".parse().expect("source");
            let fresh_ip = Ipv4Addr::new(203, 0, 113, 7);
            handle_dns_response(
                &mut device,
                &stats,
                test_mapdns(),
                &mut cache,
                &mut active_generation,
                response(src, "fresh.test", None, Ok(build_response("fresh.test", fresh_ip))),
            );
            let synthetic_ip = u32::from(Ipv4Addr::new(198, 18, 0, 0));
            assert_eq!(cache.lookup(synthetic_ip).expect("fresh mapping").real_ip, u32::from(fresh_ip));
            let upstream = if stale_success {
                Ok(build_response("stale.test", Ipv4Addr::new(203, 0, 113, 9)))
            } else {
                Err("old direct exchange failed".to_string())
            };
            handle_dns_response(
                &mut device,
                &stats,
                test_mapdns(),
                &mut cache,
                &mut active_generation,
                response(src, "stale.test", Some(11), upstream),
            );

            let mapping = cache.lookup(synthetic_ip).expect("stale result must retain the delivered mapping");
            assert_eq!(mapping.host, "fresh.test");
            assert_eq!(mapping.real_ip, u32::from(fresh_ip));
            assert_eq!(active_generation, current);
            assert_eq!(device.tx_queue.len(), 2, "fresh reply and stale SERVFAIL must both be delivered");
            device.tx_queue.pop_front().expect("fresh reply");
            let packet = device.tx_queue.pop_front().expect("stale reply");
            let IpClass::Udp { payload, .. } = crate::classify_ip_packet(&packet, None) else {
                panic!("expected UDP DNS response");
            };
            let failure = Message::from_vec(payload).expect("valid DNS failure");
            assert_eq!(failure.metadata.response_code, ResponseCode::ServFail);
            assert_eq!(failure.queries[0].name.to_ascii(), "stale.test.");
            assert!(failure.answers.is_empty());
        }
    }
}

fn response(src: SocketAddr, host: &str, generation: Option<u64>, upstream: Result<Vec<u8>, String>) -> DnsResponse {
    DnsResponse {
        src,
        query: build_query(host),
        host: Some(host.to_string()),
        upstream: upstream.map(|response_bytes| EncryptedDnsExchangeSuccess {
            response_bytes,
            endpoint_label: "fixture".to_string(),
            latency_ms: 0,
        }),
        resolver_error_kind: None,
        resolver_endpoint_label: None,
        direct_generation: generation,
        tcp_reply: None,
    }
}
