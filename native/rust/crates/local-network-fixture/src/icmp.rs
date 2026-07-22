use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};

use crate::event::EventLog;
use crate::types::{FixtureEvent, IO_POLL_DELAY};

const ICMP_HEADER_LEN: usize = 8;
const IPV4_MIN_HEADER_LEN: usize = 20;
const IPV6_HEADER_LEN: usize = 40;
const ICMPV4_ECHO_REQUEST: u8 = 8;
const ICMPV6_ECHO_REQUEST: u8 = 128;
const PHYSICAL_MARKER_PREFIX: &[u8] = b"ripdpi-so-bind-icmp-";
const MAX_MARKER_LEN: usize = 96;

pub(crate) fn start_icmp_observer(ipv6: bool, stop: Arc<AtomicBool>, events: EventLog) -> io::Result<JoinHandle<()>> {
    let socket = RawIcmpSocket::open(ipv6)?;
    let thread_name = if ipv6 { "fixture-icmp6" } else { "fixture-icmp4" };
    thread::Builder::new().name(thread_name.to_string()).spawn(move || {
        let mut packet = [0_u8; 2_048];
        while !stop.load(Ordering::Relaxed) {
            match socket.receive(&mut packet) {
                Ok(length) => record_echo_request(&packet[..length], ipv6, &events),
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
                Err(_) => break,
            }
        }
    })
}

fn record_echo_request(packet: &[u8], ipv6: bool, events: &EventLog) {
    let Some(marker) = echo_request_marker(packet, ipv6) else {
        return;
    };
    events.record(FixtureEvent {
        service: "icmp_observer".to_string(),
        protocol: if ipv6 { "icmpv6" } else { "icmpv4" }.to_string(),
        peer: "redacted".to_string(),
        target: "redacted".to_string(),
        detail: marker,
        bytes: packet.len(),
        sni: None,
        created_at: crate::event::now_ms(),
    });
}

fn echo_request_marker(packet: &[u8], ipv6: bool) -> Option<String> {
    let icmp_offset = if ipv6 {
        if packet.first().is_some_and(|first| first >> 4 == 6) { IPV6_HEADER_LEN } else { 0 }
    } else {
        let first = *packet.first()?;
        if first >> 4 != 4 {
            return None;
        }
        usize::from(first & 0x0f) * 4
    };
    let expected_type = if ipv6 { ICMPV6_ECHO_REQUEST } else { ICMPV4_ECHO_REQUEST };
    if icmp_offset < if ipv6 { 0 } else { IPV4_MIN_HEADER_LEN } || packet.get(icmp_offset).copied()? != expected_type {
        return None;
    }
    let payload = packet.get(icmp_offset + ICMP_HEADER_LEN..)?;
    let marker_length = payload.len().min(MAX_MARKER_LEN);
    let marker = payload.get(..marker_length)?;
    if !marker.starts_with(PHYSICAL_MARKER_PREFIX) || !marker.iter().all(u8::is_ascii_graphic) {
        return None;
    }
    String::from_utf8(marker.to_vec()).ok()
}

#[cfg(target_os = "linux")]
struct RawIcmpSocket(i32);

#[cfg(target_os = "linux")]
impl RawIcmpSocket {
    fn open(ipv6: bool) -> io::Result<Self> {
        const AF_INET: i32 = 2;
        const AF_INET6: i32 = 10;
        const SOCK_RAW: i32 = 3;
        const SOCK_NONBLOCK: i32 = 0x800;
        const SOCK_CLOEXEC: i32 = 0x80000;
        const IPPROTO_ICMP: i32 = 1;
        const IPPROTO_ICMPV6: i32 = 58;

        unsafe extern "C" {
            fn socket(domain: i32, socket_type: i32, protocol: i32) -> i32;
        }

        // SAFETY: `socket(2)` receives Linux ABI integer constants only and
        // returns a fresh descriptor or -1 without retaining Rust pointers.
        let fd = unsafe {
            socket(
                if ipv6 { AF_INET6 } else { AF_INET },
                SOCK_RAW | SOCK_NONBLOCK | SOCK_CLOEXEC,
                if ipv6 { IPPROTO_ICMPV6 } else { IPPROTO_ICMP },
            )
        };
        if fd < 0 { Err(io::Error::last_os_error()) } else { Ok(Self(fd)) }
    }

    fn receive(&self, packet: &mut [u8]) -> io::Result<usize> {
        unsafe extern "C" {
            fn recv(fd: i32, buffer: *mut core::ffi::c_void, length: usize, flags: i32) -> isize;
        }

        // SAFETY: `self.0` is a live owned socket descriptor; `packet` exposes
        // writable initialized storage for exactly `packet.len()` bytes and
        // remains exclusively borrowed for the duration of `recv(2)`.
        let received = unsafe { recv(self.0, packet.as_mut_ptr().cast(), packet.len(), 0) };
        if received < 0 { Err(io::Error::last_os_error()) } else { Ok(received as usize) }
    }
}

#[cfg(target_os = "linux")]
impl Drop for RawIcmpSocket {
    fn drop(&mut self) {
        unsafe extern "C" {
            fn close(fd: i32) -> i32;
        }
        // SAFETY: `self.0` is uniquely owned by this RAII wrapper and is closed
        // exactly once here. Close failure requires no recovery during Drop.
        let _ = unsafe { close(self.0) };
    }
}

#[cfg(not(target_os = "linux"))]
struct RawIcmpSocket;

#[cfg(not(target_os = "linux"))]
impl RawIcmpSocket {
    fn open(_ipv6: bool) -> io::Result<Self> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "raw ICMP observer requires Linux"))
    }

    fn receive(&self, _packet: &mut [u8]) -> io::Result<usize> {
        let _ = self;
        Err(io::Error::new(io::ErrorKind::Unsupported, "raw ICMP observer requires Linux"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_redacted_ipv4_and_ipv6_markers() {
        let marker = b"ripdpi-so-bind-icmp-direct-ipv4-0123456789ab";
        let mut ipv4 = vec![0_u8; IPV4_MIN_HEADER_LEN + ICMP_HEADER_LEN];
        ipv4[0] = 0x45;
        ipv4[IPV4_MIN_HEADER_LEN] = ICMPV4_ECHO_REQUEST;
        ipv4.extend_from_slice(marker);
        assert_eq!(echo_request_marker(&ipv4, false).as_deref(), Some("ripdpi-so-bind-icmp-direct-ipv4-0123456789ab"));

        let marker = b"ripdpi-so-bind-icmp-denied-ipv6-0123456789ab";
        let mut ipv6 = vec![0_u8; ICMP_HEADER_LEN];
        ipv6[0] = ICMPV6_ECHO_REQUEST;
        ipv6.extend_from_slice(marker);
        assert_eq!(echo_request_marker(&ipv6, true).as_deref(), Some("ripdpi-so-bind-icmp-denied-ipv6-0123456789ab"));
    }

    #[test]
    fn ignores_replies_and_non_marker_payloads() {
        let mut reply = vec![0_u8; IPV4_MIN_HEADER_LEN + ICMP_HEADER_LEN];
        reply[0] = 0x45;
        assert_eq!(echo_request_marker(&reply, false), None);

        let mut unrelated = vec![0_u8; ICMP_HEADER_LEN];
        unrelated[0] = ICMPV6_ECHO_REQUEST;
        unrelated.extend_from_slice(b"unrelated-user-traffic");
        assert_eq!(echo_request_marker(&unrelated, true), None);
    }
}
