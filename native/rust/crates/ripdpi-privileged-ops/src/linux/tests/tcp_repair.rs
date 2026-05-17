use std::mem::zeroed;
use std::time::Duration;

use crate::linux::tcp_info::{
    tcp_has_notsent, tcp_total_retransmissions_from_info, wait_tcp_stage_fd, LinuxTcpInfo, TCPI_OPT_SACK,
    TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE, TCP_ESTABLISHED,
};
use crate::linux::tcp_repair::{decode_tcp_repair_options, TcpTimestampSnapshot, TcpWindowScaleSnapshot};

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
