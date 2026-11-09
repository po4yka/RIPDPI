use std::os::unix::net::UnixStream;
use std::sync::{Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_root_helper_protocol::send_message;

use super::RequestDeadline;

fn wait_until_request_expires(watchdog: &RequestDeadline) {
    let mut armed = watchdog.lock();
    while armed.is_none() {
        armed = watchdog.wake.wait(armed).expect("armed request");
    }
    let deadline = armed.as_ref().expect("armed request").1;
    drop(armed);
    thread::sleep(deadline.saturating_duration_since(Instant::now()) + Duration::from_millis(10));
}

#[test]
fn late_request_is_rejected_before_the_watchdog_wakes() {
    // No watchdog runs yet: the reader must reject a late complete frame itself.
    let watchdog = RequestDeadline { armed: Mutex::new(None), wake: Condvar::new() };
    let (client, server) = UnixStream::pair().expect("socket pair");
    let result = thread::scope(|scope| {
        let deadline = &watchdog;
        let sender = scope.spawn(move || {
            wait_until_request_expires(deadline);
            send_message(&client, b"{}", None).expect("complete late request");
        });
        let result = watchdog.recv_request(&server, Duration::from_millis(10));
        sender.join().expect("sender thread");
        result
    });

    let error = result.expect_err("a completed frame after its deadline must not reach dispatch");
    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
}

#[test]
fn a_late_frame_closes_its_received_descriptor() {
    use std::io::Read;
    use std::os::fd::AsFd;

    let watchdog = RequestDeadline { armed: Mutex::new(None), wake: Condvar::new() };
    let (client, server) = UnixStream::pair().expect("IPC pair");
    let (passed, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
    peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
    let result = thread::scope(|scope| {
        let deadline = &watchdog;
        let sender = scope.spawn(move || {
            wait_until_request_expires(deadline);
            send_message(&client, b"{}", Some(passed.as_fd())).expect("late frame");
        });
        let result = watchdog.recv_request(&server, Duration::from_millis(10));
        sender.join().expect("sender");
        result
    });

    assert_eq!(result.expect_err("late frame").kind(), std::io::ErrorKind::TimedOut);
    assert_eq!(peer.read(&mut [0_u8]).expect("received descriptor closes"), 0);
}
