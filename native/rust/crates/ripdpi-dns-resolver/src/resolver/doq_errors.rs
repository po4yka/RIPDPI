use crate::types::{DoqErrorKind, EncryptedDnsError};

const NO_APPLICATION_PROTOCOL: u8 = 120;

pub(super) fn connection_error(kind: DoqErrorKind, stage: &str, error: quinn::ConnectionError) -> EncryptedDnsError {
    let (kind, detail) = match error {
        quinn::ConnectionError::TimedOut => {
            let kind =
                if kind == DoqErrorKind::Connect { DoqErrorKind::ConnectTimeout } else { DoqErrorKind::QueryTimeout };
            (kind, error.to_string())
        }
        quinn::ConnectionError::TransportError(error) => {
            handshake_code(kind, u64::from(error.code), error.code.to_string())
        }
        quinn::ConnectionError::ConnectionClosed(close) => {
            handshake_code(kind, u64::from(close.error_code), close.error_code.to_string())
        }
        quinn::ConnectionError::ApplicationClosed(close) => {
            (kind, format!("peer closed with code {}", close.error_code))
        }
        _ => (kind, error.to_string()),
    };
    kind.error(format!("{stage}: {detail}"))
}

fn handshake_code(kind: DoqErrorKind, code: u64, detail: String) -> (DoqErrorKind, String) {
    let kind = if kind != DoqErrorKind::Connect {
        kind
    } else if code == (0x100 | u64::from(NO_APPLICATION_PROTOCOL)) {
        DoqErrorKind::Alpn
    } else if (0x100..0x200).contains(&code) {
        DoqErrorKind::Tls
    } else {
        kind
    };
    // Use the code only: a peer-controlled close reason is not diagnostic evidence.
    (kind, format!("{detail} (code {code:#x})"))
}

pub(super) fn write_error(error: quinn::WriteError) -> EncryptedDnsError {
    match error {
        quinn::WriteError::ConnectionLost(error) => connection_error(DoqErrorKind::Exchange, "DoQ write", error),
        _ => DoqErrorKind::Exchange.error(format!("DoQ write: {error}")),
    }
}

pub(super) fn read_error(stage: &str, error: quinn::ReadError) -> EncryptedDnsError {
    match error {
        quinn::ReadError::ConnectionLost(error) => connection_error(DoqErrorKind::Exchange, stage, error),
        _ => DoqErrorKind::Exchange.error(format!("{stage}: {error}")),
    }
}

pub(super) fn read_exact_error(
    conn: &quinn::Connection,
    stage: &str,
    error: quinn::ReadExactError,
) -> EncryptedDnsError {
    match error {
        quinn::ReadExactError::FinishedEarly(_) => {
            // RFC 9250 §4.3.3: FIN before the stated DNS message length is a protocol error.
            conn.close(2u32.into(), b"DoQ response ended early");
            EncryptedDnsError::DnsParse(format!("{stage}: {error}"))
        }
        quinn::ReadExactError::ReadError(error) => read_error(stage, error),
    }
}
