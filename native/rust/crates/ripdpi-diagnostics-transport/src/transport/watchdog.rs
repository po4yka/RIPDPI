use std::io;
use std::net::Shutdown;

use super::stream::ProbeStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::time::{Duration, Instant};

use crate::util::active_scan_io_deadline;

const POLL_INTERVAL: Duration = Duration::from_millis(50);

pub fn run_with_socket_watchdog<T>(
    socket: ProbeStream,
    cancel: Option<&AtomicBool>,
    operation: impl FnOnce() -> T,
) -> T {
    let deadline = active_scan_io_deadline();
    if deadline.is_none() && cancel.is_none() {
        return operation();
    }
    watch_socket(socket, deadline, &|| cancel.is_some_and(|flag| flag.load(Ordering::Acquire)), |_| operation())
}

pub fn run_with_socket_abort_watchdog<T>(
    socket: ProbeStream,
    should_abort: &(dyn Fn() -> bool + Sync),
    operation: impl FnOnce() -> io::Result<T>,
) -> io::Result<T> {
    watch_socket(socket, active_scan_io_deadline(), should_abort, |started| {
        started?;
        operation()
    })
}

fn watch_socket<T>(
    socket: ProbeStream,
    deadline: Option<Instant>,
    should_abort: &(dyn Fn() -> bool + Sync),
    operation: impl FnOnce(io::Result<()>) -> T,
) -> T {
    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        let watchdog =
            std::thread::Builder::new().name("ripdpi-socket-watchdog".to_string()).spawn_scoped(scope, move || {
                loop {
                    if should_abort() || deadline.is_some_and(|value| Instant::now() >= value) {
                        let _ = socket.shutdown(Shutdown::Both);
                        return;
                    }
                    let wait = deadline.map_or(POLL_INTERVAL, |value| {
                        value.saturating_duration_since(Instant::now()).min(POLL_INTERVAL)
                    });
                    match done_rx.recv_timeout(wait) {
                        Ok(()) | Err(RecvTimeoutError::Disconnected) => return,
                        Err(RecvTimeoutError::Timeout) => {}
                    }
                }
            });
        let result = operation(watchdog.map(|_| ()));
        drop(done_tx);
        result
    })
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener, TcpStream};

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
            run_with_socket_watchdog(client.try_clone().unwrap().into(), None, || {
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
            run_with_socket_watchdog(client.try_clone().unwrap().into(), Some(&cancel), || client.read(&mut [0u8; 8]))
        });

        assert!(matches!(read, Ok(0) | Err(_)));
        assert!(started.elapsed() < Duration::from_secs(1));
    }
}
