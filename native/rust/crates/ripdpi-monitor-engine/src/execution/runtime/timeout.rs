use std::time::Duration;

use crate::util::CONNECT_TIMEOUT;

/// Compute adaptive connect timeout based on observed control RTT.
/// Uses max(MIN_ADAPTIVE_TIMEOUT, control_rtt * RTT_MULTIPLIER) capped at CONNECT_TIMEOUT.
/// Currently a building block for future per-candidate timeout tuning.
#[allow(dead_code)]
pub(super) fn adaptive_connect_timeout(control_rtt_ms: Option<u64>) -> Duration {
    const MIN_ADAPTIVE_TIMEOUT: Duration = Duration::from_millis(1500);
    const RTT_MULTIPLIER: u64 = 15;

    match control_rtt_ms {
        Some(rtt) if rtt > 0 => {
            let scaled = Duration::from_millis(rtt * RTT_MULTIPLIER);
            scaled.max(MIN_ADAPTIVE_TIMEOUT).min(CONNECT_TIMEOUT)
        }
        _ => CONNECT_TIMEOUT,
    }
}
