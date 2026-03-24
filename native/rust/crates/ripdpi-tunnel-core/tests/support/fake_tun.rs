//! Fake TUN device backed by a `SOCK_DGRAM` unix socketpair.
//!
//! Each `send()`/`recv()` delivers exactly one IP packet, matching real TUN
//! behaviour where each `read()` returns one complete IP packet.

use std::io;
use std::os::unix::io::{FromRawFd, RawFd};
use std::os::unix::net::UnixDatagram;
use std::time::Duration;

/// Create a `SOCK_DGRAM` unix socketpair suitable for simulating a TUN fd.
///
/// Returns `(tunnel_fd, harness)` where:
/// - `tunnel_fd` is a raw fd to pass to `run_tunnel()` (ownership transfers)
/// - `harness` wraps the other end for test packet injection/capture
pub fn socketpair_tun() -> io::Result<(RawFd, FakeTunHarness)> {
    let mut fds = [0i32; 2];
    let ret = unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_DGRAM, 0, fds.as_mut_ptr()) };
    if ret != 0 {
        return Err(io::Error::last_os_error());
    }

    let tunnel_fd = fds[0];
    // Wrap the harness-side fd in UnixDatagram for ergonomic I/O.
    let harness_sock = unsafe { UnixDatagram::from_raw_fd(fds[1]) };

    Ok((tunnel_fd, FakeTunHarness { sock: harness_sock }))
}

/// Test-side wrapper for the harness end of the socketpair.
///
/// Provides ergonomic methods to inject and capture raw IP packets that flow
/// through the tunnel under test.
pub struct FakeTunHarness {
    sock: UnixDatagram,
}

impl FakeTunHarness {
    /// Send a raw IP packet into the tunnel (appears as if read from TUN fd).
    pub fn inject_packet(&self, pkt: &[u8]) -> io::Result<()> {
        self.sock.send(pkt)?;
        Ok(())
    }

    /// Read a raw IP packet produced by the tunnel, with a timeout.
    ///
    /// Returns `Err(TimedOut)` if no packet arrives within `timeout`.
    pub fn recv_packet(&self, timeout: Duration) -> io::Result<Vec<u8>> {
        self.sock.set_read_timeout(Some(timeout))?;
        let mut buf = vec![0u8; 65536];
        let n = self.sock.recv(&mut buf)?;
        buf.truncate(n);
        Ok(buf)
    }

    /// Drain all pending packets (non-blocking). Returns collected packets.
    pub fn drain(&self) -> Vec<Vec<u8>> {
        self.sock
            .set_nonblocking(true)
            .expect("set_nonblocking on harness socket");
        let mut packets = Vec::new();
        let mut buf = vec![0u8; 65536];
        loop {
            match self.sock.recv(&mut buf) {
                Ok(n) => packets.push(buf[..n].to_vec()),
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }
        self.sock
            .set_nonblocking(false)
            .expect("restore blocking on harness socket");
        packets
    }
}
