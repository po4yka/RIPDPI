use super::super::association_state::UdpDestination;
use crate::io_loop::packet::UdpErrorQuote;
use crate::session::udp::UdpMemoryBudget;
use ripdpi_flow_app_attribution::FlowRegistrationId;
use ripdpi_socks5_core::udp_feedback::RemoteError;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::time::Instant;

pub(in crate::io_loop::udp_assoc) struct RemoteErrorContext {
    pub(in crate::io_loop::udp_assoc) registration_id: FlowRegistrationId,
    pub(in crate::io_loop::udp_assoc) destination: Arc<UdpDestination>,
    quote: UdpErrorQuote,
    _bytes: tokio::sync::OwnedSemaphorePermit,
}

impl RemoteErrorContext {
    pub(in crate::io_loop::udp_assoc) fn capture(
        packet: &[u8],
        registration_id: FlowRegistrationId,
        destination: Arc<UdpDestination>,
        budget: &UdpMemoryBudget,
    ) -> Option<Self> {
        let quote = UdpErrorQuote::capture(packet)?;
        let bytes = budget.try_reserve_queued_bytes(quote.len())?;
        Some(Self { registration_id, destination, quote, _bytes: bytes })
    }
}

#[derive(Default)]
pub(super) struct PendingErrors(HashMap<u64, (Instant, RemoteErrorContext)>);

impl PendingErrors {
    pub(super) fn next_expiry(&self) -> Option<Instant> {
        self.0.values().map(|(time, _)| *time + Duration::from_secs(5)).min()
    }

    pub(super) fn expire(&mut self) {
        self.0.retain(|_, (time, _)| time.elapsed() < Duration::from_secs(5));
    }

    pub(super) fn remember(&mut self, id: u64, context: Option<RemoteErrorContext>) {
        self.expire();
        if self.0.len() < 64
            && let Some(context) = context
        {
            self.0.insert(id, (Instant::now(), context));
        }
    }

    pub(super) fn take(
        &mut self,
        id: u64,
        error: &RemoteError,
    ) -> Option<(FlowRegistrationId, Arc<UdpDestination>, Vec<u8>)> {
        let (time, context) = self.0.remove(&id)?;
        if time.elapsed() >= Duration::from_secs(5)
            || !context.destination.is_live()
            || context.destination.resolved_dest != error.target
        {
            return None;
        }
        context.quote.build_remote(error).map(|raw| (context.registration_id, context.destination, raw))
    }

    pub(super) fn clear(&mut self) {
        self.0.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(super) fn context(budget: &UdpMemoryBudget) -> RemoteErrorContext {
        let mut packet = Vec::new();
        etherparse::PacketBuilder::ipv4([10, 0, 0, 2], [198, 18, 0, 1], 37)
            .udp(53212, 443)
            .write(&mut packet, b"original")
            .unwrap();
        let registration = ripdpi_flow_app_attribution::note_flow(
            crate::uid_policy::PROTO_UDP,
            "10.0.0.2:53212".parse().unwrap(),
            "198.18.0.1:443".parse().unwrap(),
        )
        .registration_id;
        RemoteErrorContext::capture(
            &packet,
            registration,
            Arc::new(UdpDestination::new(
                crate::session::TargetAddr::Ip("203.0.113.9:443".parse().unwrap()),
                "203.0.113.9:443".parse().unwrap(),
                "198.18.0.1:443".parse().unwrap(),
                None,
            )),
            budget,
        )
        .unwrap()
    }

    fn error() -> RemoteError {
        RemoteError {
            source: "192.0.2.1".parse().unwrap(),
            target: "203.0.113.9:443".parse().unwrap(),
            icmp_type: 3,
            icmp_code: 3,
            info: 0,
        }
    }

    #[test]
    fn errors_require_matching_ticket_target_and_preserve_mapdns_quote() {
        let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
        let mut pending = PendingErrors::default();
        pending.remember(1, Some(context(&budget)));
        assert!(pending.take(2, &error()).is_none());
        let (_, _, raw) = pending.take(1, &error()).unwrap();
        assert_eq!(&raw[28 + 16..28 + 20], &[198, 18, 0, 1], "quote retains app-visible MapDNS destination");
        assert!(pending.take(1, &error()).is_none(), "no duplicate feedback");
        pending.remember(3, Some(context(&budget)));
        let wrong = RemoteError { target: "203.0.113.8:443".parse().unwrap(), ..error() };
        assert!(pending.take(3, &wrong).is_none());
        let retired = context(&budget);
        retired.destination.retire();
        pending.remember(5, Some(retired));
        assert!(pending.take(5, &error()).is_none());
        pending.remember(4, Some(context(&budget)));
        let cross_family = RemoteError {
            source: "2001:db8::1".parse().unwrap(),
            target: "[2001:db8::9]:443".parse().unwrap(),
            icmp_type: 1,
            icmp_code: 4,
            info: 0,
        };
        assert!(pending.take(4, &cross_family).is_none());
    }

    #[test]
    fn history_is_bounded_expired_and_refunds_shared_quote_bytes() {
        let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
        let mut pending = PendingErrors::default();
        for id in 1..=65 {
            pending.remember(id, Some(context(&budget)));
        }
        assert_eq!(pending.0.len(), 64);
        assert!(budget.try_reserve_queued_bytes(4 * 1024 * 1024).is_none());
        pending.0.get_mut(&1).unwrap().0 = Instant::now() - Duration::from_secs(6);
        assert!(pending.take(1, &error()).is_none());
        pending.clear();
        assert!(budget.try_reserve_queued_bytes(4 * 1024 * 1024).is_some());
    }
}

#[cfg(test)]
mod expiry_tests {
    use super::*;

    /// # Cancel safety:
    /// cancel-safe: all quotes and permits are local RAII values, including across clock advance.
    #[tokio::test(start_paused = true)]
    async fn an_idle_history_expires_and_releases_the_aggregate_budget() {
        let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
        let mut pending = PendingErrors::default();
        pending.remember(1, Some(super::tests::context(&budget)));
        assert!(budget.try_reserve_queued_bytes(4 * 1024 * 1024).is_none());
        let deadline = pending.next_expiry().unwrap();
        tokio::time::advance(Duration::from_secs(5)).await;
        tokio::time::sleep_until(deadline).await;
        pending.expire();
        assert!(pending.next_expiry().is_none());
        assert!(budget.try_reserve_queued_bytes(4 * 1024 * 1024).is_some());
    }
}
