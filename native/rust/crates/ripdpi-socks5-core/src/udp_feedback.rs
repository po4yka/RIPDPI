//! Version 1 UDP feedback for an authenticated, explicitly negotiated local SOCKS hop.
//!
//! The caller owns authentication, endpoint pinning, ID monotonicity and replay limits.
//! DATA bodies remain ordinary SOCKS5 UDP frames. The TCP control stream stays idle.

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

pub const COMMAND: u8 = 0x80;
pub const MAGIC: [u8; 4] = *b"RUF1";
pub const PREFACE_LEN: usize = 20;
pub const HEADER_LEN: usize = 32;
pub const MAX_WIRE_LEN: usize = 65_507;
pub type Nonce = [u8; 16];

const REQUEST: u8 = 1;
const RESPONSE: u8 = 2;
const ERROR: u8 = 3;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RemoteError {
    pub source: IpAddr,
    pub target: SocketAddr,
    pub icmp_type: u8,
    pub icmp_code: u8,
    pub info: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Frame<'a> {
    Request { id: u64, payload: &'a [u8] },
    Response { payload: &'a [u8] },
    Error { id: u64, error: RemoteError },
}

pub fn encode_preface(nonce: &Nonce) -> [u8; PREFACE_LEN] {
    let mut output = [0; PREFACE_LEN];
    output[..4].copy_from_slice(&MAGIC);
    output[4..].copy_from_slice(nonce);
    output
}

/// # Errors
/// Returns an error if the preface has the wrong version or length.
pub fn decode_preface(preface: &[u8]) -> io::Result<Nonce> {
    if preface.len() != PREFACE_LEN || preface[..4] != MAGIC {
        return Err(invalid_data());
    }
    let mut nonce = [0; 16];
    nonce.copy_from_slice(&preface[4..]);
    Ok(nonce)
}

/// # Errors
/// Returns an error for a zero ID or a frame that exceeds the wire limit.
pub fn encode_request(nonce: &Nonce, id: u64, payload: &[u8]) -> io::Result<Vec<u8>> {
    if id == 0 {
        return Err(invalid_input());
    }
    encode(nonce, REQUEST, id, payload)
}

/// # Errors
/// Returns an error if the frame exceeds the wire limit.
pub fn encode_response(nonce: &Nonce, payload: &[u8]) -> io::Result<Vec<u8>> {
    encode(nonce, RESPONSE, 0, payload)
}

/// # Errors
/// Returns an error for an invalid ID, address, ICMP type, code or information field.
pub fn encode_error(nonce: &Nonce, id: u64, error: &RemoteError) -> io::Result<Vec<u8>> {
    if id == 0 || !valid_error(error) {
        return Err(invalid_input());
    }
    let mut body = Vec::with_capacity(43);
    body.extend_from_slice(&[family(error.source), error.icmp_type, error.icmp_code, 0]);
    body.extend_from_slice(&error.info.to_be_bytes());
    push_ip(&mut body, error.source);
    body.push(family(error.target.ip()));
    push_ip(&mut body, error.target.ip());
    body.extend_from_slice(&error.target.port().to_be_bytes());
    encode(nonce, ERROR, id, &body)
}

/// Decode one complete datagram. The caller must parse each DATA body as a SOCKS5 UDP frame.
///
/// # Errors
/// Returns an error for malformed, unsupported or wrong-association frames.
pub fn decode<'a>(frame: &'a [u8], nonce: &Nonce) -> io::Result<Frame<'a>> {
    if !(HEADER_LEN..=MAX_WIRE_LEN).contains(&frame.len()) || frame[..4] != MAGIC || frame[5] != 0 {
        return Err(invalid_data());
    }
    let body_len = usize::from(u16::from_be_bytes([frame[6], frame[7]]));
    if frame.len() != HEADER_LEN + body_len {
        return Err(invalid_data());
    }
    let difference = frame[8..24].iter().zip(nonce).fold(0, |acc, (actual, expected)| acc | (actual ^ expected));
    if difference != 0 {
        return Err(invalid_data());
    }
    let mut id_bytes = [0; 8];
    id_bytes.copy_from_slice(&frame[24..32]);
    let id = u64::from_be_bytes(id_bytes);
    let body = &frame[HEADER_LEN..];
    match (frame[4], id) {
        (REQUEST, 1..) => Ok(Frame::Request { id, payload: body }),
        (RESPONSE, 0) => Ok(Frame::Response { payload: body }),
        (ERROR, 1..) => Ok(Frame::Error { id, error: decode_error(body)? }),
        _ => Err(invalid_data()),
    }
}

