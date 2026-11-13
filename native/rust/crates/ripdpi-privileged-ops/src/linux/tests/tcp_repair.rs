use std::mem::zeroed;
use std::time::Duration;

use crate::linux::tcp_info::{
    LinuxTcpInfo, TCP_ESTABLISHED, TCPI_OPT_SACK, TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE,
    tcp_has_notsent, tcp_total_retransmissions_from_info, wait_tcp_stage_fd,
};
use crate::linux::tcp_repair::{
    TcpTimestampSnapshot, TcpWindowScaleSnapshot, decode_tcp_repair_options, probe_tcp_repair, sequence_after_payload,
};

#[test]
fn tcp_sequence_addition_wraps_at_the_wire_boundary() {
    assert_eq!(sequence_after_payload(0xffff_fffe, 4).expect("wrap sequence"), 2);
    assert_eq!(sequence_after_payload(u32::MAX, 1).expect("wrap to zero"), 0);
    assert_eq!(sequence_after_payload(100, 4).expect("ordinary sequence"), 104);
    assert_eq!(sequence_after_payload(u32::MAX, 0).expect("empty payload"), u32::MAX);
}

#[test]
#[cfg(target_pointer_width = "64")]
fn tcp_sequence_addition_rejects_lengths_outside_the_wire_space() {
    let error = sequence_after_payload(0, u32::MAX as usize + 1).expect_err("oversized payload");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
}

#[test]
fn raw_packet_sequence_wrap_preserves_payload_ranges() {
    let payload = b"abcdefghijkl";
    let segments =
        [crate::TcpPayloadSegment { start: 0, end: 8 }, crate::TcpPayloadSegment { start: 8, end: payload.len() }];
    let mut snapshot = super::sample_tcp_repair_snapshot();
    snapshot.sequence_number = 0xffff_fffc;
    let packets = crate::linux::fragmentation::build_multi_disorder_packets(
        "192.0.2.1:12345".parse().expect("source address"),
        "198.51.100.2:443".parse().expect("target address"),
        64,
        payload,
        &segments,
        &snapshot,
        false,
        crate::TcpFlagOverrides::default(),
        &[],
    )
    .expect("raw packet sequence crosses the boundary");

    for (packet, (expected_sequence, expected_payload)) in
        packets.iter().zip([(0xffff_fffc, &payload[..8]), (4, &payload[8..])])
    {
        let (_, tcp_packet) = etherparse::Ipv4Header::from_slice(packet).expect("decode IPv4 header");
        let (tcp, actual_payload) = etherparse::TcpHeader::from_slice(tcp_packet).expect("decode TCP header");
        assert_eq!(tcp.sequence_number, expected_sequence);
        assert_eq!(actual_payload, expected_payload);
    }
}

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
fn wait_tcp_stage_times_out_while_peer_leaves_bytes_unsent() {
    use std::io::{Read, Write};
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::os::fd::AsRawFd;
    use std::time::Instant;

    use socket2::{Domain, Protocol, Socket, Type};

    // Limit the advertised window before the handshake so an unread peer leaves source bytes queued.
    let listener = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("TCP listener");
    listener.set_recv_buffer_size(4096).expect("small peer receive buffer");
    listener
        .bind(&"127.0.0.1:0".parse::<std::net::SocketAddr>().expect("loopback address").into())
        .expect("bind listener");
    listener.listen(1).expect("listen");
    let listener: TcpListener = listener.into();
    let mut client = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect");
    let (mut server, _) = listener.accept().expect("accept");
    socket2::SockRef::from(&client).set_send_buffer_size(16384).expect("small source send buffer");
    client.set_nonblocking(true).expect("nonblocking source");
    let payload = [0x5a; 65536];
    let mut accepted = 0usize;
    let fill_deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < fill_deadline {
        match client.write(&payload) {
            Ok(0) => panic!("source accepted no bytes"),
            Ok(count) => accepted += count,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if tcp_has_notsent(client.as_raw_fd()).expect("queued source bytes") {
                    break;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("fill source send buffer: {error}"),
        }
    }
    assert!(accepted > 0, "source bytes reached the socket");
    assert!(tcp_has_notsent(client.as_raw_fd()).expect("TCP_INFO"), "unread peer must leave bytes unsent");

    for wait_send in [false, true] {
        let error = wait_tcp_stage_fd(client.as_raw_fd(), wait_send, Duration::from_millis(10))
            .expect_err("a stalled send queue is not a completed stage");
        assert_eq!(error.kind(), std::io::ErrorKind::TimedOut, "wait_send={wait_send}");
        assert!(tcp_has_notsent(client.as_raw_fd()).expect("TCP_INFO after wait"));
    }

    client.shutdown(Shutdown::Write).expect("finish source writes");
    server.set_read_timeout(Some(Duration::from_secs(5))).expect("peer drain timeout");
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("drain the original source bytes");
    assert_eq!(received, vec![0x5a; accepted]);
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
