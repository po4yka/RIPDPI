use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::Instant;

use crate::runtime::state::{HANDSHAKE_TIMEOUT, RuntimeState};
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

/// Every read in this module is bounded by one absolute `deadline`, not by a
/// per-syscall timeout, so a peer that trickles one byte per timeout period
/// cannot hold a handshake open. The client read timeout returns to
/// `HANDSHAKE_TIMEOUT` once a reader finishes, which is what the relay stage
/// expects.
pub(in crate::runtime::handshake) fn negotiate_socks5(
    client: &mut TcpStream,
    auth_token: Option<&str>,
    deadline: Instant,
) -> io::Result<Option<AttemptCorrelationId>> {
    let result = negotiate_socks5_inner(client, auth_token, deadline);
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    result
}

fn negotiate_socks5_inner(
    client: &mut TcpStream,
    auth_token: Option<&str>,
    deadline: Instant,
) -> io::Result<Option<AttemptCorrelationId>> {
    let mut count = [0u8; 1];
    read_exact_by(client, &mut count, deadline)?;
    let mut methods = vec![0u8; count[0] as usize];
    read_exact_by(client, &mut methods, deadline)?;

    let (reply, accepted) = RuntimeState::socks5_auth_selection(auth_token, &methods);
    client.write_all(&reply)?;
    if let Some(token) = auth_token {
        if !accepted {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks5 auth method"));
        }

        let allow_attempt_token = client.peer_addr().is_ok_and(|addr| addr.ip().is_loopback());
        read_socks5_userpass_auth(client, token, allow_attempt_token, deadline)
    } else {
        if !accepted {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks auth method"));
        }
        Ok(None)
    }
}

pub(in crate::runtime::handshake) fn read_socks5_request(
    client: &mut TcpStream,
    deadline: Instant,
) -> io::Result<Vec<u8>> {
    let result = read_socks5_request_inner(client, deadline);
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    result
}

fn read_socks5_request_inner(client: &mut TcpStream, deadline: Instant) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    read_exact_by(client, &mut header, deadline)?;
    let mut out = header.to_vec();
    if let Some(tail_len) = RuntimeState::socks5_fixed_address_tail_len(header[3]) {
        let mut tail = vec![0u8; tail_len];
        read_exact_by(client, &mut tail, deadline)?;
        out.extend_from_slice(&tail);
    } else if RuntimeState::is_socks5_domain_address_type(header[3]) {
        let mut len = [0u8; 1];
        read_exact_by(client, &mut len, deadline)?;
        if len[0] == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "empty SOCKS5 domain name"));
        }
        out.extend_from_slice(&len);
        let mut tail = vec![0u8; len[0] as usize + 2];
        read_exact_by(client, &mut tail, deadline)?;
        out.extend_from_slice(&tail);
    } else if RuntimeState::is_socks5_resolved_domain_address_type(header[3]) {
        let mut len = [0u8; 1];
        read_exact_by(client, &mut len, deadline)?;
        if len[0] == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "empty RIPDPI resolved domain name"));
        }
        out.extend_from_slice(&len);
        let mut host = vec![0u8; len[0] as usize];
        read_exact_by(client, &mut host, deadline)?;
        out.extend_from_slice(&host);
        let mut ip_type = [0u8; 1];
        read_exact_by(client, &mut ip_type, deadline)?;
        out.extend_from_slice(&ip_type);
        let tail_len = RuntimeState::socks5_fixed_address_tail_len(ip_type[0])
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid resolved SOCKS5 IP type"))?;
        let mut tail = vec![0u8; tail_len];
        read_exact_by(client, &mut tail, deadline)?;
        out.extend_from_slice(&tail);
    } else {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 address type"));
    }
    Ok(out)
}

pub(in crate::runtime::handshake) fn read_socks4_request(
    client: &mut TcpStream,
    version: u8,
    deadline: Instant,
) -> io::Result<Vec<u8>> {
    let result = read_socks4_request_inner(client, version, deadline);
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    result
}

fn read_socks4_request_inner(client: &mut TcpStream, version: u8, deadline: Instant) -> io::Result<Vec<u8>> {
    let mut out = vec![version];
    let mut fixed = [0u8; 7];
    read_exact_by(client, &mut fixed, deadline)?;
    out.extend_from_slice(&fixed);

    read_until_nul(client, &mut out, deadline)?;
    let is_domain = out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] != 0;
    if is_domain {
        read_until_nul(client, &mut out, deadline)?;
    }
    Ok(out)
}

pub(in crate::runtime::handshake) fn read_http_connect_request(
    client: &mut TcpStream,
    deadline: Instant,
) -> io::Result<Vec<u8>> {
    let result = read_http_connect_request_inner(client, deadline);
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    result
}

