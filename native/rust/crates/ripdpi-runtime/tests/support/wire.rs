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

// ── AF_PACKET loopback capture (Linux only) ──

#[cfg(target_os = "linux")]
pub mod capture {
    use std::io;
    use std::mem;
    use std::os::unix::io::RawFd;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread::{self, JoinHandle};

    /// A captured IP packet with parsed header fields.
    #[derive(Debug, Clone)]
    pub struct CapturedPacket {
        pub protocol: u8,
        pub src_port: u16,
        pub dst_port: u16,
        pub ttl: u8,
        pub tcp_window: Option<u16>,
        pub tcp_flags: Option<u8>,
        pub tcp_options: Option<Vec<u8>>,
        pub payload: Vec<u8>,
    }

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
            let fd = unsafe { libc::socket(libc::AF_PACKET, libc::SOCK_DGRAM, (libc::ETH_P_IP as u16).to_be() as i32) };
            if fd < 0 {
                return Err(io::Error::last_os_error());
            }

            // Bind to loopback interface
            let lo_index = unsafe { libc::if_nametoindex(c"lo".as_ptr()) };
            if lo_index == 0 {
                unsafe { libc::close(fd) };
                return Err(io::Error::new(io::ErrorKind::NotFound, "loopback interface 'lo' not found"));
            }

            let mut addr: libc::sockaddr_ll = unsafe { mem::zeroed() };
            addr.sll_family = libc::AF_PACKET as u16;
            addr.sll_protocol = (libc::ETH_P_IP as u16).to_be();
            addr.sll_ifindex = lo_index as i32;

            let rc = unsafe {
                libc::bind(
                    fd,
                    &addr as *const libc::sockaddr_ll as *const libc::sockaddr,
                    mem::size_of::<libc::sockaddr_ll>() as libc::socklen_t,
                )
            };
            if rc < 0 {
                let err = io::Error::last_os_error();
                unsafe { libc::close(fd) };
                return Err(err);
            }

            // Set recv timeout so the thread can check stop flag
            let tv = libc::timeval { tv_sec: 0, tv_usec: 200_000 };
            unsafe {
                libc::setsockopt(
                    fd,
                    libc::SOL_SOCKET,
                    libc::SO_RCVTIMEO,
                    &tv as *const libc::timeval as *const libc::c_void,
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
                    let read = unsafe { libc::recv(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len(), 0) };
                    if read <= 0 {
                        continue;
                    }
                    let data = &buf[..read as usize];
                    if let Some(pkt) = parse_ip_packet(data, filter_port) {
                        pkt_log.lock().expect("lock packets").push(pkt);
                    }
                }
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
        let transport = &data[ihl..];

        match protocol {
            6 => parse_tcp_packet(transport, ttl, filter_port),
            17 => parse_udp_packet(transport, ttl, filter_port),
            _ => None,
        }
    }

    fn parse_tcp_packet(data: &[u8], ttl: u8, filter_port: u16) -> Option<CapturedPacket> {
        if data.len() < 20 {
            return None;
        }

        let src_port = u16::from_be_bytes([data[0], data[1]]);
        let dst_port = u16::from_be_bytes([data[2], data[3]]);

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
            src_port,
            dst_port,
            ttl,
            tcp_window: Some(window),
            tcp_flags: Some(flags),
            tcp_options: options,
            payload,
        })
    }

    fn parse_udp_packet(data: &[u8], ttl: u8, filter_port: u16) -> Option<CapturedPacket> {
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
            src_port,
            dst_port,
            ttl,
            tcp_window: None,
            tcp_flags: None,
            tcp_options: None,
            payload,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
