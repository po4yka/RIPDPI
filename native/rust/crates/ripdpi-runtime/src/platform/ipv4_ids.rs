use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, SocketAddrV4, TcpStream, UdpSocket};
use std::sync::OnceLock;

use crate::sync::Mutex;
use ripdpi_config::IpIdMode;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
struct Ipv4FlowKey {
    source: SocketAddrV4,
    target: SocketAddrV4,
}

#[derive(Debug, Default)]
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) struct Ipv4IdAllocator {
    next_seq_by_flow: HashMap<Ipv4FlowKey, u16>,
    rnd_state: u32,
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
                let next = self.next_seq_by_flow.entry(key).or_insert(1);
                let mut ids = Vec::with_capacity(count);
                for _ in 0..count {
                    let current = *next;
                    ids.push(current);
                    *next = advance_ipv4_identification(current);
                }
                ids
            }
            IpIdMode::Rnd => (0..count).map(|_| self.next_random_non_zero()).collect(),
            IpIdMode::Zero => vec![0; count],
            _ => vec![0; count],
        }
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
    if value == u16::MAX {
        1
    } else {
        value + 1
    }
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
