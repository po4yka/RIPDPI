//! Orders resolved proxy addresses so the ones that accept a TCP connection come first.
//!
//! The upstream client uses the first printed address, so a dead first answer
//! would fail the start although a later one works.

use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use tokio::task::JoinSet;

pub(crate) const PROBE_TIMEOUT: Duration = Duration::from_millis(1500);
pub(crate) const PROBE_STEP_BOUND: Duration = Duration::from_secs(3);

/// Probes every address concurrently and returns the accepting ones first
/// (IPv4 before IPv6, resolver order inside each family), then the rest in
/// resolver order. A probe that has not finished when `step_bound` ends counts
/// as failed. When nothing accepts, the resolver order is returned unchanged.
///
/// # Cancel safety
///
/// cancel-safe: the probes live in a `JoinSet` that aborts them when the future is
/// dropped. In protected mode an aborted probe detaches its blocking connect, which
/// ends within the probe timeout after protect returns. Resolve mode exits the
/// process explicitly after its one output line, so a detached task cannot keep it alive.
pub(crate) async fn order_by_reachability<F, Fut>(
    addrs: Vec<SocketAddr>,
    step_bound: Duration,
    probe: F,
) -> Vec<SocketAddr>
where
    F: Fn(SocketAddr) -> Fut,
    Fut: Future<Output = io::Result<()>> + Send + 'static,
{
    let mut tasks: JoinSet<(usize, bool)> = JoinSet::new();
    for (index, addr) in addrs.iter().enumerate() {
        let attempt = probe(*addr);
        tasks.spawn(async move { (index, attempt.await.is_ok()) });
    }
    let mut accepted = vec![false; addrs.len()];
    let collect = async {
        while let Some(joined) = tasks.join_next().await {
            if let Ok((index, ok)) = joined
                && let Some(slot) = accepted.get_mut(index)
            {
                *slot = ok;
            }
        }
    };
    // A timeout here only stops waiting; unfinished probes stay `false`.
    let _ = tokio::time::timeout(step_bound, collect).await;
    tasks.abort_all();

    let pick = |want: &dyn Fn(usize, &SocketAddr) -> bool| -> Vec<SocketAddr> {
        addrs.iter().enumerate().filter(|(i, a)| want(*i, a)).map(|(_, a)| *a).collect()
    };
    let mut ordered = pick(&|i, a| accepted[i] && a.is_ipv4());
    ordered.extend(pick(&|i, a| accepted[i] && a.is_ipv6()));
    ordered.extend(pick(&|i, _| !accepted[i]));
    ordered
}

#[cfg(test)]
mod tests {
    use super::*;

    fn addr(text: &str) -> SocketAddr {
        text.parse().unwrap()
    }

    async fn real_probe(target: SocketAddr) -> io::Result<()> {
        ripdpi_vless::endpoint_resolver::probe_tcp_reachable(target, PROBE_TIMEOUT, None).await
    }

    #[tokio::test]
    async fn reachable_address_comes_first() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let open = listener.local_addr().unwrap();
        let closed = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap()
        };

        let ordered = order_by_reachability(vec![closed, open], PROBE_STEP_BOUND, real_probe).await;

        assert_eq!(ordered, vec![open, closed]);
    }

    #[tokio::test]
    async fn accepted_ipv4_precedes_accepted_ipv6_and_rest_keep_order() {
        let (v6, v4, dead_a, dead_b) =
            (addr("[2001:db8::1]:1"), addr("192.0.2.1:1"), addr("192.0.2.2:1"), addr("192.0.2.3:1"));
        let probe = move |target: SocketAddr| async move {
            if target == v6 || target == v4 { Ok(()) } else { Err(io::Error::other("refused")) }
        };

        let ordered = order_by_reachability(vec![dead_a, v6, dead_b, v4], PROBE_STEP_BOUND, probe).await;

        assert_eq!(ordered, vec![v4, v6, dead_a, dead_b]);
    }

    #[tokio::test]
    async fn no_acceptor_keeps_resolver_order() {
        let addrs = vec![addr("192.0.2.2:1"), addr("[2001:db8::1]:1"), addr("192.0.2.1:1")];
        let probe = |_: SocketAddr| async { Err(io::Error::other("refused")) };

        assert_eq!(order_by_reachability(addrs.clone(), PROBE_STEP_BOUND, probe).await, addrs);
    }

    #[tokio::test]
    async fn step_bound_stops_waiting_for_a_hung_probe() {
        let (fast, hung) = (addr("192.0.2.1:1"), addr("192.0.2.2:1"));
        let probe = move |target: SocketAddr| async move {
            if target == hung {
                std::future::pending::<()>().await;
            }
            Ok(())
        };
        let started = std::time::Instant::now();

        let ordered = order_by_reachability(vec![hung, fast], Duration::from_millis(150), probe).await;

        assert_eq!(ordered, vec![fast, hung]);
        assert!(started.elapsed() < Duration::from_secs(2), "took {:?}", started.elapsed());
    }
}
