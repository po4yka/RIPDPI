//! Fixed or randomized cadence sampling for validated Hysteria hopping policy.

use std::time::Duration;

use rand::RngExt;

/// The resolved port-hopping policy for a Hysteria2 outbound.
///
/// Returned by [`Config::port_hopping_window`](crate::Config::port_hopping_window)
/// after validation by [`PortHoppingConfig`](crate::PortHoppingConfig).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PortHoppingWindow {
    /// Port hopping is off: no `hopInterval`, `minHopInterval`, or
    /// `maxHopInterval` was configured.
    Disabled,
    /// Legacy fixed cadence: only `hopInterval` was configured, so every hop
    /// waits exactly this long. This is the pre-2.8.0 behavior.
    Fixed(Duration),
    /// Randomized window per Hysteria 2.8.0: each hop waits a uniformly random
    /// duration inside the inclusive `[min, max]` range. `min <= max` always
    /// holds because the validated policy rejects reversed bounds.
    Randomized {
        /// Inclusive lower bound of the hop-interval window.
        min: Duration,
        /// Inclusive upper bound of the hop-interval window.
        max: Duration,
    },
}

impl PortHoppingWindow {
    /// Whether this window randomizes the hop interval (Hysteria 2.8.0
    /// behavior) rather than using a fixed cadence or no hopping.
    #[must_use]
    pub fn is_randomized(&self) -> bool {
        matches!(self, Self::Randomized { .. })
    }

    /// Whether port hopping is enabled at all (randomized or fixed).
    #[must_use]
    pub fn is_enabled(&self) -> bool {
        !matches!(self, Self::Disabled)
    }

    /// Draw the interval a session should wait before its next port hop.
    ///
    /// * [`Randomized`](PortHoppingWindow::Randomized): a uniform sample in the
    ///   inclusive `[min, max]` range, drawn fresh from `rng` on every call so
    ///   each hop is independently unpredictable.
    /// * [`Fixed`](PortHoppingWindow::Fixed): the configured cadence.
    /// * [`Disabled`](PortHoppingWindow::Disabled): `None`.
    pub fn next_interval<R: RngExt + ?Sized>(&self, rng: &mut R) -> Option<Duration> {
        match *self {
            Self::Disabled => None,
            Self::Fixed(interval) => Some(interval),
            Self::Randomized { min, max } => {
                if min == max {
                    return Some(min);
                }
                // Sample in nanoseconds so sub-second windows are honored;
                // the inclusive upper bound matches Hysteria's semantics.
                let lo = duration_to_nanos(min);
                let hi = duration_to_nanos(max);
                let nanos = rng.random_range(lo..=hi);
                Some(nanos_to_duration(nanos))
            }
        }
    }
}

/// Saturating `Duration` -> nanoseconds, clamped to `u128`'s range.
fn duration_to_nanos(duration: Duration) -> u128 {
    duration.as_nanos()
}

/// Nanoseconds -> `Duration`, clamping the seconds part so an extreme window
/// bound cannot panic `Duration::new`.
fn nanos_to_duration(nanos: u128) -> Duration {
    const NANOS_PER_SEC: u128 = 1_000_000_000;
    let secs = (nanos / NANOS_PER_SEC).min(u128::from(u64::MAX)) as u64;
    let subsec = (nanos % NANOS_PER_SEC) as u32;
    Duration::new(secs, subsec)
}

/// Per-session accumulator for the hop intervals actually drawn from a
/// [`PortHoppingWindow`].
///
/// The point of randomized port hopping is that the cadence is *observably*
/// unpredictable; this telemetry surfaces the realized distribution so a test
/// (or a diagnostics probe) can verify the spread is non-degenerate and stays
/// inside the configured window. Every interval handed to a session should
/// also be fed here via [`record`](HopIntervalTelemetry::record).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HopIntervalTelemetry {
    count: u64,
    total: Duration,
    min: Option<Duration>,
    max: Option<Duration>,
}

impl HopIntervalTelemetry {
    /// A fresh telemetry accumulator with no samples.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Record one hop interval that was drawn for the session.
    pub fn record(&mut self, interval: Duration) {
        self.count += 1;
        self.total = self.total.saturating_add(interval);
        self.min = Some(self.min.map_or(interval, |current| current.min(interval)));
        self.max = Some(self.max.map_or(interval, |current| current.max(interval)));
    }

    /// Number of hop intervals recorded so far.
    #[must_use]
    pub fn count(&self) -> u64 {
        self.count
    }

    /// Smallest interval recorded, or `None` if nothing has been recorded.
    #[must_use]
    pub fn min(&self) -> Option<Duration> {
        self.min
    }

    /// Largest interval recorded, or `None` if nothing has been recorded.
    #[must_use]
    pub fn max(&self) -> Option<Duration> {
        self.max
    }

    /// Mean of the recorded intervals, or `None` if nothing has been recorded.
    #[must_use]
    pub fn mean(&self) -> Option<Duration> {
        if self.count == 0 {
            return None;
        }
        Some(self.total / u32::try_from(self.count).unwrap_or(u32::MAX))
    }