fn read_http_connect_request_inner(client: &mut TcpStream, deadline: Instant) -> io::Result<Vec<u8>> {
    const MAX_HEADER: usize = 64 * 1024;
    let mut out = Vec::new();
    let mut chunk = [0u8; 512];
    loop {
        let n = peek_by(client, &mut chunk, deadline)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during http connect request"));
        }
        let consumed = out.len();
        out.extend_from_slice(&chunk[..n]);
        let end = out[consumed.saturating_sub(3)..]
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .map(|offset| consumed.saturating_sub(3) + offset + 4);
        if end.is_some_and(|end| end > MAX_HEADER) || (end.is_none() && out.len() >= MAX_HEADER) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "http connect request too large"));
        }
        out.truncate(end.unwrap_or(out.len()));
        read_exact_by(client, &mut out[consumed..], deadline)?;
        if end.is_some() {
            return Ok(out);
        }
    }
}

/// Validate HTTP `Proxy-Authorization: Basic` header against the expected auth token.
///
/// Expected encoding: `Basic base64("ripdpi:<token>")`.
pub(in crate::runtime::handshake) fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
    crate::runtime::state::RuntimeState::validate_http_proxy_auth(request, token)
}

fn read_socks5_userpass_auth(
    client: &mut TcpStream,
    token: &str,
    allow_attempt_token: bool,
    deadline: Instant,
) -> io::Result<Option<AttemptCorrelationId>> {
    let mut sub_ver = [0u8; 1];
    read_exact_by(client, &mut sub_ver, deadline)?;
    if sub_ver[0] != 0x01 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socks5 auth sub-negotiation version"));
    }

    let mut ulen = [0u8; 1];
    read_exact_by(client, &mut ulen, deadline)?;
    let mut username = vec![0u8; ulen[0] as usize];
    read_exact_by(client, &mut username, deadline)?;

    let mut plen = [0u8; 1];
    read_exact_by(client, &mut plen, deadline)?;
    let mut password = vec![0u8; plen[0] as usize];
    read_exact_by(client, &mut password, deadline)?;

    if !constant_time_bytes_eq(&password, token.as_bytes()) {
        client.write_all(&[0x01, 0x01])?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid socks5 credentials"));
    }

    client.write_all(&[0x01, 0x00])?;
    if !allow_attempt_token {
        return Ok(None);
    }
    let username = String::from_utf8(username).ok();
    Ok(username.and_then(AttemptCorrelationId::new))
}

fn constant_time_bytes_eq(candidate: &[u8], expected: &[u8]) -> bool {
    constant_time_eq::constant_time_eq(candidate, expected)
}

fn read_until_nul(client: &mut TcpStream, out: &mut Vec<u8>, deadline: Instant) -> io::Result<()> {
    loop {
        let mut byte = [0u8; 1];
        read_exact_by(client, &mut byte, deadline)?;
        out.push(byte[0]);
        if byte[0] == 0 {
            return Ok(());
        }
        if out.len() > 4096 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "request too large"));
        }
    }
}

/// Sets the client read timeout to the time left before `deadline`.
///
/// Fails with `TimedOut` when the deadline has already passed.
fn arm_read_timeout(client: &TcpStream, deadline: Instant) -> io::Result<()> {
    let remaining = deadline
        .checked_duration_since(Instant::now())
        .filter(|left| !left.is_zero())
        .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "handshake deadline exceeded"))?;
    client.set_read_timeout(Some(remaining))
}

/// `read` bounded by an absolute deadline.
pub(in crate::runtime::handshake) fn read_by(
    client: &mut TcpStream,
    buf: &mut [u8],
    deadline: Instant,
) -> io::Result<usize> {
    by_deadline(client, deadline, |client| client.read(buf))
}

/// `peek` bounded by an absolute deadline.
pub(in crate::runtime::handshake) fn peek_by(
    client: &mut TcpStream,
    buf: &mut [u8],
    deadline: Instant,
) -> io::Result<usize> {
    by_deadline(client, deadline, |client| client.peek(buf))
}

/// Runs one read-like call with the timeout left before `deadline`. The
/// socket timeout (`WouldBlock` from `SO_RCVTIMEO`) is reported as
/// `TimedOut`, the same kind as a deadline that has already passed.
fn by_deadline(
    client: &mut TcpStream,
    deadline: Instant,
    mut read: impl FnMut(&mut TcpStream) -> io::Result<usize>,
) -> io::Result<usize> {
    loop {
        arm_read_timeout(client, deadline)?;
        match read(client) {
            Err(err) if err.kind() == io::ErrorKind::Interrupted => {}
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                return Err(io::Error::new(io::ErrorKind::TimedOut, "handshake deadline exceeded"));
            }
            other => return other,
        }
    }
}

/// `read_exact` bounded by an absolute deadline rather than a per-read timeout.
pub(in crate::runtime::handshake) fn read_exact_by(
    client: &mut TcpStream,
    buf: &mut [u8],
    deadline: Instant,
) -> io::Result<()> {
    let mut filled = 0;
    while filled < buf.len() {
        match read_by(client, &mut buf[filled..], deadline)? {
            0 => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "failed to fill whole buffer")),
            n => filled += n,
        }
    }
    Ok(())
}
