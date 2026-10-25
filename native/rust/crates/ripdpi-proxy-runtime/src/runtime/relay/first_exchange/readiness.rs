use std::io;
use std::net::TcpStream;
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use crate::runtime::state::RuntimeState;

#[derive(PartialEq, Eq)]
pub(super) enum Readiness {
    Client,
    Upstream,
    Timeout,
}

pub(super) fn wait_for_readiness(
    state: &RuntimeState,
    client: Option<&TcpStream>,
    upstream: &TcpStream,
    deadline: Option<Instant>,
) -> io::Result<Readiness> {
    const EVENTS: libc::c_short = libc::POLLIN | libc::POLLERR | libc::POLLHUP | libc::POLLNVAL;
    loop {
        if state.shutdown_requested() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "proxy stopped during first response"));
        }
        let remaining = deadline.map(|deadline| deadline.saturating_duration_since(Instant::now()));
        // This heartbeat checks owner cancellation; it does not change a response deadline.
        let timeout = remaining.unwrap_or(Duration::from_millis(100)).min(Duration::from_millis(100));
        let millis = if timeout.is_zero() { 0 } else { timeout.as_millis().max(1) as libc::c_int };
        let mut descriptors = [
            libc::pollfd { fd: upstream.as_raw_fd(), events: EVENTS, revents: 0 },
            libc::pollfd { fd: client.map_or(-1, AsRawFd::as_raw_fd), events: EVENTS, revents: 0 },
        ];
        // SAFETY: both descriptors refer to borrowed live sockets. `poll` receives
        // the exact length of this initialized mutable array and does not retain it.
        let ready = unsafe { libc::poll(descriptors.as_mut_ptr(), descriptors.len() as libc::nfds_t, millis) };
        if ready < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        // Preserve response classification when both directions become ready.
        if descriptors[0].revents & EVENTS != 0 {
            return Ok(Readiness::Upstream);
        }
        if descriptors[1].revents & EVENTS != 0 {
            return Ok(Readiness::Client);
        }
        if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Ok(Readiness::Timeout);
        }
    }
}
