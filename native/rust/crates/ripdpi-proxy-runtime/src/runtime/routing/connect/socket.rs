use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use ripdpi_runtime_platform as platform;

use super::error::ConnectAttemptError;
pub(in crate::runtime) fn connect_socket(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    connect_socket_detailed(target, bind_ip, protect_path, tfo, connect_timeout, None)
        .map_err(ConnectAttemptError::into_io_error)
}

pub(in crate::runtime::routing::connect) fn connect_socket_detailed(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
    pre_connect_rcvbuf: Option<u32>,
) -> Result<TcpStream, ConnectAttemptError> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).map_err(|source| ConnectAttemptError {
        source,
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: tfo,
    })?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, Some(path)).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo,
        })?;
    }
    if tfo {
        enable_tcp_fastopen_if_supported(&socket).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo,
        })?;
    }
    bind_socket(&socket, bind_ip, target).map_err(|source| ConnectAttemptError {
        source,
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: tfo,
    })?;
    if let Some(rcvbuf) = pre_connect_rcvbuf {
        let _ = platform::set_rcvbuf(&socket, rcvbuf);
    }
    let connect_started = std::time::Instant::now();
    tracing::debug!(
        target = %target,
        bind_ip = %bind_ip,
        tcp_fast_open = tfo,
        protected = protect_path.is_some(),
        "ripdpi upstream connect start"
    );
    let connect_result = if let Some(timeout) = connect_timeout {
        socket.connect_timeout(&SockAddr::from(target), timeout)
    } else {
        socket.connect(&SockAddr::from(target))
    };
    if let Err(err) = connect_result {
        let tcp_total_retransmissions = platform::tcp_total_retransmissions(&socket).ok().flatten();
        tracing::warn!(
            target = %target,
            bind_ip = %bind_ip,
            tcp_fast_open = tfo,
            protected = protect_path.is_some(),
            elapsed_ms = connect_started.elapsed().as_millis() as u64,
            "ripdpi upstream connect failed: {err}"
        );
        return Err(ConnectAttemptError { source: err, tcp_total_retransmissions, tcp_fast_open_enabled: tfo });
    }
    tracing::debug!(
        target = %target,
        bind_ip = %bind_ip,
        tcp_fast_open = tfo,
        protected = protect_path.is_some(),
        elapsed_ms = connect_started.elapsed().as_millis() as u64,
        "ripdpi upstream connect established"
    );
    let stream: TcpStream = socket.into();
    if let Err(err) = stream.set_nodelay(true) {
        tracing::debug!("set_nodelay on upstream socket failed (non-fatal): {err}");
    }
    Ok(stream)
}

fn enable_tcp_fastopen_if_supported(socket: &Socket) -> io::Result<()> {
    match platform::enable_tcp_fastopen_connect(socket) {
        Ok(()) => Ok(()),
        #[cfg(target_os = "android")]
        Err(err) if should_ignore_android_tfo_error(&err) => {
            tracing::debug!("TCP Fast Open unavailable on this Android build: {err}");
            Ok(())
        }
        Err(err) => Err(err),
    }
}

#[cfg(any(test, target_os = "android"))]
pub(super) fn should_ignore_android_tfo_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES | libc::EINVAL))
}

fn bind_socket(socket: &Socket, bind_ip: IpAddr, target: SocketAddr) -> io::Result<()> {
    if is_unspecified(bind_ip) {
        return Ok(());
    }
    let bind_addr = match (bind_ip, target) {
        (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
        (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
        _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "bind ip family does not match target family")),
    };
    socket.bind(&SockAddr::from(bind_addr))
}

fn is_unspecified(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_unspecified(),
        IpAddr::V6(ip) => ip.is_unspecified(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_tfo_capability_errors_are_ignored() {
        for errno in [libc::ENOPROTOOPT, libc::EOPNOTSUPP, libc::EPERM, libc::EACCES, libc::EINVAL] {
            assert!(
                should_ignore_android_tfo_error(&io::Error::from_raw_os_error(errno)),
                "expected errno {errno} to be ignored on Android",
            );
        }
    }

    #[test]
    fn android_tfo_runtime_failures_are_not_ignored() {
        for errno in [libc::ECONNRESET, libc::ETIMEDOUT, libc::EHOSTUNREACH] {
            assert!(
                !should_ignore_android_tfo_error(&io::Error::from_raw_os_error(errno)),
                "expected errno {errno} to remain fatal on Android",
            );
        }
    }
}
