// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from sagernet/sing-snell, Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>.

use std::io;
use std::net::SocketAddr;

const REQUEST_VERSION: u8 = 0x01;
/// sing-snell snellv4/client.go: v4 clients send CONNECT_V2 even without connection reuse.
const COMMAND_CONNECT_V2: u8 = 0x05;
const REPLY_TUNNEL: u8 = 0x00;
const REPLY_ERROR: u8 = 0x02;

/// Builds the request plaintext for `target` (`host:port`, `ip:port` or `[ipv6]:port`). The host is
/// always sent as a string, also for literal IPs.
pub(crate) fn encode_connect(user_key: &[u8], target: &str) -> io::Result<Vec<u8>> {
    let (host, port) = split_target(target)?;
    if host.is_empty() || host.len() > usize::from(u8::MAX) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid Snell target host"));
    }
    let user_key_len = u8::try_from(user_key.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Snell user key exceeds 255 bytes"))?;
    let mut request = Vec::with_capacity(3 + user_key.len() + 1 + host.len() + 2);
    request.extend_from_slice(&[REQUEST_VERSION, COMMAND_CONNECT_V2, user_key_len]);
    request.extend_from_slice(user_key);
    request.push(host.len() as u8);
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    Ok(request)
}

fn split_target(target: &str) -> io::Result<(String, u16)> {
    if let Ok(address) = target.parse::<SocketAddr>() {
        return Ok((address.ip().to_string(), address.port()));
    }
    let invalid = || io::Error::new(io::ErrorKind::InvalidInput, "Snell target must be host:port");
    let (host, port) = target.rsplit_once(':').ok_or_else(invalid)?;
    let port = port.parse::<u16>().map_err(|_| invalid())?;
    Ok((host.to_owned(), port))
}

/// Parses the first server record. Returns the tunnel payload that rode with the reply.
pub(crate) fn parse_reply(record: &[u8]) -> io::Result<&[u8]> {
    match record.split_first() {
        Some((&REPLY_TUNNEL, data)) => Ok(data),
        Some((&REPLY_ERROR, rest)) => {
            // sing-snell ReadServerError: exactly code, message length and message.
            let [code, len, message @ ..] = rest else {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Snell server error"));
            };
            if message.len() != usize::from(*len) {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "malformed Snell server error"));
            }
            Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("Snell server error {code}: {}", String::from_utf8_lossy(message)),
            ))
        }
        Some((reply, _)) => {
            Err(io::Error::new(io::ErrorKind::InvalidData, format!("unexpected Snell reply opcode {reply}")))
        }
        None => Err(io::Error::new(io::ErrorKind::InvalidData, "empty Snell reply")),
    }
}
