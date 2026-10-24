//! Validated remote-port policy shared by URI and native relay configuration.

use std::time::{Duration, Instant};

use rand::RngExt;

use crate::error::{HysteriaError, Result};
use crate::port_hopping::PortHoppingWindow;

pub const MIN_HOP_INTERVAL_MILLIS: u64 = 5_000;
pub const DEFAULT_HOP_INTERVAL_MILLIS: u64 = 30_000;
/// The whole-millisecond portion of Go's signed 64-bit nanosecond duration.
pub const MAX_HOP_INTERVAL_MILLIS: u64 = 9_223_372_036_854;
const MAX_PORT_SPEC_BYTES: usize = 1_048_576;

/// One validated port union and cadence. Fields are private so invalid policy
/// cannot reach socket creation through a direct Rust caller.
#[derive(Clone, PartialEq, Eq)]
pub struct PortHoppingConfig {
    ports: Vec<u16>,
    window: PortHoppingWindow,
}

impl std::fmt::Debug for PortHoppingConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PortHoppingConfig")
            .field("port_count", &self.ports.len())
            .field("window", &self.window)
            .finish()
    }
}

impl PortHoppingConfig {
    /// Validate the optional native JSON fields before any network operation.
    pub fn from_wire(ports: Option<&str>, interval_ms: Option<u64>, max_ms: Option<u64>) -> Result<Option<Self>> {
        let Some(ports) = ports else {
            if interval_ms.is_some() || max_ms.is_some() {
                return Err(invalid_policy("interval requires a port set"));
            }
            return Ok(None);
        };
        let ports = parse_ports(ports)?;
        let min = interval_ms.unwrap_or(DEFAULT_HOP_INTERVAL_MILLIS);
        let max = max_ms.unwrap_or(min);
        if !(MIN_HOP_INTERVAL_MILLIS..=MAX_HOP_INTERVAL_MILLIS).contains(&min)
            || !(min..=MAX_HOP_INTERVAL_MILLIS).contains(&max)
        {
            return Err(invalid_policy("interval is outside the supported bounds"));
        }
        let window = if min == max {
            PortHoppingWindow::Fixed(Duration::from_millis(min))
        } else {
            PortHoppingWindow::Randomized { min: Duration::from_millis(min), max: Duration::from_millis(max) }
        };
        let policy = Self { ports, window };
        policy.validate_deadline()?;
        Ok(Some(policy))
    }

    pub fn ports(&self) -> &[u16] {
        &self.ports
    }

    pub fn window(&self) -> PortHoppingWindow {
        self.window
    }

    /// Verify platform monotonic-clock capacity before DNS or socket work.
    pub fn validate_deadline(&self) -> Result<()> {
        let maximum = match self.window {
            PortHoppingWindow::Fixed(duration) => duration,
            PortHoppingWindow::Randomized { max, .. } => max,
            PortHoppingWindow::Disabled => return Err(invalid_policy("missing interval")),
        };
        if Instant::now().checked_add(maximum).is_none() {
            return Err(invalid_policy("interval exceeds monotonic clock capacity"));
        }
        Ok(())
    }

    pub(crate) fn initial_index<R: RngExt + ?Sized>(&self, rng: &mut R) -> usize {
        rng.random_range(0..self.ports.len())
    }

    pub(crate) fn next_index<R: RngExt + ?Sized>(&self, current: usize, rng: &mut R) -> usize {
        if self.ports.len() == 1 {
            return 0;
        }
        let index = rng.random_range(0..self.ports.len() - 1);
        if index >= current { index + 1 } else { index }
    }

    pub(crate) fn contains_port(&self, port: u16) -> bool {
        self.ports.binary_search(&port).is_ok()
    }
}

pub(crate) fn invalid_policy(reason: &str) -> HysteriaError {
    HysteriaError::InvalidAddress(format!("invalid Hysteria port hopping: {reason}"))
}

fn parse_ports(raw: &str) -> Result<Vec<u16>> {
    if raw.is_empty() || raw.len() > MAX_PORT_SPEC_BYTES {
        return Err(invalid_policy("invalid port set length"));
    }
    let mut ranges = Vec::new();
    for part in raw.split(',') {
        let mut bounds = part.split('-');
        let start = parse_port(bounds.next().unwrap_or_default())?;
        let end = bounds.next().map(parse_port).transpose()?.unwrap_or(start);
        if bounds.next().is_some() || start > end {
            return Err(invalid_policy("invalid port range"));
        }
        if ranges.len() == 65_535 {
            return Err(invalid_policy("too many port components"));
        }
        ranges.push((start, end));
    }
    ranges.sort_unstable();
    let mut merged: Vec<(u16, u16)> = Vec::new();
    for (start, end) in ranges {
        if let Some(last) = merged.last_mut()
            && u32::from(start) <= u32::from(last.1) + 1
        {
            last.1 = last.1.max(end);
        } else {
            merged.push((start, end));
        }
    }
    Ok(merged.into_iter().flat_map(|(start, end)| start..=end).collect())
}

fn parse_port(raw: &str) -> Result<u16> {
    if raw.is_empty() || !raw.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(invalid_policy("invalid port number"));
    }
    raw.parse::<u16>().ok().filter(|port| *port != 0).ok_or_else(|| invalid_policy("invalid port number"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_union_without_expanding_overlapping_ranges_repeatedly() {
        let policy = PortHoppingConfig::from_wire(Some("65535,9-12,11-15,1,9"), None, None).unwrap().unwrap();
        assert_eq!(policy.ports(), &[1, 9, 10, 11, 12, 13, 14, 15, 65535]);
        assert_eq!(policy.window(), PortHoppingWindow::Fixed(Duration::from_secs(30)));
        let all = PortHoppingConfig::from_wire(Some("1-65535,1-65535"), None, None).unwrap().unwrap();
        assert_eq!(all.ports().len(), 65_535);
    }

    #[test]
    fn rejects_bad_ranges_and_intervals_before_runtime() {
        for ports in ["", "0", "65536", "3-2", "1:2", "1,,2", "1-2-3", " 443", "+443"] {
            assert!(PortHoppingConfig::from_wire(Some(ports), None, None).is_err(), "{ports}");
        }
        for (min, max) in [(0, None), (4_999, None), (5_000, Some(4_999)), (MAX_HOP_INTERVAL_MILLIS + 1, None)] {
            assert!(PortHoppingConfig::from_wire(Some("443,444"), Some(min), max).is_err());
        }
        assert!(PortHoppingConfig::from_wire(None, Some(30_000), None).is_err());
        assert!(PortHoppingConfig::from_wire(None, None, Some(30_000)).is_err());
        assert_eq!(PortHoppingConfig::from_wire(None, None, None).unwrap(), None);
    }

    #[test]
    fn respects_interval_boundaries_and_normalizes_equal_bounds() {
        for interval in [MIN_HOP_INTERVAL_MILLIS, MAX_HOP_INTERVAL_MILLIS] {
            let policy = PortHoppingConfig::from_wire(Some("443"), Some(interval), Some(interval)).unwrap().unwrap();
            assert_eq!(policy.window(), PortHoppingWindow::Fixed(Duration::from_millis(interval)));
        }
        let policy = PortHoppingConfig::from_wire(Some("443,444"), Some(5_000), Some(6_000)).unwrap().unwrap();
        assert!(policy.window().is_randomized());
        let mut rng = rand::rng();
        for current in 0..policy.ports.len() {
            assert_ne!(current, policy.next_index(current, &mut rng));
        }
    }
}
