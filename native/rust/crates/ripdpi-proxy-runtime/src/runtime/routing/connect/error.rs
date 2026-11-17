use std::io;

#[derive(Debug)]
pub(in crate::runtime::routing) struct ConnectAttemptError {
    pub(in crate::runtime::routing) source: io::Error,
    pub(in crate::runtime::routing) tcp_total_retransmissions: Option<u32>,
    pub(in crate::runtime::routing) tcp_fast_open_enabled: bool,
}

impl ConnectAttemptError {
    pub(in crate::runtime::routing) fn is_shutdown_cancelled(&self, shutdown_requested: bool) -> bool {
        shutdown_requested && self.source.kind() == io::ErrorKind::ConnectionAborted
    }

    /// A shutdown or a failed VPN socket protection is local: it must not advance the route
    /// or count as a block signal for the host.
    pub(in crate::runtime::routing) fn is_local_failure(&self, shutdown_requested: bool) -> bool {
        self.is_shutdown_cancelled(shutdown_requested)
            || ripdpi_proxy_runtime_adapter::platform::connect::is_socket_protect_failure(&self.source)
    }

    pub(in crate::runtime::routing) fn into_io_error(self) -> io::Error {
        self.source
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::platform::connect::socket_protect_failure;

    fn attempt(source: io::Error) -> ConnectAttemptError {
        ConnectAttemptError { source, tcp_total_retransmissions: None, tcp_fast_open_enabled: false }
    }

    #[test]
    fn protect_failure_is_local_and_a_refused_connect_is_not() {
        let denied = io::Error::new(io::ErrorKind::PermissionDenied, "protect rejected");
        assert!(attempt(socket_protect_failure(denied)).is_local_failure(false));
        assert!(!attempt(io::Error::from(io::ErrorKind::ConnectionRefused)).is_local_failure(false));
        // The same kind without the marker is a network error, for example a firewall reset.
        assert!(!attempt(io::Error::from(io::ErrorKind::PermissionDenied)).is_local_failure(false));
    }
}
