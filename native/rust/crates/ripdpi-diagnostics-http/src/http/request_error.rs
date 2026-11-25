use std::fmt;
use std::io;

use crate::tls::{ProbeStreamError, ProbeStreamFailureStage};

#[derive(Debug)]
pub enum HttpRequestError {
    Stream(ProbeStreamError),
    RequestWrite(io::Error),
    ResponseRead(io::Error),
    SocketSetup(io::Error),
    Cancelled,
    DeadlineExceeded,
}

impl HttpRequestError {
    pub fn status(&self) -> &'static str {
        match self {
            Self::Cancelled => "cancelled",
            Self::DeadlineExceeded => "deadline_exceeded",
            Self::Stream(error) => {
                let message = error.message.to_ascii_lowercase();
                if message.contains("timeout") || message.contains("timed out") || message.contains("deadline") {
                    "timeout"
                } else if error.stage == ProbeStreamFailureStage::TlsHandshake {
                    "tls_error"
                } else {
                    "network_error"
                }
            }
            Self::RequestWrite(error) | Self::ResponseRead(error)
                if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
            {
                "timeout"
            }
            Self::ResponseRead(error) if error.kind() == io::ErrorKind::InvalidData => "invalid_response",
            Self::RequestWrite(_) | Self::ResponseRead(_) | Self::SocketSetup(_) => "network_error",
        }
    }
}

impl fmt::Display for HttpRequestError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Stream(error) => error.fmt(formatter),
            Self::RequestWrite(error) | Self::ResponseRead(error) | Self::SocketSetup(error) => error.fmt(formatter),
            Self::Cancelled => formatter.write_str("scan_cancelled"),
            Self::DeadlineExceeded => formatter.write_str("scan_deadline_exceeded"),
        }
    }
}

impl std::error::Error for HttpRequestError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Stream(error) => Some(error),
            Self::RequestWrite(error) | Self::ResponseRead(error) | Self::SocketSetup(error) => Some(error),
            Self::Cancelled | Self::DeadlineExceeded => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn setup_failures_keep_timeout_priority_at_the_legacy_boundary() {
        for (stage, message, expected) in [
            (ProbeStreamFailureStage::DnsResolution, "dns_resolve_timeout", "timeout"),
            (ProbeStreamFailureStage::Socks5Negotiation, "SOCKS5 negotiation timed out", "timeout"),
            (ProbeStreamFailureStage::TcpConnect, "connection timed out", "timeout"),
            (ProbeStreamFailureStage::TlsHandshake, "TLS handshake timed out", "timeout"),
            (ProbeStreamFailureStage::StreamSetup, "scan_deadline_exceeded", "timeout"),
            (ProbeStreamFailureStage::TlsHandshake, "invalid peer certificate", "tls_error"),
            (ProbeStreamFailureStage::TcpConnect, "connection refused", "network_error"),
            (ProbeStreamFailureStage::DnsResolution, "dns_resolver_busy", "network_error"),
        ] {
            let mut source = crate::tls::open_probe_stream_targets_with_options_and_abort(
                &[],
                443,
                &crate::transport::direct_transport(),
                None,
                &crate::tls::ProbeStreamOptions::default(),
                &|| Some("test_cancelled"),
            )
            .err()
            .expect("abort before any network I/O");
            source.stage = stage;
            source.message = message.to_string();
            let error = HttpRequestError::Stream(source);
            assert_eq!(error.status(), expected, "{stage:?}/{message}");
        }
    }

    #[test]
    fn exchange_status_uses_io_kind_instead_of_display_text() {
        for kind in [io::ErrorKind::WouldBlock, io::ErrorKind::TimedOut] {
            let error = HttpRequestError::ResponseRead(io::Error::new(kind, "localized socket message"));
            assert_eq!(error.status(), "timeout");
            assert!(std::error::Error::source(&error).unwrap().downcast_ref::<io::Error>().is_some());
        }
        let reset = HttpRequestError::ResponseRead(io::Error::new(io::ErrorKind::ConnectionReset, "timeout in text"));
        assert_eq!(reset.status(), "network_error");
        let invalid = HttpRequestError::ResponseRead(io::Error::new(io::ErrorKind::InvalidData, "timeout in text"));
        assert_eq!(invalid.status(), "invalid_response");
        let resource = HttpRequestError::SocketSetup(io::Error::new(io::ErrorKind::WouldBlock, "no thread available"));
        assert_eq!(resource.status(), "network_error");
    }
}
