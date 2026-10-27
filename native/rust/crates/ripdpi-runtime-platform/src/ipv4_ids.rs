//! Runtime-adaptation — process-global IPv4 identification allocator.
//!
//! Hands out IPv4 IP-ID values for locally crafted packets, keyed per flow,
//! honouring the configured [`IpIdMode`](ripdpi_config::IpIdMode) (sequential,
//! grouped, random, zero). State is a process-global `OnceLock<Mutex<...>>`
//! shared across all flows. Crate-internal (`pub(crate)`): the `fake_send` /
//! `ip_fragmentation` modules consume it for their local (non-root-helper)
//! paths; it is not part of the crate's public surface.

use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, SocketAddrV4, TcpStream, UdpSocket};
use std::sync::OnceLock;

use ripdpi_config::IpIdMode;
use std::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
struct Ipv4FlowKey {
    source: SocketAddrV4,
    target: SocketAddrV4,
}

/// Per-flow sequence state is kept for this many flows; the least recently
/// used flow is evicted when a new one arrives, so the map stays bounded for
/// the process lifetime.
pub(crate) const MAX_TRACKED_FLOWS: usize = 4096;

#[derive(Debug, Default)]
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) struct Ipv4IdAllocator {
    next_seq_by_flow: HashMap<Ipv4FlowKey, FlowSequence>,
    use_counter: u64,
    rnd_state: u32,
}

#[derive(Debug)]
struct FlowSequence {
    next: u16,
    last_used: u64,
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
impl Ipv4IdAllocator {
    pub(crate) fn reserve(
        &mut self,
        source: SocketAddrV4,
        target: SocketAddrV4,
        mode: IpIdMode,
        count: usize,
    ) -> Vec<u16> {
        match mode {
            IpIdMode::Seq | IpIdMode::SeqGroup => {
                let key = Ipv4FlowKey { source, target };
                self.use_counter += 1;
                if !self.next_seq_by_flow.contains_key(&key) && self.next_seq_by_flow.len() >= MAX_TRACKED_FLOWS {
                    let oldest =
                        self.next_seq_by_flow.iter().min_by_key(|(_, flow)| flow.last_used).map(|(key, _)| *key);
                    if let Some(oldest) = oldest {
                        self.next_seq_by_flow.remove(&oldest);
                    }
                }
                let flow = self.next_seq_by_flow.entry(key).or_insert(FlowSequence { next: 1, last_used: 0 });
                flow.last_used = self.use_counter;
                let mut ids = Vec::with_capacity(count);
                for _ in 0..count {
                    let current = flow.next;
                    ids.push(current);
                    flow.next = advance_ipv4_identification(current);
                }
                ids
            }
            IpIdMode::Rnd => (0..count).map(|_| self.next_random_non_zero()).collect(),
            IpIdMode::Zero => vec![0; count],
            _ => vec![0; count],
        }
    }

    #[cfg(test)]
    pub(crate) fn tracked_flows(&self) -> usize {
        self.next_seq_by_flow.len()
    }

    fn next_random_non_zero(&mut self) -> u16 {
        if self.rnd_state == 0 {
            self.rnd_state = 0x9e37_79b9;
        }
        loop {
            self.rnd_state ^= self.rnd_state << 13;
            self.rnd_state ^= self.rnd_state >> 17;
            self.rnd_state ^= self.rnd_state << 5;
            let candidate = (self.rnd_state & u32::from(u16::MAX)) as u16;
            if candidate != 0 {
                return candidate;
            }
        }
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
static IPV4_ID_ALLOCATOR: OnceLock<Mutex<Ipv4IdAllocator>> = OnceLock::new();

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
fn advance_ipv4_identification(value: u16) -> u16 {
    if value == u16::MAX { 1 } else { value + 1 }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) fn reserve_ipv4_identifications(
    source: SocketAddr,
    target: SocketAddr,
    mode: Option<IpIdMode>,
    count: usize,
) -> Vec<u16> {
    let Some(mode) = mode else {
        return Vec::new();
    };
    let (SocketAddr::V4(source), SocketAddr::V4(target)) = (source, target) else {
        return Vec::new();
    };
    let allocator = IPV4_ID_ALLOCATOR.get_or_init(|| Mutex::new(Ipv4IdAllocator::default()));
    allocator.lock().map(|mut guard| guard.reserve(source, target, mode, count)).unwrap_or_default()
}

/// Returns the ID to put in both IPv4 fragments of one datagram. Zero is not
/// usable: an `IP_HDRINCL` socket fills a zero ID per packet (raw(7)), so the
/// two fragments would carry different IDs and never reassemble. `None` lets
/// the sender derive a nonzero per-datagram ID.
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) fn fragment_identification(reserved: Option<u16>) -> Option<u16> {
    reserved.filter(|id| *id != 0)
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) fn reserve_stream_ipv4_identifications(
    stream: &TcpStream,
    mode: Option<IpIdMode>,
    count: usize,
) -> io::Result<Vec<u16>> {
    Ok(reserve_ipv4_identifications(stream.local_addr()?, stream.peer_addr()?, mode, count))
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) fn reserve_udp_ipv4_identifications(
    socket: &UdpSocket,
    target: SocketAddr,
    mode: Option<IpIdMode>,
    count: usize,
) -> io::Result<Vec<u16>> {
    Ok(reserve_ipv4_identifications(socket.local_addr()?, target, mode, count))
}
