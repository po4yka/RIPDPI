use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime_adapter::platform::connect as connect_platform;

use super::super::super::state::{RouteConnectPolicy, RuntimeState};
use super::error::ConnectAttemptError;

pub(super) fn apply_group_socket_options(
    stream: &TcpStream,
    policy: &RouteConnectPolicy,
) -> Result<(), ConnectAttemptError> {
    if policy.drop_sack {
        connect_platform::attach_drop_sack(stream).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: policy.tfo_enabled,
        })?;
    }
    if let Some(clamp) = policy.window_clamp {
        let _ = connect_platform::set_tcp_window_clamp(stream, clamp);
    }
    // SO_ATTACH_FILTER replaces the socket's filter, so a second program would silently
    // remove the drop-SACK one; keep drop-SACK and report the skipped timestamp filter.
    if policy.strip_timestamps && policy.drop_sack {
        tracing::warn!("strip_timestamps ignored: it cannot share the socket filter with drop_sack");
    } else if policy.strip_timestamps {
        let _ = connect_platform::attach_strip_timestamps(stream);
    }
    Ok(())
}

/// A deferred TCP Fast Open connect returns before any SYN. Bound the first write, which
/// sends the SYN, by the connect timeout; the relay clears the bound after that write.
pub(super) fn bound_deferred_first_write(stream: &TcpStream, connect_timeout: Option<Duration>) {
    let deferred = stream.peer_addr().is_err_and(|err| err.kind() == std::io::ErrorKind::NotConnected);
    if let (true, Some(timeout)) = (deferred, connect_timeout)
        && let Err(err) = stream.set_write_timeout(Some(timeout))
    {
        tracing::debug!("set write timeout on deferred TFO socket failed (non-fatal): {err}");
    }
}

