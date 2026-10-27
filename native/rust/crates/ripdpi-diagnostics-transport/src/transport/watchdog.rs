use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::time::{Duration, Instant};

use crate::util::active_scan_io_deadline;

const POLL_INTERVAL: Duration = Duration::from_millis(50);

/// Run blocking I/O and shut `socket` down once `cancel` is set or the scan deadline of the
/// calling thread passes.
///
/// A socket timeout bounds one read only. Loops such as rustls `StreamOwned::read` or an HTTP
/// body read continue while a peer sends a byte within every timeout. The shutdown makes their
/// next read or write fail, which bounds the whole operation. `socket` is a clone of the socket
/// that `operation` uses. The caller decides from the deadline or the flag whether a failure
/// came from the shutdown. Without a deadline and without a flag, `operation` runs directly.
pub fn run_with_socket_watchdog<T>(socket: TcpStream, cancel: Option<&AtomicBool>, operation: impl FnOnce() -> T) -> T {
    let deadline = active_scan_io_deadline();
    if deadline.is_none() && cancel.is_none() {
        return operation();
    }
    let (done_tx, done_rx) = mpsc::channel::<()>();
    std::thread::scope(|scope| {
        scope.spawn(move || {
            loop {
                // Acquire observes the scan owner's published cancellation flag.
                let cancelled = cancel.is_some_and(|flag| flag.load(Ordering::Acquire));
                if cancelled || deadline.is_some_and(|value| Instant::now() >= value) {
                    let _ = socket.shutdown(Shutdown::Both);
                    return;
                }
                let wait = deadline
                    .map_or(POLL_INTERVAL, |value| value.saturating_duration_since(Instant::now()).min(POLL_INTERVAL));
                match done_rx.recv_timeout(wait) {
                    Ok(()) | Err(RecvTimeoutError::Disconnected) => return,
                    Err(RecvTimeoutError::Timeout) => {}
                }
            }
        });
        let result = operation();
        let _ = done_tx.send(());
        result
    })
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use super::*;

    #[test]
    fn slow_drip_read_loop_ends_at_the_scan_deadline() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        client.set_read_timeout(Some(Duration::from_millis(1200))).unwrap();
        let (mut peer, _) = listener.accept().unwrap();
        let server = std::thread::spawn(move || {
            let started = Instant::now();
            while started.elapsed() < Duration::from_secs(4) && peer.write_all(b"x").is_ok() {
                std::thread::sleep(Duration::from_millis(100));
            }
        });
        let started = Instant::now();

        let received = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(300)), || {
            run_with_socket_watchdog(client.try_clone().unwrap(), None, || {
                let mut total = 0;
                let mut buf = [0u8; 16];
                while let Ok(read @ 1..) = client.read(&mut buf) {
                    total += read;
                }
                total
            })
        });

        assert!(received > 0);
        assert!(started.elapsed() < Duration::from_secs(1), "read loop ran for {:?}", started.elapsed());
        drop(client);
        server.join().unwrap();
    }

    #[test]
    fn cancel_flag_ends_the_operation_without_a_deadline() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let _peer = listener.accept().unwrap();
        let cancel = AtomicBool::new(false);
        let started = Instant::now();

        let read = std::thread::scope(|scope| {
            scope.spawn(|| {
                std::thread::sleep(Duration::from_millis(50));
                cancel.store(true, Ordering::Release);
            });
            run_with_socket_watchdog(client.try_clone().unwrap(), Some(&cancel), || client.read(&mut [0u8; 8]))
        });

        assert!(matches!(read, Ok(0) | Err(_)));
        assert!(started.elapsed() < Duration::from_secs(1));
    }
}
