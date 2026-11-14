use std::io;
use std::net::{Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

// ── RecordingUdpServer ──

/// A UDP echo server that records every received datagram's raw bytes.
pub struct RecordingUdpServer {
    port: u16,
    stop: Arc<AtomicBool>,
    received: Arc<Mutex<Vec<Vec<u8>>>>,
    handle: Option<JoinHandle<()>>,
}

impl RecordingUdpServer {
    pub fn start(port: u16) -> io::Result<Self> {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, port))?;
        socket.set_read_timeout(Some(Duration::from_millis(200)))?;
        let local_port = socket.local_addr()?.port();
        let stop = Arc::new(AtomicBool::new(false));
        let received: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));

        let stop_flag = stop.clone();
        let recv_log = received.clone();
        let handle = thread::spawn(move || {
            let mut buf = [0u8; 4096];
            while !stop_flag.load(Ordering::Relaxed) {
                match socket.recv_from(&mut buf) {
                    Ok((read, peer)) => {
                        let data = buf[..read].to_vec();
                        recv_log.lock().expect("lock received").push(data.clone());
                        let _ = socket.send_to(&data, peer);
                    }
                    Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                    Err(_) => break,
                }
            }
        });

        Ok(Self { port: local_port, stop, received, handle: Some(handle) })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn snapshot(&self) -> Vec<Vec<u8>> {
        self.received.lock().expect("lock received").clone()
    }
}

impl Drop for RecordingUdpServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

// ── TCP option parsing ──

/// Returns true if the raw TCP options bytes contain an option with `target_kind`.
pub fn tcp_options_contain_kind(options: &[u8], target_kind: u8) -> bool {
    let mut i = 0;
    while i < options.len() {
        let kind = options[i];
        if kind == 0 {
            break;
        }
        if kind == 1 {
            i += 1;
            continue;
        }
        if kind == target_kind {
            return true;
        }
        if i + 1 >= options.len() {
            break;
        }
        let len = options[i + 1] as usize;
        if len < 2 {
            break;
        }
        i += len;
    }
    false
}

/// A captured IPv4 packet with parsed transport fields.
#[derive(Debug, Clone)]
pub struct CapturedPacket {
    pub protocol: u8,
    pub src_ip: Ipv4Addr,
    pub dst_ip: Ipv4Addr,
    pub src_port: u16,
    pub dst_port: u16,
    pub ttl: u8,
    pub tcp_window: Option<u16>,
    pub tcp_flags: Option<u8>,
    pub tcp_sequence_number: Option<u32>,
    pub tcp_options: Option<Vec<u8>>,
    pub payload: Vec<u8>,
}

/// Reads the receive-window exponent offered in a SYN's TCP options.
fn tcp_window_scale(options: &[u8]) -> Result<Option<u8>, &'static str> {
    let mut remaining = options;
    let mut scale = None;
    while let Some(&kind) = remaining.first() {
        match kind {
            0 => break,
            1 => remaining = &remaining[1..],
            _ => {
                let len = usize::from(*remaining.get(1).ok_or("truncated TCP option")?);
                if len < 2 || len > remaining.len() {
                    return Err("invalid TCP option length");
                }
                if kind == 3 {
                    if len != 3 || scale.is_some() {
                        return Err("invalid window scale option");
                    }
                    scale = Some(remaining[2].min(14));
                }
                remaining = &remaining[len..];
            }
        }
    }
    Ok(scale)
}