pub(super) fn record_connect_telemetry(
    state: &RuntimeState,
    stream: &TcpStream,
    target: SocketAddr,
    group_index: usize,
    started: Instant,
) {
    // A deferred TCP Fast Open socket has sent no SYN yet: no handshake time exists.
    let peer = stream.peer_addr();
    let deferred = peer.as_ref().is_err_and(|err| err.kind() == std::io::ErrorKind::NotConnected);
    if !deferred {
        connect_platform::record_connection_setup_duration(group_index, started.elapsed().as_secs_f64());
    }
    let upstream_addr = peer.unwrap_or(target);
    let upstream_rtt_ms = if deferred {
        None
    } else {
        connect_platform::tcp_round_trip_time_ms(stream)
            .ok()
            .flatten()
            .or_else(|| Some(started.elapsed().as_millis() as u64))
    };
    state.note_upstream_connected(upstream_addr, upstream_rtt_ms);
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use std::net::{Ipv4Addr, TcpListener};
    use std::os::fd::AsRawFd;

    use super::*;

    fn connected_stream() -> (TcpStream, TcpListener) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let stream = TcpStream::connect(listener.local_addr().expect("listener addr")).expect("connect");
        (stream, listener)
    }

    /// Kernel PoC S3 (DPI-02): with a cached cookie, connect() returns before any SYN and
    /// the first blocking write waits for the whole SYN retry schedule when the server's
    /// accept queue is full. The send timeout must cut that wait at the connect timeout.
    #[test]
    fn deferred_tfo_first_write_times_out_with_the_connect_timeout() {
        use std::io::Write;
        use std::time::Duration;

        let sysctl = std::fs::read_to_string("/proc/sys/net/ipv4/tcp_fastopen").unwrap_or_default();
        if !sysctl.trim().parse::<u32>().is_ok_and(|bits| bits & 0x3 == 0x3) {
            eprintln!("skipped: net.ipv4.tcp_fastopen needs client and server bits (0x3), got {sysctl:?}");
            return;
        }
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let target = listener.local_addr().expect("listener addr");
        let qlen: libc::c_int = 16;
        // SAFETY: the listener fd is live; `qlen` is a valid c_int for the option.
        let rc = unsafe {
            libc::setsockopt(
                listener.as_raw_fd(),
                libc::IPPROTO_TCP,
                libc::TCP_FASTOPEN,
                (&raw const qlen).cast(),
                std::mem::size_of::<libc::c_int>() as libc::socklen_t,
            )
        };
        assert_eq!(rc, 0, "TCP_FASTOPEN on listener: {}", std::io::Error::last_os_error());

        let timeout = Duration::from_millis(500);
        let connect = || {
            super::super::socket::connect_socket_detailed(
                target,
                Ipv4Addr::UNSPECIFIED.into(),
                None,
                true,
                Some(timeout),
                None,
            )
        };
        // The first TFO connection requests a cookie; the kernel caches it for the next one.
        let mut primer = connect().expect("cookie-request connect");
        primer.write_all(b"x").expect("write on cookie-request connection");
        drop(listener.accept().expect("accept primer"));
        drop(primer);

        // Backlog 0 plus connections nobody accepts leave no room in the accept queue.
        // SAFETY: re-listening on a live listener fd only changes its backlog.
        assert_eq!(unsafe { libc::listen(listener.as_raw_fd(), 0) }, 0);
        let _fillers: Vec<TcpStream> =
            (0..3).filter_map(|_| TcpStream::connect_timeout(&target, Duration::from_millis(200)).ok()).collect();

        let mut stream = connect().expect("deferred connect returns without a SYN");
        if stream.peer_addr().is_ok() {
            eprintln!("skipped: no cookie cached, the connect was not deferred");
            return;
        }
        bound_deferred_first_write(&stream, Some(timeout));
        let started = Instant::now();
        let err = stream.write(b"hello").expect_err("first write must not complete");
        let elapsed = started.elapsed();
        assert!(matches!(err.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut), "{err}");
        assert!(elapsed < timeout + Duration::from_millis(500), "first write blocked for {elapsed:?}");
    }

    /// Reads back the attached classic BPF program (SO_GET_FILTER shares the
    /// SO_ATTACH_FILTER option number). The kernel takes and returns the length in
    /// instructions, and returns 0 on success.
    fn attached_filter(stream: &TcpStream) -> Vec<libc::sock_filter> {
        let mut insns = [libc::sock_filter { code: 0, jt: 0, jf: 0, k: 0 }; 16];
        let mut len = insns.len() as libc::socklen_t;
        // SAFETY: `stream` owns a live socket fd for the call; `insns` is a writable
        // buffer of `len` sock_filter entries, which is the unit SO_GET_FILTER uses.
        let rc = unsafe {
            libc::getsockopt(
                stream.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_ATTACH_FILTER,
                insns.as_mut_ptr().cast(),
                &mut len,
            )
        };
        assert_eq!(rc, 0, "SO_GET_FILTER: {}", std::io::Error::last_os_error());
        insns[..len as usize].to_vec()
    }

    fn policy(drop_sack: bool, strip_timestamps: bool) -> RouteConnectPolicy {
        RouteConnectPolicy {
            tfo_enabled: false,
            upstream_socks_addr: None,
            pre_connect_rcvbuf: None,
            connect_timeout: None,
            protect_path: None,
            drop_sack,
            window_clamp: None,
            strip_timestamps,
        }
    }

    #[test]
    fn strip_timestamps_does_not_replace_the_drop_sack_filter() {
        let (both, _listener) = connected_stream();

        apply_group_socket_options(&both, &policy(true, true)).expect("both filters requested");

        // drop-SACK checks data offset >= 0x0b and option byte 0x22; the timestamp
        // program checks 0x08 and 0x14 instead, so these k values tell them apart.
        let filter = attached_filter(&both);
        assert_eq!(filter.len(), 7, "a filter must stay attached");
        assert_eq!((filter[2].k, filter[3].k), (0x0b, 0x22), "drop-SACK must stay attached");
    }
}
