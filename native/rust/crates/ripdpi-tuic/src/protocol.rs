use std::io;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;

use bytes::{BufMut, BytesMut};
use quinn::SendStream;
use uuid::Uuid;

use crate::config::Config;

pub(crate) const TUIC_VERSION: u8 = 0x05;
pub(crate) const COMMAND_AUTHENTICATE: u8 = 0x00;
pub(crate) const COMMAND_CONNECT: u8 = 0x01;
pub(crate) const COMMAND_PACKET: u8 = 0x02;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum TuicAddress {
    None,
    Domain(String, u16),
    Socket(SocketAddr),
}

impl TuicAddress {
    pub(crate) fn from_authority(authority: &str) -> io::Result<Self> {
        if authority.trim().is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "TUIC authority must not be blank"));
        }
        if let Ok(socket) = SocketAddr::from_str(authority.trim()) {
            return Ok(Self::Socket(socket));
        }

        let (host, port) = split_authority(authority)?;
        let port = port.parse::<u16>().map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC authority port {port}: {error}"))
        })?;
        if let Ok(ip) = IpAddr::from_str(&host) {
            return Ok(Self::Socket(SocketAddr::new(ip, port)));
        }

        if host.len() > usize::from(u8::MAX) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "TUIC domain authorities are limited to 255 bytes",
            ));
        }

        Ok(Self::Domain(host, port))
    }

    pub(crate) fn to_authority(&self) -> io::Result<String> {
        match self {
            Self::None => {
                Err(io::Error::new(io::ErrorKind::InvalidData, "TUIC packet fragment is missing its address"))
            }
            Self::Domain(host, port) => Ok(format!("{host}:{port}")),
            Self::Socket(socket) => Ok(socket.to_string()),
        }
    }

    pub(crate) fn encoded_len(&self) -> usize {
        1 + match self {
            Self::None => 0,
            Self::Domain(host, _) => 1 + host.len() + 2,
            Self::Socket(SocketAddr::V4(_)) => 4 + 2,
            Self::Socket(SocketAddr::V6(_)) => 16 + 2,
        }
    }

    pub(crate) fn encode(&self, buffer: &mut BytesMut) {
        match self {
            Self::None => buffer.put_u8(0xff),
            Self::Domain(host, port) => {
                buffer.put_u8(0x00);
                buffer.put_u8(host.len() as u8);
                buffer.extend_from_slice(host.as_bytes());
                buffer.put_u16(*port);
            }
            Self::Socket(SocketAddr::V4(address)) => {
                buffer.put_u8(0x01);
                buffer.extend_from_slice(&address.ip().octets());
                buffer.put_u16(address.port());
            }
            Self::Socket(SocketAddr::V6(address)) => {
                buffer.put_u8(0x02);
                for segment in address.ip().segments() {
                    buffer.put_u16(segment);
                }
                buffer.put_u16(address.port());
            }
        }
    }

    pub(crate) fn decode(input: &mut &[u8]) -> io::Result<Self> {
        let Some((&kind, rest)) = input.split_first() else {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "missing TUIC address kind"));
        };
        *input = rest;

        match kind {
            0xff => Ok(Self::None),
            0x00 => {
                let length = read_u8(input)? as usize;
                let host_bytes = take_bytes(input, length)?;
                let port = read_u16(input)?;
                let host = String::from_utf8(host_bytes.to_vec())
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "TUIC domain name is not valid UTF-8"))?;
                Ok(Self::Domain(host, port))
            }
            0x01 => {
                let octets = take_bytes(input, 4)?;
                let port = read_u16(input)?;
                Ok(Self::Socket(SocketAddr::from(([octets[0], octets[1], octets[2], octets[3]], port))))
            }
            0x02 => {
                let octets = take_bytes(input, 16)?;
                let port = read_u16(input)?;
                let ip = [
                    u16::from_be_bytes([octets[0], octets[1]]),
                    u16::from_be_bytes([octets[2], octets[3]]),
                    u16::from_be_bytes([octets[4], octets[5]]),
                    u16::from_be_bytes([octets[6], octets[7]]),
                    u16::from_be_bytes([octets[8], octets[9]]),
                    u16::from_be_bytes([octets[10], octets[11]]),
                    u16::from_be_bytes([octets[12], octets[13]]),
                    u16::from_be_bytes([octets[14], octets[15]]),
                ];
                Ok(Self::Socket(SocketAddr::from((ip, port))))
            }
            _ => Err(io::Error::new(io::ErrorKind::InvalidData, format!("unsupported TUIC address kind {kind:#x}"))),
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct PacketHeader {
    pub(crate) assoc_id: u16,
    pub(crate) packet_id: u16,
    pub(crate) fragment_total: u8,
    pub(crate) fragment_id: u8,
    pub(crate) payload_len: u16,
    pub(crate) address: TuicAddress,
}