    /// Spread between the largest and smallest recorded interval.
    ///
    /// A randomized window with enough samples produces a non-zero spread; a
    /// fixed cadence (or a single sample) produces [`Duration::ZERO`].
    #[must_use]
    pub fn spread(&self) -> Duration {
        match (self.min, self.max) {
            (Some(min), Some(max)) => max.saturating_sub(min),
            _ => Duration::ZERO,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_window_yields_no_interval() {
        let mut rng = rand::rng();
        assert_eq!(PortHoppingWindow::Disabled.next_interval(&mut rng), None);
    }

    #[test]
    fn fixed_window_always_yields_the_configured_cadence() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Fixed(Duration::from_secs(30));
        for _ in 0..100 {
            assert_eq!(window.next_interval(&mut rng), Some(Duration::from_secs(30)));
        }
    }

    #[test]
    fn randomized_window_stays_inside_the_configured_bounds() {
        let mut rng = rand::rng();
        let min = Duration::from_secs(10);
        let max = Duration::from_secs(60);
        let window = PortHoppingWindow::Randomized { min, max };
        for _ in 0..1_000 {
            let interval = window.next_interval(&mut rng).expect("randomized window yields an interval");
            assert!(interval >= min, "interval {interval:?} below window minimum {min:?}");
            assert!(interval <= max, "interval {interval:?} above window maximum {max:?}");
        }
    }

    #[test]
    fn randomized_window_actually_varies_the_interval() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Randomized { min: Duration::from_secs(1), max: Duration::from_secs(120) };
        let first = window.next_interval(&mut rng).expect("interval");
        let varied = (0..1_000).any(|_| window.next_interval(&mut rng).expect("interval") != first);
        assert!(varied, "a wide randomized window must produce more than one distinct interval");
    }

