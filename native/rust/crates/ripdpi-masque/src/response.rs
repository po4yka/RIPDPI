use std::io;

use http::{HeaderMap, StatusCode};

use crate::auth::parse_privacy_pass_challenge;
use crate::config::MasqueAuthMode;

pub(crate) enum AttemptError {
    Io(io::Error),
    PrivacyPassChallenge(String),
}

impl From<io::Error> for AttemptError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

pub(crate) fn validate_proxy_response(
    status: StatusCode,
    headers: &HeaderMap,
    auth_mode: MasqueAuthMode,
) -> Result<(), AttemptError> {
    if status.is_success() {
        return Ok(());
    }

    if auth_mode == MasqueAuthMode::PrivacyPass
        && (status == StatusCode::PROXY_AUTHENTICATION_REQUIRED || status == StatusCode::UNAUTHORIZED)
    {
        let authenticate_header = headers.get("www-authenticate").or_else(|| headers.get("proxy-authenticate"));
        if authenticate_header.is_some() {
            let challenge = parse_privacy_pass_challenge(authenticate_header)?;
            return Err(AttemptError::PrivacyPassChallenge(challenge));
        }
    }

    let message = match auth_mode {
        MasqueAuthMode::CloudflareMtls if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN => {
            format!("MASQUE proxy rejected the Cloudflare direct client identity with status {status}")
        }
        MasqueAuthMode::Bearer | MasqueAuthMode::Preshared
            if status == StatusCode::UNAUTHORIZED || status == StatusCode::PROXY_AUTHENTICATION_REQUIRED =>
        {
            format!("MASQUE proxy rejected the configured auth secret with status {status}")
        }
        MasqueAuthMode::PrivacyPass
            if status == StatusCode::UNAUTHORIZED || status == StatusCode::PROXY_AUTHENTICATION_REQUIRED =>
        {
            format!("MASQUE proxy rejected Privacy Pass authentication with status {status}")
        }
        _ => format!("MASQUE proxy rejected request with status {status}"),
    };
    Err(io::Error::new(io::ErrorKind::PermissionDenied, message).into())
}

pub(crate) fn classify_attempt_failure(error: &io::Error) -> &'static str {
    match error.kind() {
        io::ErrorKind::PermissionDenied => "permission_denied",
        io::ErrorKind::InvalidInput => "invalid_input",
        io::ErrorKind::InvalidData => "invalid_data",
        io::ErrorKind::AddrNotAvailable => "address_unavailable",
        io::ErrorKind::TimedOut => "timed_out",
        io::ErrorKind::ConnectionRefused => "connection_refused",
        io::ErrorKind::ConnectionReset => "connection_reset",
        io::ErrorKind::BrokenPipe | io::ErrorKind::UnexpectedEof => "transport_closed",
        _ => "io_error",
    }
}
