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

    fn attached_filter_len(stream: &TcpStream) -> libc::socklen_t {
        let mut len: libc::socklen_t = 0;
        // SAFETY: `stream` owns a live socket fd for the call; a null optval with a zero
        // optlen asks the kernel only for the attached program's instruction count in `len`.
        let rc = unsafe {
            libc::getsockopt(
                stream.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_ATTACH_FILTER,
                std::ptr::null_mut(),
                &mut len,
            )
        };
        assert_eq!(rc, 0, "SO_GET_FILTER: {}", std::io::Error::last_os_error());
        len
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
        let (drop_sack_only, _a) = connected_stream();
        apply_group_socket_options(&drop_sack_only, &policy(true, false)).expect("drop-SACK filter");
        let (both, _b) = connected_stream();

        apply_group_socket_options(&both, &policy(true, true)).expect("both filters requested");

        assert_eq!(attached_filter_len(&both), attached_filter_len(&drop_sack_only), "drop-SACK must stay attached");
    }
}