/// Returns positive receive windows after the peer passed the initial advertised edge.
pub fn tcp_receive_windows_after_initial_edge(
    packets: &[CapturedPacket],
    server_port: u16,
) -> Result<(u8, Vec<u32>), &'static str> {
    let syn = packets
        .iter()
        .find(|packet| {
            packet.protocol == 6
                && packet.src_ip == Ipv4Addr::LOCALHOST
                && packet.dst_ip == Ipv4Addr::LOCALHOST
                && packet.dst_port == server_port
                && packet.tcp_flags.is_some_and(|flags| flags & 0x12 == 0x02)
        })
        .ok_or("missing upstream SYN")?;
    let outbound = |packet: &CapturedPacket| {
        packet.protocol == 6
            && (packet.src_ip, packet.dst_ip, packet.src_port, packet.dst_port)
                == (syn.src_ip, syn.dst_ip, syn.src_port, syn.dst_port)
    };
    let inbound = |packet: &CapturedPacket| {
        packet.protocol == 6
            && (packet.src_ip, packet.dst_ip, packet.src_port, packet.dst_port)
                == (syn.dst_ip, syn.src_ip, syn.dst_port, syn.src_port)
    };
    let syn_ack = packets
        .iter()
        .find(|packet| inbound(packet) && packet.tcp_flags.is_some_and(|flags| flags & 0x12 == 0x12))
        .ok_or("missing upstream SYN-ACK")?;
    let offered = tcp_window_scale(syn.tcp_options.as_deref().unwrap_or_default())?;
    let accepted = tcp_window_scale(syn_ack.tcp_options.as_deref().unwrap_or_default())?;
    let scale = if accepted.is_some() { offered.unwrap_or(0) } else { 0 };
    let initial_window = u32::from(syn.tcp_window.ok_or("missing SYN window")?);
    let first_peer_sequence = syn_ack.tcp_sequence_number.ok_or("missing SYN-ACK sequence")?.wrapping_add(1);
    let mut peer_progress = 0;
    let mut windows = Vec::new();
    for packet in packets {
        if inbound(packet) && !packet.payload.is_empty() {
            let sequence = packet.tcp_sequence_number.ok_or("missing peer sequence")?;
            let length = u32::try_from(packet.payload.len()).map_err(|_| "oversized captured payload")?;
            // A loopback capture can contain duplicate frames; use sequence progress, not byte sums.
            peer_progress = peer_progress.max(sequence.wrapping_add(length).wrapping_sub(first_peer_sequence));
        }
        if outbound(packet)
            && peer_progress > initial_window
            && packet.tcp_flags.is_some_and(|flags| flags & 0x17 == 0x10)
            && let Some(window @ 1..) = packet.tcp_window
        {
            windows.push(u32::from(window) << scale);
        }
    }
    Ok((scale, windows))
}

/// Checks every observed window so one transient small value cannot certify a clamp.
pub fn effective_windows_respect_clamp(control: &[u32], clamped: &[u32], requested: u32, scale: u8) -> bool {
    if scale > 14 || clamped.is_empty() {
        return false;
    }
    let Some(control_max) = control.iter().copied().max() else { return false };
    let bound = requested.saturating_add((1u32 << scale) - 1);
    clamped.iter().all(|&window| window > 0 && window <= bound && window < control_max)
}

// ── AF_PACKET loopback capture (Linux only) ──

#[cfg(target_os = "linux")]
pub mod capture {
    use std::io;
    use std::mem;
    use std::net::Ipv4Addr;
    use std::os::unix::io::RawFd;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread::{self, JoinHandle};

    use super::CapturedPacket;

    /// Captures IP packets on the loopback interface via AF_PACKET.
    ///
    /// Requires `CAP_NET_RAW` privilege.
    pub struct LoopbackCapture {
        fd: RawFd,
        stop: Arc<AtomicBool>,
        packets: Arc<Mutex<Vec<CapturedPacket>>>,
        handle: Option<JoinHandle<()>>,
    }

