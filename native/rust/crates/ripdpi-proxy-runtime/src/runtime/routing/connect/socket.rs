use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::platform::connect::{self as connect_platform, ProtectObservationOutcome};

use super::error::ConnectAttemptError;
use crate::runtime::state::RuntimeState;

struct RuntimeConnectObserver<'a> {
    state: &'a RuntimeState,
}

impl connect_platform::TcpConnectObserver for RuntimeConnectObserver<'_> {
    fn on_socket_created(&self) {
        self.state.note_upstream_socket_created();
    }

    fn on_protect_attempted(&self) {
        self.state.note_upstream_protect_attempted();
    }

    fn on_protect_finished(&self, outcome: ProtectObservationOutcome) {
        match outcome {
            ProtectObservationOutcome::Succeeded => self.state.note_upstream_protect_succeeded(),
            ProtectObservationOutcome::Rejected => self.state.note_upstream_protect_rejected(),
            ProtectObservationOutcome::Error => self.state.note_upstream_protect_error(),
        }
    }
}

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
    connect_platform::connect_tcp_stream(target, bind_ip, protect_path, tfo, connect_timeout, pre_connect_rcvbuf)
        .map_err(|err| ConnectAttemptError {
            source: err.source,
            tcp_total_retransmissions: err.tcp_total_retransmissions,
            tcp_fast_open_enabled: err.tcp_fast_open_enabled,
        })
}

pub(in crate::runtime::routing::connect) fn connect_socket_detailed_observed(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
    pre_connect_rcvbuf: Option<u32>,
    state: &RuntimeState,
) -> Result<TcpStream, ConnectAttemptError> {
    let observer = RuntimeConnectObserver { state };
    connect_platform::connect_tcp_stream_observed(
        target,
        bind_ip,
        protect_path,
        tfo,
        connect_timeout,
        pre_connect_rcvbuf,
        Some(&observer),
    )
    .map_err(|err| ConnectAttemptError {
        source: err.source,
        tcp_total_retransmissions: err.tcp_total_retransmissions,
        tcp_fast_open_enabled: err.tcp_fast_open_enabled,
    })
}

#[cfg(test)]
pub(super) fn should_ignore_android_tfo_error(err: &io::Error) -> bool {
    connect_platform::should_ignore_android_tfo_error(err)
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