fn encode(nonce: &Nonce, kind: u8, id: u64, body: &[u8]) -> io::Result<Vec<u8>> {
    if body.len() > MAX_WIRE_LEN - HEADER_LEN {
        return Err(invalid_input());
    }
    let mut output = Vec::with_capacity(HEADER_LEN + body.len());
    output.extend_from_slice(&MAGIC);
    output.extend_from_slice(&[kind, 0]);
    output.extend_from_slice(&(body.len() as u16).to_be_bytes());
    output.extend_from_slice(nonce);
    output.extend_from_slice(&id.to_be_bytes());
    output.extend_from_slice(body);
    Ok(output)
}

fn decode_error(body: &[u8]) -> io::Result<RemoteError> {
    if body.len() < 8 || body[3] != 0 {
        return Err(invalid_data());
    }
    let info = u32::from_be_bytes([body[4], body[5], body[6], body[7]]);
    let mut tail = &body[8..];
    let source = take_ip(body[0], &mut tail)?;
    let (&target_family, rest) = tail.split_first().ok_or_else(invalid_data)?;
    tail = rest;
    let target = take_ip(target_family, &mut tail)?;
    let [high, low] = *tail else { return Err(invalid_data()) };
    let error = RemoteError {
        source,
        target: SocketAddr::new(target, u16::from_be_bytes([high, low])),
        icmp_type: body[1],
        icmp_code: body[2],
        info,
    };
    valid_error(&error).then_some(error).ok_or_else(invalid_data)
}

fn valid_error(error: &RemoteError) -> bool {
    if !is_unicast(error.source)
        || !is_unicast(error.target.ip())
        || error.target.port() == 0
        || error.source.is_ipv4() != error.target.is_ipv4()
    {
        return false;
    }
    match (error.source, error.icmp_type, error.icmp_code) {
        (IpAddr::V4(_), 3, 4) => error.info <= u32::from(u16::MAX),
        (IpAddr::V4(_), 3, 0..=15) | (IpAddr::V4(_), 11, 0) => error.info == 0,
        (IpAddr::V6(_), 2, 0) => error.info != 0,
        (IpAddr::V6(_), 1, 0..=6) | (IpAddr::V6(_), 3, 0..=1) => error.info == 0,
        _ => false,
    }
}

fn family(address: IpAddr) -> u8 {
    if address.is_ipv4() { 4 } else { 6 }
}

fn push_ip(output: &mut Vec<u8>, address: IpAddr) {
    match address {
        IpAddr::V4(address) => output.extend_from_slice(&address.octets()),
        IpAddr::V6(address) => output.extend_from_slice(&address.octets()),
    }
}

fn take_ip(family: u8, tail: &mut &[u8]) -> io::Result<IpAddr> {
    match family {
        4 => {
            let (raw, rest) = tail.split_at_checked(4).ok_or_else(invalid_data)?;
            *tail = rest;
            Ok(IpAddr::V4(Ipv4Addr::new(raw[0], raw[1], raw[2], raw[3])))
        }
        6 => {
            let (raw, rest) = tail.split_at_checked(16).ok_or_else(invalid_data)?;
            let mut address = [0; 16];
            address.copy_from_slice(raw);
            *tail = rest;
            Ok(IpAddr::V6(Ipv6Addr::from(address)))
        }
        _ => Err(invalid_data()),
    }
}

fn is_unicast(address: IpAddr) -> bool {
    !address.is_unspecified() && !address.is_multicast() && !matches!(address, IpAddr::V4(ip) if ip.is_broadcast())
}

fn invalid_data() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "invalid UDP feedback frame")
}

fn invalid_input() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "invalid UDP feedback input")
}

#[cfg(test)]
mod tests;
