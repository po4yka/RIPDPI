//! The privileged sender owns fragment IDs across all callers, including helpers.
//! Never evict a live lease.

use std::collections::{HashSet, VecDeque};
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

// shortcut: retain leases only in this process, for the conventional 120s MDL estimate (RFC 6864, section 3.2).
// A restart loses leases; paths with longer datagram lifetimes need a longer retention window.
const IDENTIFICATION_RETENTION: Duration = Duration::from_secs(120);
// Bound memory independently of the number of source/destination pairs.
const MAX_ACTIVE_IDENTIFICATIONS: usize = u16::MAX as usize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct FragmentKey {
    source: IpAddr,
    destination: IpAddr,
    protocol: u8,
    identification: u32,
}

#[derive(Default)]
struct FragmentIdentifications {
    next_ipv4: u32,
    next_ipv6: u32,
    active: HashSet<FragmentKey>,
    history: VecDeque<(Instant, FragmentKey)>,
}

impl FragmentIdentifications {
    fn reserve(
        &mut self,
        source: SocketAddr,
        destination: SocketAddr,
        protocol: u8,
        preferred: Option<u16>,
        now: Instant,
    ) -> io::Result<u32> {
        if source.is_ipv4() != destination.is_ipv4() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "fragment addresses use different IP families"));
        }
        self.expire(now);
        if self.active.len() >= MAX_ACTIVE_IDENTIFICATIONS {
            return Err(io::Error::new(io::ErrorKind::WouldBlock, "IP fragment identification capacity exhausted"));
        }

        let limit = if source.is_ipv4() { u32::from(u16::MAX) } else { u32::MAX };
        let key = FragmentKey {
            source: source.ip(),
            destination: destination.ip(),
            // IPv6 reassembly does not include the upper-layer protocol.
            protocol: if source.is_ipv4() { protocol } else { 0 },
            identification: 0,
        };
        let cursor = if source.is_ipv4() { &mut self.next_ipv4 } else { &mut self.next_ipv6 };
        let current = (*cursor).clamp(1, limit);
        let preferred = preferred.filter(|id| source.is_ipv4() && *id != 0).map(u32::from);
        let mut candidate = preferred.unwrap_or(current);
        let preferred_available =
            preferred.is_some() && !self.active.contains(&FragmentKey { identification: candidate, ..key });
        if !preferred_available {
            candidate = current;
        }
        for _ in 0..=self.active.len() {
            let candidate_key = FragmentKey { identification: candidate, ..key };
            if self.active.insert(candidate_key) {
                self.history.push_back((now, candidate_key));
                let next = advance(candidate, limit);
                *cursor = if preferred_available { current.max(next) } else { next };
                return Ok(candidate);
            }
            candidate = advance(candidate, limit);
        }
        Err(io::Error::new(io::ErrorKind::WouldBlock, "IP fragment identification namespace exhausted"))
    }

    fn expire(&mut self, now: Instant) {
        while let Some((reserved_at, key)) = self.history.front().copied() {
            if now.saturating_duration_since(reserved_at) < IDENTIFICATION_RETENTION {
                break;
            }
            self.history.pop_front();
            self.active.remove(&key);
        }
    }
}

fn advance(value: u32, limit: u32) -> u32 {
    if value == limit { 1 } else { value + 1 }
}

static IDENTIFICATIONS: OnceLock<Mutex<FragmentIdentifications>> = OnceLock::new();

pub(crate) fn reserve_fragment_identification(
    source: SocketAddr,
    destination: SocketAddr,
    protocol: u8,
    preferred: Option<u16>,
) -> io::Result<u32> {
    let mut allocator = IDENTIFICATIONS
        .get_or_init(|| Mutex::new(FragmentIdentifications::default()))
        .lock()
        .map_err(|_| io::Error::other("IP fragment identification allocator is poisoned"))?;
    allocator.reserve(source, destination, protocol, preferred, Instant::now())
}

#[cfg(test)]
mod tests;
