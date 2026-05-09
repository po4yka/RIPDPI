use std::io::{self, Read, Write};
use std::net::TcpStream;

use crate::runtime::state::RuntimeState;

pub(in crate::runtime::handshake) fn negotiate_socks5(
    client: &mut TcpStream,
    auth_token: Option<&str>,
) -> io::Result<()> {
    let mut count = [0u8; 1];
    client.read_exact(&mut count)?;
    let mut methods = vec![0u8; count[0] as usize];
    client.read_exact(&mut methods)?;

    let (reply, accepted) = RuntimeState::socks5_auth_selection(auth_token, &methods);
    client.write_all(&reply)?;
    if let Some(token) = auth_token {
        if !accepted {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks5 auth method"));
        }

        read_socks5_userpass_auth(client, token)
    } else {
        if !accepted {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks auth method"));
        }
        Ok(())
    }
}

pub(in crate::runtime::handshake) fn read_socks5_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    client.read_exact(&mut header)?;
    let mut out = header.to_vec();
    if let Some(tail_len) = RuntimeState::socks5_fixed_address_tail_len(header[3]) {
        let mut tail = vec![0u8; tail_len];
        client.read_exact(&mut tail)?;
        out.extend_from_slice(&tail);
    } else if RuntimeState::is_socks5_domain_address_type(header[3]) {
        let mut len = [0u8; 1];
        client.read_exact(&mut len)?;
        if len[0] == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "empty SOCKS5 domain name"));
        }
        out.extend_from_slice(&len);
        let mut tail = vec![0u8; len[0] as usize + 2];
        client.read_exact(&mut tail)?;
        out.extend_from_slice(&tail);
    } else {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 address type"));
    }
    Ok(out)
}

pub(in crate::runtime::handshake) fn read_socks4_request(client: &mut TcpStream, version: u8) -> io::Result<Vec<u8>> {
    let mut out = vec![version];
    let mut fixed = [0u8; 7];
    client.read_exact(&mut fixed)?;
    out.extend_from_slice(&fixed);

    read_until_nul(client, &mut out)?;
    let is_domain = out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] != 0;
    if is_domain {
        read_until_nul(client, &mut out)?;
    }
    Ok(out)
}

pub(in crate::runtime::handshake) fn read_http_connect_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut chunk = [0u8; 512];
    loop {
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during http connect request"));
        }
        out.extend_from_slice(&chunk[..n]);
        if out.windows(4).any(|window| window == b"\r\n\r\n") {
            return Ok(out);
        }
        if out.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "http connect request too large"));
        }
    }
}

/// Validate HTTP `Proxy-Authorization: Basic` header against the expected auth token.
///
/// Expected encoding: `Basic base64("ripdpi:<token>")`.
pub(in crate::runtime::handshake) fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
    crate::runtime::state::RuntimeState::validate_http_proxy_auth(request, token)
}

fn read_socks5_userpass_auth(client: &mut TcpStream, token: &str) -> io::Result<()> {
    let mut sub_ver = [0u8; 1];
    client.read_exact(&mut sub_ver)?;
    if sub_ver[0] != 0x01 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socks5 auth sub-negotiation version"));
    }

    let mut ulen = [0u8; 1];
    client.read_exact(&mut ulen)?;
    let mut username = vec![0u8; ulen[0] as usize];
    client.read_exact(&mut username)?;

    let mut plen = [0u8; 1];
    client.read_exact(&mut plen)?;
    let mut password = vec![0u8; plen[0] as usize];
    client.read_exact(&mut password)?;

    if password != token.as_bytes() {
        client.write_all(&[0x01, 0x01])?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid socks5 credentials"));
    }

    client.write_all(&[0x01, 0x00])
}

fn read_until_nul(client: &mut TcpStream, out: &mut Vec<u8>) -> io::Result<()> {
    loop {
        let mut byte = [0u8; 1];
        client.read_exact(&mut byte)?;
        out.push(byte[0]);
        if byte[0] == 0 {
            return Ok(());
        }
        if out.len() > 4096 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "request too large"));
        }
    }
}
