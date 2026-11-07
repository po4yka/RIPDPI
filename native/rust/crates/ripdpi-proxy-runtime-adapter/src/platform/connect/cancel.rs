use std::io;
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use socket2::{SockAddr, Socket};

pub(super) fn connect(
    socket: &Socket,
    target: SocketAddr,
    timeout: Option<Duration>,
    cancelled: &dyn Fn() -> bool,
) -> io::Result<()> {
    let deadline = timeout.map(|timeout| Instant::now() + timeout);
    check_cancelled(cancelled)?;
    socket.set_nonblocking(true)?;
    match socket.connect(&SockAddr::from(target)) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::WouldBlock || error.raw_os_error() == Some(libc::EINPROGRESS) => {}
        Err(error) => return Err(error),
    }
    wait_for_connect(socket, deadline, cancelled)
}

fn wait_for_connect(socket: &Socket, deadline: Option<Instant>, cancelled: &dyn Fn() -> bool) -> io::Result<()> {
    loop {
        check_cancelled(cancelled)?;
        if let Some(error) = socket.take_error()? {
            return Err(error);
        }
        if socket.peer_addr().is_ok() {
            socket.set_nonblocking(false)?;
            return Ok(());
        }
        let wait = deadline.map_or(Duration::from_millis(10), |deadline| {
            deadline.saturating_duration_since(Instant::now()).min(Duration::from_millis(10))
        });
        if wait.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "upstream connect timed out"));
        }
        std::thread::sleep(wait);
    }
}

fn check_cancelled(cancelled: &dyn Fn() -> bool) -> io::Result<()> {
    if cancelled() {
        return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "upstream connect cancelled"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use socket2::{Domain, Protocol, Type};
    use std::net::{Ipv4Addr, TcpListener};

    #[test]
    fn cancelled_connect_sends_no_connection_even_without_a_deadline() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        listener.set_nonblocking(true).expect("nonblocking listener");
        let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("socket");
        let error = connect(&socket, listener.local_addr().expect("address"), None, &|| true)
            .expect_err("cancelled before connect");
        assert_eq!(error.kind(), io::ErrorKind::ConnectionAborted);
        assert_eq!(listener.accept().expect_err("no connection sent").kind(), io::ErrorKind::WouldBlock);
    }

    #[test]
    fn waiting_for_completion_honours_cancel_and_deadline_without_reconnecting() {
        // An unconnected socket cannot complete, independent of the host accept-queue policy.
        for timeout in [None, Some(Duration::from_millis(50))] {
            let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("pending socket");
            let started = Instant::now();
            let cancelled = || timeout.is_none() && started.elapsed() >= Duration::from_millis(50);
            let error = wait_for_connect(&socket, timeout.map(|timeout| started + timeout), &cancelled)
                .expect_err("unfinished socket must remain cancellable");
            assert_eq!(
                error.kind(),
                if timeout.is_some() { io::ErrorKind::TimedOut } else { io::ErrorKind::ConnectionAborted }
            );
            assert!(started.elapsed() < Duration::from_secs(1));
        }
    }

    #[test]
    fn successful_connect_restores_blocking_mode() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("socket");
        connect(&socket, listener.local_addr().expect("address"), Some(Duration::from_secs(1)), &|| false)
            .expect("connected");
        assert!(socket.peer_addr().is_ok());
        assert!(!socket.nonblocking().expect("blocking mode restored"));
        assert_eq!(
            listener.accept().expect("accepted").1.port(),
            socket.local_addr().expect("local").as_socket().expect("IP").port()
        );
    }
}
