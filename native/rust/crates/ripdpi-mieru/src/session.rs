//! Mieru TCP-carrier session: the open-session handshake, data-segment
//! read/write helpers (control segments are consumed transparently), the
//! in-tunnel SOCKS5 connect, and the duplex pump that presents the tunnel as a
//! plain byte stream. See `PROTOCOL.md` §5.

use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::Arc;

use ripdpi_network_time::NetworkTimeProvider;
use tokio::io::{AsyncRead, AsyncWrite};

use crate::cipher::KEY_LEN;
use crate::error::{MieruError, Result};
use crate::metadata::{DataAckMeta, ProtocolType, SessionMeta, timestamp_estimate_unix};
use crate::segment::{Decryptor, Encryptor, MAX_PDU};

/// Outbound data path: owns the encryptor, the (boxed) transport write half,
/// and the per-segment header fields. `time` stamps each segment's metadata
/// timestamp with the current network time (not a value frozen at open).
pub(crate) struct DataWriter {
    enc: Encryptor,
    writer: Box<dyn AsyncWrite + Unpin + Send>,
    session_id: u32,
    time: Arc<NetworkTimeProvider>,
    seq: u32,
}

impl DataWriter {
    fn new(
        enc: Encryptor,
        writer: Box<dyn AsyncWrite + Unpin + Send>,
        session_id: u32,
        time: Arc<NetworkTimeProvider>,
        seq: u32,
    ) -> Self {
        Self { enc, writer, session_id, time, seq }
    }

    /// Send the one-shot open-session request (no piggybacked payload).
    async fn send_open(&mut self) -> Result<()> {
        // Session metadata has no prefix-padding field, so prefix padding MUST be
        // zero on session segments (only suffix padding is length-delimited).
        let (_, suffix) = self.enc.next_padding()?;
        let session_id = self.session_id;
        let now = self.time.now_unix();
        let seq = self.next_seq();
        self.enc
            .write_segment(
                &mut self.writer,
                0,
                suffix,
                |_p, suffix_len| {
                    SessionMeta {
                        protocol: ProtocolType::OpenSessionRequest,
                        session_id,
                        seq,
                        status_code: 0,
                        payload_len: 0,
                        suffix_len,
                    }
                    .marshal(now)
                },
                None,
            )
            .await
    }

    fn next_seq(&mut self) -> u32 {
        let seq = self.seq;
        self.seq = self.seq.wrapping_add(1);
        seq
    }

    /// Send `bytes` as one or more `dataClientToServer` segments, each carrying
    /// a raw stream fragment of at most `MAX_PDU` bytes. UDP association
    /// encapsulation is not part of a TCP stream's payload.
    pub(crate) async fn send_data(&mut self, bytes: &[u8]) -> Result<()> {
        for chunk in bytes.chunks(MAX_PDU) {
            let (prefix, suffix) = self.enc.next_padding()?;
            let session_id = self.session_id;
            let now = self.time.now_unix();
            let seq = self.next_seq();
            let payload_len =
                u16::try_from(chunk.len()).map_err(|_| MieruError::Protocol("fragment too large".to_owned()))?;
            self.enc
                .write_segment(
                    &mut self.writer,
                    prefix,
                    suffix,
                    |prefix_len, suffix_len| {
                        DataAckMeta {
                            protocol: ProtocolType::DataClientToServer,
                            session_id,
                            seq,
                            unack_seq: 0,
                            window_size: 0,
                            fragment: 0,
                            prefix_len,
                            payload_len,
                            suffix_len,
                        }
                        .marshal(now)
                    },
                    Some(chunk),
                )
                .await?;
        }
        Ok(())
    }
}

/// Inbound data path: owns the decryptor, the (boxed) transport read half, and
/// a buffer of decrypted bytes not yet consumed by the caller. Control
/// segments (open/close session responses) are skipped transparently.
pub(crate) struct DataReader {
    dec: Decryptor,
    reader: Box<dyn AsyncRead + Unpin + Send>,
    buffer: Vec<u8>,
    pos: usize,
    /// Shared network-time source; calibrated once from the first inbound
    /// server segment so future sessions derive keys from network time even if
    /// the device clock is wrong.
    time: Arc<NetworkTimeProvider>,
    calibrated: bool,
}