impl PacketHeader {
    pub(crate) fn encoded_len(&self) -> usize {
        2 + 2 + 1 + 1 + 2 + self.address.encoded_len()
    }

    pub(crate) fn encode(&self, buffer: &mut BytesMut) {
        buffer.put_u8(TUIC_VERSION);
        buffer.put_u8(COMMAND_PACKET);
        buffer.put_u16(self.assoc_id);
        buffer.put_u16(self.packet_id);
        buffer.put_u8(self.fragment_total);
        buffer.put_u8(self.fragment_id);
        buffer.put_u16(self.payload_len);
        self.address.encode(buffer);
    }

    pub(crate) fn decode(datagram: &[u8]) -> io::Result<(Self, &[u8])> {
        let mut input = datagram;
        if read_u8(&mut input)? != TUIC_VERSION {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TUIC packet version"));
        }
        if read_u8(&mut input)? != COMMAND_PACKET {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TUIC packet command"));
        }

        let header = Self {
            assoc_id: read_u16(&mut input)?,
            packet_id: read_u16(&mut input)?,
            fragment_total: read_u8(&mut input)?.max(1),
            fragment_id: read_u8(&mut input)?,
            payload_len: read_u16(&mut input)?,
            address: TuicAddress::decode(&mut input)?,
        };
        let payload = take_bytes(&mut input, usize::from(header.payload_len))?;
        Ok((header, payload))
    }
}

pub(crate) async fn authenticate_connection(connection: &quinn::Connection, config: &Config) -> io::Result<()> {
    let uuid = Uuid::parse_str(config.uuid.trim())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC UUID: {error}")))?;
    let mut token = [0u8; 32];
    connection
        .export_keying_material(&mut token, uuid.as_bytes(), config.password.as_bytes())
        .map_err(|error| io::Error::other(format!("TUIC exporter failed: {error:?}")))?;

    let mut payload = BytesMut::with_capacity(2 + 16 + 32);
    payload.put_u8(TUIC_VERSION);
    payload.put_u8(COMMAND_AUTHENTICATE);
    payload.extend_from_slice(uuid.as_bytes());
    payload.extend_from_slice(&token);

    let mut send = connection.open_uni().await?;
    write_auth_payload(&mut send, &payload).await?;
    send.finish()?;
    Ok(())
}

async fn write_auth_payload(send: &mut SendStream, payload: &[u8]) -> io::Result<()> {
    send.write_all(payload).await.map_err(io::Error::other)
}

fn split_authority(authority: &str) -> io::Result<(String, String)> {
    let trimmed = authority.trim();
    if trimmed.starts_with('[') {
        let end = trimmed
            .find(']')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid bracketed authority"))?;
        let host = trimmed[1..end].to_owned();
        let remainder = trimmed[end + 1..]
            .strip_prefix(':')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid bracketed authority port"))?;
        return Ok((host, remainder.to_owned()));
    }

    trimmed
        .rsplit_once(':')
        .map(|(host, port)| (host.to_owned(), port.to_owned()))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "authority must include a port"))
}

fn read_u8(input: &mut &[u8]) -> io::Result<u8> {
    let Some((&value, rest)) = input.split_first() else {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of TUIC payload"));
    };
    *input = rest;
    Ok(value)
}

fn read_u16(input: &mut &[u8]) -> io::Result<u16> {
    let bytes = take_bytes(input, 2)?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn take_bytes<'a>(input: &mut &'a [u8], count: usize) -> io::Result<&'a [u8]> {
    if input.len() < count {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of TUIC payload"));
    }
    let (head, tail) = input.split_at(count);
    *input = tail;
    Ok(head)
}