    impl LoopbackCapture {
        /// Start capturing packets that involve `filter_port` (src or dst).
        pub fn start(filter_port: u16) -> io::Result<Self> {
            // SAFETY: all arguments are valid constants; fd validity is checked immediately after.
            let fd = unsafe { libc::socket(libc::AF_PACKET, libc::SOCK_DGRAM, (libc::ETH_P_IP as u16).to_be() as i32) };
            if fd < 0 {
                return Err(io::Error::last_os_error());
            }

            // Bind to loopback interface
            // SAFETY: "lo\0" is a valid C string literal; the returned index is validated before use.
            let lo_index = unsafe { libc::if_nametoindex(c"lo".as_ptr()) };
            if lo_index == 0 {
                // SAFETY: fd is valid (checked above) and we own it exclusively at this point.
                unsafe { libc::close(fd) };
                return Err(io::Error::new(io::ErrorKind::NotFound, "loopback interface 'lo' not found"));
            }

            // SAFETY: sockaddr_ll is a POD type; zeroing it produces a valid zero-initialised value.
            let mut addr: libc::sockaddr_ll = unsafe { mem::zeroed() };
            addr.sll_family = libc::AF_PACKET as u16;
            addr.sll_protocol = (libc::ETH_P_IP as u16).to_be();
            addr.sll_ifindex = lo_index as i32;

            // SAFETY: fd is valid, addr is fully initialised above, and size matches the struct.
            let rc = unsafe {
                libc::bind(
                    fd,
                    (&addr as *const libc::sockaddr_ll).cast::<libc::sockaddr>(),
                    mem::size_of::<libc::sockaddr_ll>() as libc::socklen_t,
                )
            };
            if rc < 0 {
                let err = io::Error::last_os_error();
                // SAFETY: fd is valid and we own it exclusively at this point.
                unsafe { libc::close(fd) };
                return Err(err);
            }

            // Set recv timeout so the thread can check stop flag
            let tv = libc::timeval { tv_sec: 0, tv_usec: 200_000 };
            // SAFETY: fd is valid, tv is a fully initialised stack variable, and size matches the struct.
            unsafe {
                libc::setsockopt(
                    fd,
                    libc::SOL_SOCKET,
                    libc::SO_RCVTIMEO,
                    (&tv as *const libc::timeval).cast::<libc::c_void>(),
                    mem::size_of::<libc::timeval>() as libc::socklen_t,
                );
            }

            let stop = Arc::new(AtomicBool::new(false));
            let packets: Arc<Mutex<Vec<CapturedPacket>>> = Arc::new(Mutex::new(Vec::new()));

            let stop_flag = stop.clone();
            let pkt_log = packets.clone();
            let handle = thread::spawn(move || {
                let mut buf = [0u8; 65536];
                while !stop_flag.load(Ordering::Relaxed) {
                    // SAFETY: fd is valid for the lifetime of this thread; buf is a valid writable buffer of the given length.
                    let read = unsafe { libc::recv(fd, buf.as_mut_ptr().cast::<libc::c_void>(), buf.len(), 0) };
                    if read <= 0 {
                        continue;
                    }
                    let data = &buf[..read as usize];
                    if let Some(pkt) = parse_ip_packet(data, filter_port) {
                        pkt_log.lock().expect("lock packets").push(pkt);
                    }
                }
                // SAFETY: fd is valid and this is the sole owner at thread-exit (Drop has set stop=true and called shutdown first).
                unsafe { libc::close(fd) };
            });

            Ok(Self { fd, stop, packets, handle: Some(handle) })
        }

        /// Return all captured packets filtered to the given dst_port.
        pub fn packets_to_port(&self, port: u16) -> Vec<CapturedPacket> {
            self.packets.lock().expect("lock packets").iter().filter(|p| p.dst_port == port).cloned().collect()
        }

        /// Return all captured packets.
        #[allow(dead_code)]
        pub fn snapshot(&self) -> Vec<CapturedPacket> {
            self.packets.lock().expect("lock packets").clone()
        }
    }

