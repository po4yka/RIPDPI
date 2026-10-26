use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

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

pub(super) fn record_connect_telemetry(
    state: &RuntimeState,
    stream: &TcpStream,
    target: SocketAddr,
    group_index: usize,
    started: Instant,
) {
    let elapsed = started.elapsed().as_secs_f64();
    connect_platform::record_connection_setup_duration(group_index, elapsed);
    let upstream_addr = stream.peer_addr().unwrap_or(target);
    let upstream_rtt_ms = connect_platform::tcp_round_trip_time_ms(stream)
        .ok()
        .flatten()
        .or_else(|| Some(started.elapsed().as_millis() as u64));
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