impl DataReader {
    fn new(dec: Decryptor, reader: Box<dyn AsyncRead + Unpin + Send>, time: Arc<NetworkTimeProvider>) -> Self {
        Self { dec, reader, buffer: Vec::new(), pos: 0, time, calibrated: false }
    }

    /// Pull the next decrypted data fragment into the buffer, skipping any
    /// control segments. Returns `false` on clean EOF.
    async fn fill(&mut self) -> Result<bool> {
        loop {
            let (meta, payload) = match self.dec.read_segment(&mut self.reader).await {
                Ok(segment) => segment,
                Err(MieruError::Io(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(false),
                Err(e) => return Err(e),
            };
            // Calibrate the shared replay clock once per session from the
            // server's own (authenticated) timestamp. Done a single time: the
            // minute-granularity field would otherwise re-anchor mid-minute and
            // make network time jitter backwards. The segment is already AEAD-
            // verified at this point, so the timestamp is trusted.
            if !self.calibrated {
                self.time.calibrate(timestamp_estimate_unix(&meta));
                self.calibrated = true;
            }
            let protocol = ProtocolType::from_u8(meta[0])?;
            match protocol {
                ProtocolType::DataServerToClient | ProtocolType::DataClientToServer => {
                    if let Some(blob) = payload {
                        self.buffer = blob;
                        self.pos = 0;
                        if !self.buffer.is_empty() {
                            return Ok(true);
                        }
                    }
                }
                ProtocolType::CloseSessionRequest
                | ProtocolType::CloseSessionResponse
                | ProtocolType::CloseConnRequest => {
                    return Ok(false);
                }
                // open/close responses and acks: transparently skip.
                _ => {}
            }
        }
    }

    /// Read up to `out.len()` bytes from the tunnel; returns bytes read (0 = EOF).
    pub(crate) async fn read(&mut self, out: &mut [u8]) -> Result<usize> {
        if self.pos >= self.buffer.len() && !self.fill().await? {
            return Ok(0);
        }
        let available = &self.buffer[self.pos..];
        let n = available.len().min(out.len());
        out[..n].copy_from_slice(&available[..n]);
        self.pos += n;
        Ok(n)
    }

    /// Read exactly `out.len()` bytes or fail with EOF (used by SOCKS5 framing).
    async fn read_exact_tunnel(&mut self, out: &mut [u8]) -> Result<()> {
        let mut filled = 0;
        while filled < out.len() {
            let n = self.read(&mut out[filled..]).await?;
            if n == 0 {
                return Err(MieruError::Socks5("unexpected EOF during SOCKS5 negotiation".to_owned()));
            }
            filled += n;
        }
        Ok(())
    }
}

/// Derive the session key (current 2-minute window) and run the open-session
/// handshake over `transport`, returning the split data paths. The replay clock
/// comes from the shared [`NetworkTimeProvider`] (NOT the device wall clock); the
/// handshake time is captured once here, and the reader calibrates the provider
/// from the server's first segment for subsequent sessions.
pub(crate) async fn open_session<T>(
    transport: T,
    password: &[u8],
    username: &[u8],
    session_id: u32,
    time: Arc<NetworkTimeProvider>,
) -> Result<(DataWriter, DataReader)>
where
    T: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let key: [u8; KEY_LEN] = crate::cipher::derive_key(password, username, time.now_unix(), 0);
    let (read_half, write_half) = tokio::io::split(transport);
    let enc = Encryptor::new(key, username.to_vec());
    let dec = Decryptor::new(key);
    let mut writer = DataWriter::new(enc, Box::new(write_half), session_id, Arc::clone(&time), 0);
    let reader = DataReader::new(dec, Box::new(read_half), time);
    writer.send_open().await?;
    Ok((writer, reader))
}

/// Perform the in-tunnel SOCKS5 client handshake + CONNECT to `target`
/// (`host:port`). The mieru server runs a SOCKS5 server inside the session.
pub(crate) async fn socks5_connect(writer: &mut DataWriter, reader: &mut DataReader, target: &str) -> Result<()> {
    let (host, port) = split_host_port(target)?;

    // Mieru authenticates the user at the carrier layer. The upstream server
    // skips SOCKS method negotiation and expects the CONNECT request directly.
    let mut request = vec![0x05, 0x01, 0x00];
    encode_socks5_address(&mut request, host, port)?;
    writer.send_data(&request).await?;

    // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT.
    let mut head = [0u8; 4];
    reader.read_exact_tunnel(&mut head).await?;
    if head[0] != 0x05 {
        return Err(MieruError::Socks5(format!("bad SOCKS5 reply version {}", head[0])));
    }
    if head[1] != 0x00 {
        return Err(MieruError::Socks5(format!("SOCKS5 CONNECT failed, reply code {}", head[1])));
    }
    let addr_len = match head[3] {
        0x01 => 4,
        0x04 => 16,
        0x03 => {
            let mut len = [0u8; 1];
            reader.read_exact_tunnel(&mut len).await?;
            usize::from(len[0])
        }
        other => return Err(MieruError::Socks5(format!("unknown SOCKS5 reply atyp {other}"))),
    };
    let mut rest = vec![0u8; addr_len + 2]; // bound address + port
    reader.read_exact_tunnel(&mut rest).await?;
    Ok(())
}

pub(crate) fn split_host_port(target: &str) -> Result<(&str, u16)> {
    let (host, port) =
        target.rsplit_once(':').ok_or_else(|| MieruError::Socks5(format!("target `{target}` missing port")))?;
    if !host.starts_with('[') && host.contains(':') {
        return Err(MieruError::Socks5(format!("unbracketed IPv6 target `{target}` must use bracketed form")));
    }
    let port: u16 = port.parse().map_err(|_| MieruError::Socks5(format!("bad port in `{target}`")))?;
    let host = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')).unwrap_or(host);
    Ok((host, port))
}

pub(crate) fn encode_socks5_address(out: &mut Vec<u8>, host: &str, port: u16) -> Result<()> {
    if let Ok(v4) = host.parse::<Ipv4Addr>() {
        out.push(0x01);
        out.extend_from_slice(&v4.octets());
    } else if let Ok(v6) = host.parse::<Ipv6Addr>() {
        out.push(0x04);
        out.extend_from_slice(&v6.octets());
    } else {
        let bytes = host.as_bytes();
        let len = u8::try_from(bytes.len()).map_err(|_| MieruError::Socks5("domain too long".to_owned()))?;
        out.push(0x03);
        out.push(len);
        out.extend_from_slice(bytes);
    }
    out.extend_from_slice(&port.to_be_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_port_split_handles_v6_brackets() {
        assert_eq!(split_host_port("example.com:443").unwrap(), ("example.com", 443));
        assert_eq!(split_host_port("[::1]:8080").unwrap(), ("::1", 8080));
        assert!(split_host_port("noport").is_err());
    }

    #[test]
    fn socks5_address_encodes_each_atyp() {
        let mut v4 = Vec::new();
        encode_socks5_address(&mut v4, "127.0.0.1", 80).unwrap();
        assert_eq!(v4, vec![0x01, 127, 0, 0, 1, 0x00, 80]);

        let mut domain = Vec::new();
        encode_socks5_address(&mut domain, "ex.com", 443).unwrap();
        assert_eq!(domain[0], 0x03);
        assert_eq!(domain[1], 6);
        assert_eq!(&domain[2..8], b"ex.com");
        assert_eq!(&domain[8..], &[0x01, 0xbb]);
    }
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::split_host_port;

    /// Regression test (audit H4 siblings): a bare IPv6 literal must be
    /// rejected instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) with a bogus port.
    #[test]
    fn split_host_port_rejects_bare_ipv6_target() {
        assert!(split_host_port("2001:db8::1").is_err(), "bare IPv6 target must be rejected");
        assert!(split_host_port("2001:db8::1:443").is_err(), "unbracketed IPv6 with port must be rejected");
    }

    #[test]
    fn split_host_port_accepts_bracketed_ipv6_and_domain() {
        let bracketed = split_host_port("[2001:db8::1]:443").expect("bracketed IPv6 target parses");
        assert_eq!(bracketed, ("2001:db8::1", 443));
        let domain = split_host_port("example.com:443").expect("domain target parses");
        assert_eq!(domain, ("example.com", 443));
    }
}