    impl Drop for LoopbackCapture {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            // Shutdown the socket to unblock recv
            // SAFETY: self.fd is a valid socket fd that we own and have not yet closed.
            unsafe { libc::shutdown(self.fd, libc::SHUT_RDWR) };
            if let Some(handle) = self.handle.take() {
                let _ = handle.join();
            }
        }
    }

    /// Parse an IP packet (starting from IP header), filtering by port.
    fn parse_ip_packet(data: &[u8], filter_port: u16) -> Option<CapturedPacket> {
        if data.len() < 20 {
            return None;
        }

        let version = (data[0] >> 4) & 0x0f;
        if version != 4 {
            return None;
        }

        let ihl = (data[0] & 0x0f) as usize * 4;
        if ihl < 20 || data.len() < ihl {
            return None;
        }

        let protocol = data[9];
        let ttl = data[8];
        let src_ip = Ipv4Addr::new(data[12], data[13], data[14], data[15]);
        let dst_ip = Ipv4Addr::new(data[16], data[17], data[18], data[19]);
        let transport = &data[ihl..];

        match protocol {
            6 => parse_tcp_packet(transport, ttl, filter_port, src_ip, dst_ip),
            17 => parse_udp_packet(transport, ttl, filter_port, src_ip, dst_ip),
            _ => None,
        }
    }

    fn parse_tcp_packet(
        data: &[u8],
        ttl: u8,
        filter_port: u16,
        src_ip: Ipv4Addr,
        dst_ip: Ipv4Addr,
    ) -> Option<CapturedPacket> {
        if data.len() < 20 {
            return None;
        }

        let src_port = u16::from_be_bytes([data[0], data[1]]);
        let dst_port = u16::from_be_bytes([data[2], data[3]]);
        let sequence_number = u32::from_be_bytes([data[4], data[5], data[6], data[7]]);

        if src_port != filter_port && dst_port != filter_port {
            return None;
        }

        let data_offset = ((data[12] >> 4) & 0x0f) as usize * 4;
        let flags = data[13];
        let window = u16::from_be_bytes([data[14], data[15]]);

        let options =
            if data_offset > 20 && data.len() >= data_offset { Some(data[20..data_offset].to_vec()) } else { None };

        let payload = if data.len() > data_offset { data[data_offset..].to_vec() } else { Vec::new() };

        Some(CapturedPacket {
            protocol: 6,
            src_ip,
            dst_ip,
            src_port,
            dst_port,
            ttl,
            tcp_window: Some(window),
            tcp_flags: Some(flags),
            tcp_sequence_number: Some(sequence_number),
            tcp_options: options,
            payload,
        })
    }

    fn parse_udp_packet(
        data: &[u8],
        ttl: u8,
        filter_port: u16,
        src_ip: Ipv4Addr,
        dst_ip: Ipv4Addr,
    ) -> Option<CapturedPacket> {
        if data.len() < 8 {
            return None;
        }

        let src_port = u16::from_be_bytes([data[0], data[1]]);
        let dst_port = u16::from_be_bytes([data[2], data[3]]);

        if src_port != filter_port && dst_port != filter_port {
            return None;
        }

        let payload = if data.len() > 8 { data[8..].to_vec() } else { Vec::new() };

        Some(CapturedPacket {
            protocol: 17,
            src_ip,
            dst_ip,
            src_port,
            dst_port,
            ttl,
            tcp_window: None,
            tcp_flags: None,
            tcp_sequence_number: None,
            tcp_options: None,
            payload,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clamp_comparison_rejects_transient_small_windows() {
        assert!(effective_windows_respect_clamp(&[65536], &[8192, 7168], 8192, 0));
        assert!(!effective_windows_respect_clamp(&[65536], &[8192, 65536], 8192, 0));
        assert!(!effective_windows_respect_clamp(&[65536], &[0], 8192, 0));
        assert!(!effective_windows_respect_clamp(&[65536], &[], 8192, 0));
        assert!(!effective_windows_respect_clamp(&[], &[8192], 8192, 0));
        assert!(!effective_windows_respect_clamp(&[65536], &[8192], 8192, 15));
    }

    #[test]
    fn window_scale_options_validate_lengths_and_cap_the_exponent() {
        assert_eq!(tcp_window_scale(&[2, 4, 0x05, 0xb4, 1, 3, 3, 10, 0]), Ok(Some(10)));
        assert_eq!(tcp_window_scale(&[3, 3, 255]), Ok(Some(14)));
        assert_eq!(tcp_window_scale(&[0, 3, 3, 10]), Ok(None));
        for options in [&[3, 2][..], &[3, 3][..], &[2, 0][..], &[3, 3, 2, 3, 3, 2][..]] {
            assert!(tcp_window_scale(options).is_err(), "malformed options: {options:?}");
        }
    }

    fn window_packet(outbound: bool, flags: u8, sequence: u32, window: u16, options: &[u8]) -> CapturedPacket {
        CapturedPacket {
            protocol: 6,
            src_ip: Ipv4Addr::LOCALHOST,
            dst_ip: Ipv4Addr::LOCALHOST,
            src_port: if outbound { 41000 } else { 42000 },
            dst_port: if outbound { 42000 } else { 41000 },
            ttl: 64,
            tcp_window: Some(window),
            tcp_flags: Some(flags),
            tcp_sequence_number: Some(sequence),
            tcp_options: Some(options.to_vec()),
            payload: Vec::new(),
        }
    }

    fn window_handshake() -> Vec<CapturedPacket> {
        vec![
            window_packet(true, 0x02, 100, 1, &[3, 3, 10]),
            window_packet(false, 0x12, 200, 65535, &[3, 3, 10]),
            window_packet(true, 0x10, 101, 64, &[]),
        ]
    }

    fn peer_byte(sequence: u32) -> CapturedPacket {
        let mut packet = window_packet(false, 0x10, sequence, 65535, &[]);
        packet.payload.push(42);
        packet
    }

    #[test]
    fn receive_windows_use_negotiated_scale_after_the_initial_edge() {
        let mut packets = window_handshake();
        packets.push(peer_byte(201));
        packets.push(window_packet(true, 0x10, 101, 64, &[]));
        assert_eq!(tcp_receive_windows_after_initial_edge(&packets, 42000), Ok((10, Vec::new())));
        packets.push(peer_byte(202));
        packets.push(window_packet(true, 0x10, 101, 64, &[]));
        // RFC 7323: 64 * 2^10 = 65536 bytes; this is not a tiny window.
        assert_eq!(tcp_receive_windows_after_initial_edge(&packets, 42000), Ok((10, vec![65536])));
        packets[1].tcp_options = None;
        assert_eq!(tcp_receive_windows_after_initial_edge(&packets, 42000), Ok((0, vec![64])));
    }

    #[test]
    fn receive_windows_exclude_zero_close_and_other_flows() {
        let mut packets = window_handshake();
        packets.extend([peer_byte(201), peer_byte(202)]);
        for (flags, window) in [(0x10, 0), (0x11, 64), (0x14, 64), (0x12, 64)] {
            packets.push(window_packet(true, flags, 101, window, &[]));
        }
        let mut other_ip = window_packet(true, 0x10, 101, 64, &[]);
        other_ip.src_ip = Ipv4Addr::new(127, 0, 0, 2);
        packets.push(other_ip);
        let mut other_port = window_packet(true, 0x10, 101, 64, &[]);
        other_port.src_port += 1;
        packets.push(other_port);
        assert_eq!(tcp_receive_windows_after_initial_edge(&packets, 42000), Ok((10, Vec::new())));
    }

    #[test]
    fn duplicate_peer_frames_do_not_exhaust_the_initial_window() {
        let mut packets = window_handshake();
        packets[0].tcp_window = Some(2);
        packets.extend([peer_byte(201), peer_byte(201), peer_byte(201)]);
        packets.push(window_packet(true, 0x10, 101, 64, &[]));
        assert_eq!(tcp_receive_windows_after_initial_edge(&packets, 42000), Ok((10, Vec::new())));
        packets.retain(|packet| packet.tcp_flags != Some(0x12));
        assert_eq!(tcp_receive_windows_after_initial_edge(&packets, 42000), Err("missing upstream SYN-ACK"));
    }

    #[test]
    fn tcp_options_find_timestamps_kind_8() {
        // NOP NOP Timestamps(kind=8, len=10, ...)
        let opts = [1, 1, 8, 10, 0, 0, 0, 0, 0, 0, 0, 0];
        assert!(tcp_options_contain_kind(&opts, 8));
        assert!(!tcp_options_contain_kind(&opts, 5));
    }

    #[test]
    fn tcp_options_find_sack_kind_5() {
        // SACK-Permitted(kind=4, len=2) SACK(kind=5, len=10, ...)
        let opts = [4, 2, 5, 10, 0, 0, 0, 0, 0, 0, 0, 0];
        assert!(tcp_options_contain_kind(&opts, 5));
        assert!(tcp_options_contain_kind(&opts, 4));
        assert!(!tcp_options_contain_kind(&opts, 8));
    }

    #[test]
    fn tcp_options_empty_returns_false() {
        assert!(!tcp_options_contain_kind(&[], 8));
    }

    #[test]
    fn tcp_options_eol_terminates_early() {
        // EOL before kind=8
        let opts = [0, 8, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        assert!(!tcp_options_contain_kind(&opts, 8));
    }
}
