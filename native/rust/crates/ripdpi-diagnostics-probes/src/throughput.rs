//! Throughput measurement classifier wrapped as a Probe.
//!
//! Compares a measured bytes/duration sample against a configured
//! baseline + two thresholds (degraded floor, severe floor). The
//! classification is pure: the Probe adapter does no I/O, and the
//! verdict is determined entirely by the captured measurement vs.
//! the baseline.
//!
//! ## Verdict mapping
//!
//! Evaluated in this priority order (first match wins):
//!
//! 1. `Inconclusive { reason: "duration is zero" }` — no time
//!    elapsed; bytes/sec is undefined.
//! 2. `Fail { class: "throughput-stalled" }` — total_bytes is zero
//!    but duration is non-zero. The flow opened but never moved data.
//! 3. `Pass` — measured throughput ≥ `expected_bps × degraded_threshold_pct%`.
//! 4. `Fail { class: "throughput-degraded" }` — measured ≥
//!    `expected_bps × severe_threshold_pct%` but below the degraded
//!    floor.
//! 5. `Fail { class: "throughput-severely-degraded" }` — measured
//!    below the severe floor.
//!
//! All ratio comparisons use 128-bit integer arithmetic to avoid
//! float precision issues at the threshold boundaries and to avoid
//! overflow for high-bandwidth baselines.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier. Embedded in goldens and telemetry.
pub const THROUGHPUT_PROBE_ID: &str = "throughput";

/// Captured measurement passed to the probe.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ThroughputMeasurement {
    /// Total bytes transferred during the measurement window.
    pub total_bytes: u64,
    /// Measurement window length, in milliseconds.
    pub duration_ms: u64,
}

/// Baseline expectation and degradation thresholds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ThroughputBaseline {
    /// Expected throughput, bytes per second.
    pub expected_bps: u64,
    /// Below this percentage of the baseline the flow is considered
    /// degraded. Inclusive — measured ≥ this percentage maps to Pass.
    pub degraded_threshold_pct: u8,
    /// Below this percentage of the baseline the flow is considered
    /// severely degraded. Inclusive — measured ≥ this percentage but
    /// below the degraded floor maps to `Fail{throughput-degraded}`.
    pub severe_threshold_pct: u8,
}

/// Pure [`Probe`] adapter over a captured throughput measurement.
#[derive(Debug, Clone)]
pub struct ThroughputProbe {
    measurement: ThroughputMeasurement,
    baseline: ThroughputBaseline,
}

impl ThroughputProbe {
    /// Construct from a captured measurement and the baseline + thresholds
    /// the runner was configured with.
    pub fn new(measurement: ThroughputMeasurement, baseline: ThroughputBaseline) -> Self {
        Self { measurement, baseline }
    }
}

impl Probe for ThroughputProbe {
    fn id(&self) -> &'static str {
        THROUGHPUT_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Throughput
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(self.measurement, self.baseline);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

// ─── Async runner ─────────────────────────────────────────────────────────

/// Async runner that opens a TCP connection to `target_host:target_port`,
/// drains up to `max_bytes` of inbound bytes within `window`, and builds a
/// [`ThroughputProbe`] from the measured throughput vs. the supplied
/// baseline.
///
/// The runner is direct-TCP only and reads — it does not send a
/// request. Use it against a target you control that streams bytes
/// on connect (e.g. the local-network fixture's throughput endpoint).
/// `ctx.relay_hint` is noted for future extension but does not currently
/// alter the connect path.
#[derive(Debug, Clone)]
pub struct ThroughputRunner {
    /// Host to connect to.
    pub target_host: String,
    /// Port to connect to.
    pub target_port: u16,
    /// Maximum bytes to read before stopping the measurement.
    pub max_bytes: u64,
    /// Maximum window length. Reads stop at whichever of `max_bytes` /
    /// `window` arrives first.
    pub window: std::time::Duration,
    /// Baseline and thresholds passed through to the produced probe.
    pub baseline: ThroughputBaseline,
}

impl ThroughputRunner {
    /// Connect to the configured target, measure, and return a
    /// [`ThroughputProbe`]. On connect failure or read error returns a
    /// probe with `total_bytes = 0` so the verdict comes out as
    /// `throughput-stalled` — exposing the failure through the same
    /// verdict surface as a real stall.
    pub async fn measure(&self, _ctx: &ProbeContext) -> ThroughputProbe {
        use tokio::io::AsyncReadExt;
        let addr = format!("{}:{}", self.target_host, self.target_port);
        let start = std::time::Instant::now();
        let conn_result = tokio::time::timeout(self.window, tokio::net::TcpStream::connect(&addr)).await;
        let Ok(Ok(mut stream)) = conn_result else {
            return ThroughputProbe::new(
                ThroughputMeasurement {
                    total_bytes: 0,
                    duration_ms: self.window.as_millis().min(u64::MAX as u128) as u64,
                },
                self.baseline,
            );
        };
        let mut buf = vec![0u8; 64 * 1024];
        let mut total: u64 = 0;
        loop {
            let remaining_budget = self.window.saturating_sub(start.elapsed());
            if remaining_budget.is_zero() || total >= self.max_bytes {
                break;
            }
            match tokio::time::timeout(remaining_budget, stream.read(&mut buf)).await {
                Ok(Ok(0)) => break,
                Ok(Ok(n)) => total = total.saturating_add(n as u64),
                Ok(Err(_)) => break,
                Err(_) => break, // window elapsed mid-read
            }
        }
        let duration_ms = start.elapsed().as_millis().min(u64::MAX as u128) as u64;
        ThroughputProbe::new(ThroughputMeasurement { total_bytes: total, duration_ms }, self.baseline)
    }
}

/// Pure verdict classifier; extracted so tests can call it directly.
fn classify_verdict(m: ThroughputMeasurement, b: ThroughputBaseline) -> ProbeVerdict {
    if m.duration_ms == 0 {
        return ProbeVerdict::Inconclusive { reason: "duration is zero".to_string() };
    }
    if m.total_bytes == 0 {
        return ProbeVerdict::Fail { class: "throughput-stalled".to_string() };
    }

    // measured_bps_x100 = total_bytes * 1000 * 100 / duration_ms
    //                   = bytes_per_second * 100
    // expected_bps_x_pct = expected_bps * pct
    // measured >= expected * pct/100  <=>  measured * 100 >= expected * pct
    // To avoid integer division when comparing, multiply both sides by duration_ms:
    //   total_bytes * 1000 * 100  >=  expected_bps * pct * duration_ms
    let lhs: u128 = u128::from(m.total_bytes) * 1000 * 100;
    let baseline_x_dur: u128 = u128::from(b.expected_bps) * u128::from(m.duration_ms);
    let degraded_rhs: u128 = baseline_x_dur * u128::from(b.degraded_threshold_pct);
    let severe_rhs: u128 = baseline_x_dur * u128::from(b.severe_threshold_pct);

    if lhs >= degraded_rhs {
        ProbeVerdict::Pass
    } else if lhs >= severe_rhs {
        ProbeVerdict::Fail { class: "throughput-degraded".to_string() }
    } else {
        ProbeVerdict::Fail { class: "throughput-severely-degraded".to_string() }
    }
}
