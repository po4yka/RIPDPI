#![cfg(unix)]

use std::io::{IoSlice, Read, Write};
use std::os::fd::{AsFd, AsRawFd};
use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};

use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};
use ripdpi_root_helper_protocol::{recv_message_with_deadline, send_message};

#[test]
fn a_payload_timeout_closes_the_descriptor_received_with_its_header() {
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    let (passed, mut peer) = UnixStream::pair().expect("lifetime oracle");
    peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
    let fds = [passed.as_raw_fd()];
    // Independent wire oracle: length two, with only the first body byte.
    sendmsg::<()>(
        sender.as_raw_fd(),
        &[IoSlice::new(&[0, 0, 0, 2, b'{'])],
        &[ControlMessage::ScmRights(&fds)],
        MsgFlags::empty(),
        None,
    )
    .expect("send partial frame");
    drop(passed);

    let error = recv_message_with_deadline(&receiver, "closed", Instant::now() + Duration::from_millis(50))
        .expect_err("partial payload must expire");
    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
    assert_eq!(peer.read(&mut [0_u8]).expect("received copy must close"), 0);
}

#[test]
fn a_complete_frame_preserves_its_live_descriptor_before_the_deadline() {
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    let (passed, mut peer) = UnixStream::pair().expect("working descriptor oracle");
    send_message(&sender, b"{}", Some(passed.as_fd())).expect("send frame");
    drop(passed);

    let (bytes, fd) = recv_message_with_deadline(&receiver, "closed", Instant::now() + Duration::from_secs(1))
        .expect("complete frame");
    assert_eq!(bytes, b"{}");
    let mut transferred = UnixStream::from(fd.expect("descriptor"));
    transferred.write_all(b"ok").expect("write through transferred descriptor");
    let mut received = [0_u8; 2];
    peer.read_exact(&mut received).expect("read peer");
    assert_eq!(received, *b"ok");
}

#[test]
fn an_expired_deadline_rejects_even_a_buffered_complete_frame() {
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    send_message(&sender, b"{}", None).expect("send frame");

    let error = recv_message_with_deadline(&receiver, "closed", Instant::now() - Duration::from_millis(1))
        .expect_err("an expired read cannot succeed");
    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
}
