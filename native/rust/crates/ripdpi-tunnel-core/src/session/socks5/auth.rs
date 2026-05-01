use std::io;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Authentication method for SOCKS5 handshake.
#[derive(Debug, Clone)]
pub enum Auth {
    NoAuth,
    UserPass { username: String, password: String },
}

/// Perform SOCKS5 handshake (method negotiation + optional auth).
///
/// On success the stream is ready for a CONNECT/ASSOCIATE request.
pub async fn handshake<S>(stream: &mut S, auth: &Auth) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let method = auth_method(auth);

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
    if let Auth::UserPass { username, password } = auth {
        let ulen = checked_auth_field_len(username, "username")?;
        let plen = checked_auth_field_len(password, "password")?;

        let mut req = Vec::with_capacity(3 + username.len() + password.len());
        req.push(0x01); // sub-negotiation version
        req.push(ulen);
        req.extend_from_slice(username.as_bytes());
        req.push(plen);
        req.extend_from_slice(password.as_bytes());
        stream.write_all(&req).await?;

        // Read sub-auth response: [VER, STATUS]
        let mut auth_resp = [0u8; 2];
        stream.read_exact(&mut auth_resp).await?;

        if auth_resp[1] != 0x00 {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS5: authentication rejected"));
        }
    }

    Ok(())
}

fn auth_method(auth: &Auth) -> u8 {
    match auth {
        Auth::NoAuth => 0x00,
        Auth::UserPass { .. } => 0x02,
    }
}

fn checked_auth_field_len(field: &str, label: &str) -> io::Result<u8> {
    u8::try_from(field.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("SOCKS5: {label} must be at most 255 bytes")))
}
