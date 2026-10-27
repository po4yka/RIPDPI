use std::mem::zeroed;
use std::time::Duration;

use crate::linux::tcp_info::{
    LinuxTcpInfo, TCP_ESTABLISHED, TCPI_OPT_SACK, TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE,
    tcp_has_notsent, tcp_total_retransmissions_from_info, wait_tcp_stage_fd,
};
use crate::linux::tcp_repair::{
    TcpTimestampSnapshot, TcpWindowScaleSnapshot, decode_tcp_repair_options, probe_tcp_repair,
};

#[test]
fn tcp_total_retransmissions_prefers_total_counter_and_falls_back_to_retransmits() {
    let info =
        LinuxTcpInfo { tcpi_state: TCP_ESTABLISHED, tcpi_total_retrans: 5, tcpi_retransmits: 2, ..Default::default() };
    assert_eq!(tcp_total_retransmissions_from_info(&info).expect("extract"), Some(5));

    let fallback =
        LinuxTcpInfo { tcpi_state: TCP_ESTABLISHED, tcpi_total_retrans: 0, tcpi_retransmits: 3, ..Default::default() };
    assert_eq!(tcp_total_retransmissions_from_info(&fallback).expect("fallback"), Some(3));
}

#[test]
fn invalid_fds_report_errors_for_tcp_state_helpers() {
    let err = tcp_has_notsent(-1).expect_err("invalid fd should fail");
    assert_eq!(err.raw_os_error(), Some(libc::EBADF));

    let err = wait_tcp_stage_fd(-1, false, Duration::ZERO).expect_err("invalid fd should fail");
    assert_eq!(err.raw_os_error(), Some(libc::EBADF));
}

#[test]
fn decode_tcp_repair_options_preserves_negotiated_timestamp_state() {
    // SAFETY: LinuxTcpInfo mirrors the kernel tcp_info layout; zeroed is a
    // valid base before filling the fields relevant to this decoder.
    let mut info: LinuxTcpInfo = unsafe { zeroed() };
    info.tcpi_options = TCPI_OPT_TIMESTAMPS | TCPI_OPT_SACK | TCPI_OPT_WSCALE | TCPI_OPT_USEC_TS;
    info.tcpi_snd_wscale_rcv_wscale = 0x27;
    info.tcpi_snd_mss = 1440;

    let options = decode_tcp_repair_options(
        info,
        Some(TcpTimestampSnapshot { value: 0x1122_3344, echo_reply: 0, usec_ts: true }),
    );

    assert_eq!(options.mss, Some(1440));
    assert!(options.sack_permitted);
    assert_eq!(options.window_scale, Some(TcpWindowScaleSnapshot { send: 7, receive: 2 }));
    assert_eq!(options.timestamp, Some(TcpTimestampSnapshot { value: 0x1122_3344, echo_reply: 0, usec_ts: true }));
}

#[test]
fn decode_tcp_repair_options_omits_timestamp_when_not_negotiated() {
    // SAFETY: see the tcp_info zero-initialization rationale above.
    let mut info: LinuxTcpInfo = unsafe { zeroed() };
    info.tcpi_options = TCPI_OPT_SACK;
    info.tcpi_snd_mss = 1200;

    let options = decode_tcp_repair_options(info, None);

    assert_eq!(options.mss, Some(1200));
    assert!(options.sack_permitted);
    assert_eq!(options.window_scale, None);
    assert_eq!(options.timestamp, None);
}

#[test]
fn probe_tcp_repair_requires_kernel_or_protect_path_work() {
    let protect_path = super::missing_protect_socket_path("tcp-repair-probe");

    probe_tcp_repair(Some(&protect_path))
        .expect_err("TCP_REPAIR probe must not succeed without protect/capability work");
}

#[test]
fn tcp_timestamp_round_trips_tsvals_above_i32_max() {
    use std::os::fd::AsRawFd;

    use crate::linux::socket_options::set_c_int_sockopt;
    use crate::linux::tcp_repair::{TCP_REPAIR_ON, disable_tcp_repair, read_tcp_timestamp, set_tcp_repair};

    let (client, _server) = super::connected_pair();
    let fd = client.as_raw_fd();
    // The kernel picks a random TSval offset per address pair, so about half
    // of all connections report a TSval with the top bit set.
    read_tcp_timestamp(fd).expect("read the TSval of a live connection");

    // Writing the TSval needs TCP_REPAIR (CAP_NET_ADMIN); without it only the
    // read above runs.
    if set_tcp_repair(fd, TCP_REPAIR_ON).is_err() {
        return;
    }
    let written = 0x9000_0000_u32;
    let result = set_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP, written.cast_signed())
        .and_then(|()| read_tcp_timestamp(fd));
    let _ = disable_tcp_repair(fd);
    let read_back = result.expect("TSval above i32::MAX round-trips");
    assert!(read_back.wrapping_sub(written) < 10_000, "read back {read_back:#x}, wrote {written:#x}");
}