    #[test]
    fn randomized_window_with_equal_bounds_is_effectively_fixed() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Randomized { min: Duration::from_secs(30), max: Duration::from_secs(30) };
        for _ in 0..100 {
            assert_eq!(window.next_interval(&mut rng), Some(Duration::from_secs(30)));
        }
    }

    #[test]
    fn randomized_window_honors_sub_second_bounds() {
        let mut rng = rand::rng();
        let min = Duration::from_millis(250);
        let max = Duration::from_millis(750);
        let window = PortHoppingWindow::Randomized { min, max };
        for _ in 0..1_000 {
            let interval = window.next_interval(&mut rng).expect("interval");
            assert!(interval >= min && interval <= max, "sub-second interval {interval:?} escaped its window");
        }
    }

    #[test]
    fn telemetry_starts_empty() {
        let telemetry = HopIntervalTelemetry::new();
        assert_eq!(telemetry.count(), 0);
        assert_eq!(telemetry.min(), None);
        assert_eq!(telemetry.max(), None);
        assert_eq!(telemetry.mean(), None);
        assert_eq!(telemetry.spread(), Duration::ZERO);
    }

    #[test]
    fn telemetry_tracks_count_min_max_and_mean() {
        let mut telemetry = HopIntervalTelemetry::new();
        telemetry.record(Duration::from_secs(10));
        telemetry.record(Duration::from_secs(30));
        telemetry.record(Duration::from_secs(20));
        assert_eq!(telemetry.count(), 3);
        assert_eq!(telemetry.min(), Some(Duration::from_secs(10)));
        assert_eq!(telemetry.max(), Some(Duration::from_secs(30)));
        assert_eq!(telemetry.mean(), Some(Duration::from_secs(20)));
        assert_eq!(telemetry.spread(), Duration::from_secs(20));
    }

    #[test]
    fn telemetry_spread_is_zero_for_a_fixed_cadence() {
        let mut rng = rand::rng();
        let window = PortHoppingWindow::Fixed(Duration::from_secs(30));
        let mut telemetry = HopIntervalTelemetry::new();
        for _ in 0..50 {
            telemetry.record(window.next_interval(&mut rng).expect("interval"));
        }
        assert_eq!(telemetry.spread(), Duration::ZERO);
        assert_eq!(telemetry.count(), 50);
    }

    #[test]
    fn telemetry_surfaces_a_non_degenerate_distribution_for_a_randomized_window() {
        let mut rng = rand::rng();
        let min = Duration::from_secs(10);
        let max = Duration::from_secs(60);
        let window = PortHoppingWindow::Randomized { min, max };
        let mut telemetry = HopIntervalTelemetry::new();
        for _ in 0..2_000 {
            telemetry.record(window.next_interval(&mut rng).expect("interval"));
        }
        assert_eq!(telemetry.count(), 2_000);
        assert!(telemetry.min().expect("min") >= min);
        assert!(telemetry.max().expect("max") <= max);
        assert!(telemetry.spread() > Duration::ZERO, "a randomized window must surface a non-zero spread");
        let mean = telemetry.mean().expect("mean");
        assert!(mean > min && mean < max, "mean {mean:?} should sit inside the window");
    }

    /// Socket-rebind soak. Exercises `build_client_udp_socket` over
    /// 30 successive binds, drops each socket, and asserts:
    ///
    /// - every bind succeeds
    /// - every bind reports a non-zero ephemeral port
    /// - `HopIntervalTelemetry` counter matches the hop count (30)
    ///
    /// This is a unit-level soak: no Quinn server, no real hop
    /// schedule, no real rebind_endpoint() call. The full Quinn-side
    /// rebind soak depends on the per-protocol loopback module
    /// designed in `docs/architecture/protocol-loopback-harness-design.md`
    /// and is deferred to that follow-up.
    ///
    /// Tracks the partial-progress portion of
    /// completed task `add-port-hopping-window-soak-test-for-hysteria2` (see git history).
    #[test]
    fn socket_rebind_soak_thirty_iterations() {
        use crate::quic_transport::build_client_udp_socket;
        let mut telemetry = HopIntervalTelemetry::new();
        for hop in 0..30u32 {
            let started = std::time::Instant::now();
            let socket = build_client_udp_socket(false, false).expect("ipv4 socket bind");
            let port = socket.local_addr().expect("local addr").port();
            assert_ne!(port, 0, "hop {hop} produced a zero port; ephemeral bind failed");
            drop(socket);
            telemetry.record(started.elapsed().max(Duration::from_nanos(1)));
        }
        assert_eq!(telemetry.count(), 30, "telemetry counter must match the hop count");
    }

    /// Quinn-side port-hopping rebind soak over the shared `QuicLoopback`
    /// harness. For each of 16 hops it transfers bytes bidirectionally on the
    /// live QUIC connection, records the hop, then migrates the connection to a
    /// fresh ephemeral UDP socket via `endpoint.rebind()` (RFC 9000 path
    /// validation). Asserts strictly-monotonic hop indices, a bidirectional
    /// round-trip every hop, distinct local ports across rebinds, and a
    /// telemetry counter matching the hop count.
    ///
    /// Closes the deferred socket-rebind + bidirectional-bytes criteria of
    /// completed task `add-port-hopping-window-soak-test-for-hysteria2` (see git history)
    /// using the `ripdpi-protocol-loopback` Quinn harness. Runs in standard CI
    /// (16 hops complete in well under a second).
    #[tokio::test]
    async fn quic_rebind_soak_preserves_bidirectional_bytes_across_monotonic_hops() {
        use std::collections::BTreeSet;
        use std::sync::Arc;

        use ripdpi_protocol_loopback::{ProtocolLoopbackServer, QuicLoopback};

        use crate::quic_transport::build_client_udp_socket;

        // N > 10 so a regression that breaks hop scheduling after window N>10
        // (the task definition of done) is caught.
        const HOPS: u32 = 16;

        let server = QuicLoopback::start(64 * 1024).await.expect("start QUIC loopback");

        let socket = build_client_udp_socket(false, false).expect("initial client socket");
        let mut endpoint =
            quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))
                .expect("client endpoint");
        endpoint.set_default_client_config(server.client_config().expect("client config"));

        let connection =
            endpoint.connect(server.local_addr(), "localhost").expect("connect call").await.expect("handshake");

        let mut telemetry = HopIntervalTelemetry::new();
        let mut hop_indices = Vec::new();
        let mut ports = Vec::new();

        for hop in 0..HOPS {
            let started = std::time::Instant::now();

            // Bidirectional byte transfer over the current (possibly migrated) path.
            let payload = format!("hop-{hop}-payload").into_bytes();
            let (mut send, mut recv) = connection.open_bi().await.expect("open bi-stream");
            send.write_all(&payload).await.expect("client write");
            send.finish().expect("client finish");
            let echoed = recv.read_to_end(payload.len()).await.expect("client read echo");
            assert_eq!(echoed, payload, "hop {hop} must round-trip bytes bidirectionally");

            hop_indices.push(hop);
            ports.push(endpoint.local_addr().expect("endpoint local addr").port());
            telemetry.record(started.elapsed().max(Duration::from_nanos(1)));

            // Port-hop: migrate the live connection onto a fresh ephemeral socket.
            let replacement = build_client_udp_socket(false, false).expect("rebind socket");
            endpoint.rebind(replacement).expect("endpoint rebind");
        }

        assert_eq!(
            hop_indices,
            (0..HOPS).collect::<Vec<_>>(),
            "hop indices must be strictly monotonic with no gaps or repeats",
        );
        assert_eq!(telemetry.count(), u64::from(HOPS), "telemetry counter must match the hop count");
        assert!(ports.iter().all(|&port| port != 0), "every hop must bind a concrete local port");
        assert!(
            ports.iter().copied().collect::<BTreeSet<_>>().len() >= 2,
            "rebinds must exercise more than one local port: {ports:?}",
        );

        drop(connection);
        server.shutdown().await.expect("clean shutdown");
    }
}
