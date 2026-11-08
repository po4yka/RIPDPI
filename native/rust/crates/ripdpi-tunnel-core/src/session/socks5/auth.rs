use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Authentication method for SOCKS5 handshake.
#[derive(Debug, Clone)]
pub enum Auth {
    NoAuth,
    UserPass { username: String, password: String },
}

impl Auth {
    /// Keep owner scopes inside the authenticated loopback transport.
    pub(crate) fn with_app_scope(&self, proxy: SocketAddr, app_scope: Option<&str>) -> Self {
        match (self, app_scope) {
            (Self::UserPass { password, .. }, Some(scope)) if proxy.ip().to_canonical().is_loopback() => {
                Self::UserPass { username: format!("vpn08:{scope}"), password: password.clone() }
            }
            _ => self.clone(),
        }
    }
}

/// Perform SOCKS5 handshake (method negotiation + optional auth).
///
/// On success the stream is ready for a CONNECT/ASSOCIATE request.
///
/// # Cancel safety:
/// NOT cancel-safe: partial reads or writes leave the stream mid-handshake.
/// Drop the stream if this future is cancelled; do not reuse it.
pub async fn handshake<S>(stream: &mut S, auth: &Auth) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = match auth {
        Auth::NoAuth => None,
        Auth::UserPass { username, password } => {
            let ulen = checked_auth_field_len(username, "username")?;
            let plen = checked_auth_field_len(password, "password")?;
            let mut request = Vec::with_capacity(3 + username.len() + password.len());
            request.extend_from_slice(&[0x01, ulen]);
            request.extend_from_slice(username.as_bytes());
            request.push(plen);
            request.extend_from_slice(password.as_bytes());
            Some(request)
        }
    };
    let method = if request.is_some() { 0x02 } else { 0x00 };

    // Send greeting: VER=5, NMETHODS=1, METHOD
    stream.write_all(&[0x05, 0x01, method]).await?;

    // Read server method selection: [VER, METHOD]
    let mut resp = [0u8; 2];
    stream.read_exact(&mut resp).await?;

    if resp[0] != 0x05 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("SOCKS5: invalid handshake version {:#04x}", resp[0]),
        ));
    }

    if resp[1] == 0xFF {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS5: no acceptable authentication method"));
    }

    if resp[1] != method {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!("SOCKS5: server selected method {:#04x}, expected {:#04x}", resp[1], method),
        ));
    }

    // Sub-authentication for USERNAME_PASSWORD
    if let Some(request) = request {
        stream.write_all(&request).await?;

        // Read sub-auth response: [VER, STATUS]
        let mut auth_resp = [0u8; 2];
        stream.read_exact(&mut auth_resp).await?;

        if auth_resp[0] != 0x01 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5: invalid authentication version"));
        }
        if auth_resp[1] != 0x00 {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS5: authentication rejected"));
        }
    }

    Ok(())
}

fn checked_auth_field_len(field: &str, label: &str) -> io::Result<u8> {
    if field.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("SOCKS5: {label} must not be empty")));
    }
    u8::try_from(field.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("SOCKS5: {label} must be at most 255 bytes")))
}