#[test]
fn tcp_activation_state_does_not_break_a_concurrent_reader() {
    use std::io::{Read, Write};
    use std::os::fd::AsRawFd;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};

    use crate::linux::tcp_info::tcp_activation_state;
    use crate::linux::tcp_repair::{TCP_REPAIR_ON, disable_tcp_repair, set_tcp_repair};

    let (client, mut server) = super::connected_pair();
    // Only a caller that can enter TCP_REPAIR (CAP_NET_ADMIN) could disturb
    // the reader, so the check is meaningful only with that capability.
    if set_tcp_repair(client.as_raw_fd(), TCP_REPAIR_ON).is_err() {
        return;
    }
    disable_tcp_repair(client.as_raw_fd()).expect("leave TCP_REPAIR");

    let stop = Arc::new(AtomicBool::new(false));
    let writer_stop = Arc::clone(&stop);
    let writer = std::thread::spawn(move || {
        while !writer_stop.load(Ordering::Relaxed) {
            if server.write_all(&[0x42; 16]).is_err() {
                return;
            }
            std::thread::sleep(Duration::from_micros(50));
        }
    });
    let mut reader_stream = client.try_clone().expect("clone the relay reader");
    reader_stream.set_read_timeout(Some(Duration::from_millis(50))).expect("reader timeout");
    let reader_stop = Arc::clone(&stop);
    let reader = std::thread::spawn(move || {
        let mut buffer = [0u8; 4];
        while !reader_stop.load(Ordering::Relaxed) {
            match reader_stream.read(&mut buffer) {
                Ok(_) => {}
                Err(error) if matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
                }
                Err(error) => return Err(error),
            }
        }
        Ok(())
    });

    for _ in 0..5_000 {
        tcp_activation_state(&client).expect("activation state");
    }
    stop.store(true, Ordering::Relaxed);
    writer.join().expect("writer thread");
    reader.join().expect("reader thread").expect("the relay reader never sees an error");
}

#[test]
fn tcp_activation_state_reports_the_receive_window_without_tcp_repair() {
    use std::os::fd::AsRawFd;

    use crate::linux::tcp_info::{read_tcp_rcv_wnd, tcp_activation_state};

    let (client, _server) = super::connected_pair();
    let state = tcp_activation_state(&client).expect("activation state").expect("TCP_INFO");
    // Only Linux 6.2+ reports tcpi_rcv_wnd; Android GKI 6.1 and older kernels do not.
    if read_tcp_rcv_wnd(client.as_raw_fd()).expect("TCP_INFO").is_none() {
        assert_eq!(state.window_size, None, "no window without tcpi_rcv_wnd");
        eprintln!("skipped: this kernel's TCP_INFO has no tcpi_rcv_wnd (Linux 6.2+)");
        return;
    }
    let window = state.window_size.expect("receive window from TCP_INFO");
    assert!((1..=i64::from(u16::MAX)).contains(&window), "window {window}");
    assert_eq!(state.has_timestamp, Some(true));
    assert!(state.mss.is_some_and(|mss| mss > 0));
}

#[cfg(target_env = "gnu")]
#[test]
fn tcpi_rcv_wnd_offset_matches_the_libc_tcp_info_layout() {
    assert_eq!(std::mem::offset_of!(libc::tcp_info, tcpi_rcv_wnd), crate::linux::tcp_info::TCPI_RCV_WND_OFFSET);
}

#[test]
fn snapshot_window_is_shifted_by_the_receive_window_scale() {
    use std::os::fd::AsRawFd;

    use crate::linux::tcp_repair::{
        TCP_NO_QUEUE, TCP_REPAIR_ON, disable_tcp_repair, set_tcp_repair, set_tcp_repair_queue,
        snapshot_tcp_repair_state,
    };

    let (client, _server) = super::connected_pair();
    let fd = client.as_raw_fd();
    // The snapshot needs TCP_REPAIR (CAP_NET_ADMIN).
    if set_tcp_repair(fd, TCP_REPAIR_ON).is_err() {
        return;
    }
    let snapshot = snapshot_tcp_repair_state(fd);
    let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
    let _ = disable_tcp_repair(fd);
    let snapshot = snapshot.expect("snapshot of a live connection");

    let shift = snapshot.options.window_scale.expect("loopback negotiates window scaling").receive;
    assert!(shift > 0, "loopback receive scale");
    // RFC 7323 2.3: the window field of a non-SYN segment is RCV.WND >> Rcv.Wind.Shift.
    let expected = (snapshot.repair_window.rcv_wnd >> shift).min(u32::from(u16::MAX));
    assert_eq!(u32::from(snapshot.window_size), expected, "rcv_wnd {}", snapshot.repair_window.rcv_wnd);
}
