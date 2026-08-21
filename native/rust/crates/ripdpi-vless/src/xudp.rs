//! Xray-compatible XUDP datagrams over a VLESS Mux carrier.
//!
//! The outer transport is still one protected Reality/TCP connection. XUDP
//! frames preserve UDP datagram boundaries inside that reliable carrier; this
//! module never opens a direct UDP socket.

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::time::Duration;

use ring::rand::{SecureRandom, SystemRandom};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tokio::time::{Instant, MissedTickBehavior, interval, timeout};

pub const MAX_XUDP_METADATA: usize = 512;
/// Combined worst-case wire overhead mirrored from xray's XUDP payload
/// budget: a full-size datagram plus the VLESS request header and the XUDP
/// frame header must still fit one transport buffer.
const XUDP_OVERHEAD_RESERVE: usize = 666;
pub const MAX_XUDP_PAYLOAD: usize = 8192 - XUDP_OVERHEAD_RESERVE;
const READER_CHANNEL_CAPACITY: usize = 32;
const WRITER_CHANNEL_CAPACITY: usize = 32;
const DEFAULT_WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const DEFAULT_KEEPALIVE_INTERVAL: Duration = Duration::from_secs(30);
const DEFAULT_RECENT_ACTIVITY_WINDOW: Duration = Duration::from_secs(180);
const SESSION_ID: u16 = 0;
const STATUS_NEW: u8 = 0x01;
const STATUS_KEEP: u8 = 0x02;
const STATUS_KEEP_ALIVE: u8 = 0x04;
const OPTION_DATA: u8 = 0x01;
const NETWORK_UDP: u8 = 0x02;
const ADDRESS_IPV4: u8 = 0x01;
const ADDRESS_DOMAIN: u8 = 0x02;
const ADDRESS_IPV6: u8 = 0x03;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum XudpError {
    #[error("XUDP datagram payload is empty")]
    EmptyPayload,
    #[error("XUDP datagram payload is {len} bytes; maximum is {max}")]
    PayloadTooLarge { len: usize, max: usize },
    #[error("XUDP metadata is {len} bytes; expected 4..={max}")]
    InvalidMetadataLength { len: usize, max: usize },
    #[error("XUDP session id {0} is unsupported")]
    UnsupportedSessionId(u16),
    #[error("XUDP status {0} is unsupported")]
    UnsupportedStatus(u8),
    #[error("XUDP option {0} is unsupported")]
    UnsupportedOption(u8),
    #[error("XUDP network {0} is unsupported")]
    UnsupportedNetwork(u8),
    #[error("XUDP address type {0} is unsupported")]
    UnsupportedAddressType(u8),
    #[error("XUDP metadata is truncated")]
    TruncatedMetadata,
    #[error("XUDP domain is not valid UTF-8")]
    InvalidDomainUtf8,
    #[error("XUDP target is invalid")]
    InvalidTarget,
    #[error("XUDP target port is invalid")]
    InvalidPort,
    #[error("XUDP domain is {len} bytes; maximum is {max}")]
    DomainTooLong { len: usize, max: usize },
    #[error("XUDP downlink omitted its source target before one was established")]
    MissingSourceTarget,
    #[error("UDP/443 requires xtls-rprx-vision-udp443")]
    Udp443NotAllowed,
}

impl XudpError {
    fn into_input_error(self) -> io::Error {
        io::Error::new(io::ErrorKind::InvalidInput, self)
    }

    fn into_data_error(self) -> io::Error {
        io::Error::new(io::ErrorKind::InvalidData, self)
    }
}

/// One XUDP association. Cloning is intentionally unsupported: the writer,
/// downlink ordering and stable GlobalID belong to exactly one SOCKS5 UDP
/// ASSOCIATE lifecycle.
pub struct VlessXudpSession {
    writer: mpsc::Sender<WriteCommand>,
    receiver: mpsc::Receiver<io::Result<(String, Vec<u8>)>>,
    reader_task: JoinHandle<()>,
    writer_task: JoinHandle<()>,
    global_id: [u8; 8],
    first_packet: bool,
    allow_udp_443: bool,
    queue_high_water_mark: usize,
}

struct WriteCommand {
    frame: Vec<u8>,
    completion: oneshot::Sender<io::Result<()>>,
}

#[derive(Clone, Copy)]
struct XudpLivenessConfig {
    keepalive_interval: Duration,
    recent_activity_window: Duration,
}

const DEFAULT_LIVENESS: XudpLivenessConfig = XudpLivenessConfig {
    keepalive_interval: DEFAULT_KEEPALIVE_INTERVAL,
    recent_activity_window: DEFAULT_RECENT_ACTIVITY_WINDOW,
};

impl VlessXudpSession {
    pub(crate) fn new<S>(stream: S, allow_udp_443: bool) -> io::Result<Self>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        Self::new_with_liveness(stream, allow_udp_443, DEFAULT_WRITE_TIMEOUT, DEFAULT_LIVENESS)
    }

    #[cfg(test)]
    fn new_with_write_timeout<S>(stream: S, allow_udp_443: bool, write_timeout: Duration) -> io::Result<Self>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        Self::new_with_liveness(stream, allow_udp_443, write_timeout, DEFAULT_LIVENESS)
    }

    fn new_with_liveness<S>(
        stream: S,
        allow_udp_443: bool,
        write_timeout: Duration,
        liveness: XudpLivenessConfig,
    ) -> io::Result<Self>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let mut global_id = [0_u8; 8];
        SystemRandom::new().fill(&mut global_id).map_err(|_| io::Error::other("generate XUDP association id"))?;
        let (reader, writer) = tokio::io::split(stream);
        let (reader_sender, receiver) = mpsc::channel(READER_CHANNEL_CAPACITY);
        let (writer_sender, writer_receiver) = mpsc::channel(WRITER_CHANNEL_CAPACITY);
        let (reader_abort_sender, reader_abort_receiver) = oneshot::channel();
        let writer_task =
            tokio::spawn(run_writer(writer, writer_receiver, write_timeout, liveness, reader_abort_receiver));
        let reader_task = tokio::spawn(run_reader(reader, reader_sender, writer_task.abort_handle()));
        reader_abort_sender
            .send(reader_task.abort_handle())
            .map_err(|_| io::Error::other("start XUDP carrier tasks"))?;
        Ok(Self {
            writer: writer_sender,
            receiver,
            reader_task,
            writer_task,
            global_id,
            first_packet: true,
            allow_udp_443,
            queue_high_water_mark: 0,
        })
    }

    /// cancel-safe: cancellation while waiting for queue capacity sends
    /// nothing. Once capacity is reserved, enqueueing the complete frame and
    /// advancing `first_packet` happen without an await; the bounded writer
    /// task then finishes the write even if the acknowledgement wait is
    /// cancelled. A timed-out write closes the carrier instead of reusing a
    /// possibly partial frame.
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        let (_, port) = parse_target(target).map_err(XudpError::into_input_error)?;
        if port == 443 && !self.allow_udp_443 {
            return Err(XudpError::Udp443NotAllowed.into_input_error());
        }
        let frame =
            encode_datagram(self.first_packet, target, payload, self.global_id).map_err(XudpError::into_input_error)?;
        let permit = self
            .writer
            .reserve()
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "XUDP writer is closed"))?;
        let (completion, completed) = oneshot::channel();
        permit.send(WriteCommand { frame, completion });
        let queued = WRITER_CHANNEL_CAPACITY.saturating_sub(self.writer.capacity());
        self.queue_high_water_mark = self.queue_high_water_mark.max(queued);
        self.first_packet = false;
        completed.await.unwrap_or_else(|_| Err(io::Error::new(io::ErrorKind::BrokenPipe, "XUDP writer is closed")))
    }

    /// Highest number of complete datagram frames queued for the sole writer
    /// task during this association. Contains no packet contents or targets.
    pub fn queue_high_water_mark(&self) -> usize {
        self.queue_high_water_mark
    }

    /// cancel-safe: the reader task owns all partial frame state and sends only
    /// complete datagrams through a bounded channel; cancelling `recv` neither
    /// consumes nor corrupts the next queued datagram.
    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        self.receiver
            .recv()
            .await
            .unwrap_or_else(|| Err(io::Error::new(io::ErrorKind::UnexpectedEof, "XUDP carrier closed")))
    }
}

// Drop order: abort reader_task before writer_task so no downlink work can
// outlive carrier teardown; channel handles then drop in field order.
impl Drop for VlessXudpSession {
    fn drop(&mut self) {
        self.reader_task.abort();
        self.writer_task.abort();
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: `write_all` may have emitted a frame prefix. This task is
/// the sole write-half owner; aborting it also drops the carrier. Its internal
/// deadline converts a partial or stalled write into terminal carrier closure
/// before another frame can be accepted.
async fn run_writer<W>(
    mut writer: W,
    mut receiver: mpsc::Receiver<WriteCommand>,
    write_timeout: Duration,
    liveness: XudpLivenessConfig,
    reader_abort: oneshot::Receiver<tokio::task::AbortHandle>,
) where
    W: AsyncWrite + Unpin,
{
    let Ok(reader_abort) = reader_abort.await else {
        return;
    };
    let mut keepalive_interval = interval(liveness.keepalive_interval);
    keepalive_interval.set_missed_tick_behavior(MissedTickBehavior::Skip);
    keepalive_interval.tick().await;
    let mut last_application_activity = None;

    loop {
        tokio::select! {
            command = receiver.recv() => {
                let Some(command) = command else {
                    break;
                };
                let result = write_frame(&mut writer, &command.frame, write_timeout).await;
                let terminal = result.is_err();
                if result.is_ok() {
                    last_application_activity = Some(Instant::now());
                }
                let _ = command.completion.send(result);
                if terminal {
                    break;
                }
            }
            _ = keepalive_interval.tick() => {
                let recently_active = last_application_activity
                    .is_some_and(|last_activity| last_activity.elapsed() <= liveness.recent_activity_window);
                if !recently_active {
                    continue;
                }
                // Xray treats StatusKeepAlive as one-way activity and does not
                // acknowledge it. TCP keepalive/TCP_USER_TIMEOUT owns silent
                // carrier failure detection; lack of XUDP downlink is never a
                // failure signal by itself.
                if write_frame(&mut writer, &encode_keep_alive(), write_timeout).await.is_err() {
                    break;
                }
            }
        }
    }
    reader_abort.abort();
}

/// # Cancel safety
///
/// NOT cancel-safe: a cancelled write may leave a frame prefix on the carrier.
/// Only the dedicated writer task calls this helper, and every error is
/// terminal for that carrier before the next frame can be accepted.
async fn write_frame<W>(writer: &mut W, frame: &[u8], write_timeout: Duration) -> io::Result<()>
where
    W: AsyncWrite + Unpin,
{
    match timeout(write_timeout, writer.write_all(frame)).await {
        Ok(result) => result,
        Err(_) => Err(io::Error::new(io::ErrorKind::TimedOut, "XUDP carrier write timed out")),
    }
}

fn encode_datagram(first: bool, target: &str, payload: &[u8], global_id: [u8; 8]) -> Result<Vec<u8>, XudpError> {
    validate_payload(payload)?;
    let mut frame = vec![0_u8; 4];
    frame[2..4].copy_from_slice(&SESSION_ID.to_be_bytes());
    frame.push(if first { STATUS_NEW } else { STATUS_KEEP });
    frame.push(OPTION_DATA);
    frame.push(NETWORK_UDP);
    encode_target(&mut frame, target)?;
    if first {
        frame.extend_from_slice(&global_id);
    }
    let metadata_len = frame.len().checked_sub(2).ok_or(XudpError::TruncatedMetadata)?;
    if metadata_len > MAX_XUDP_METADATA {
        return Err(XudpError::InvalidMetadataLength { len: metadata_len, max: MAX_XUDP_METADATA });
    }
    let metadata_len = u16::try_from(metadata_len)
        .map_err(|_| XudpError::InvalidMetadataLength { len: metadata_len, max: MAX_XUDP_METADATA })?;
    frame[..2].copy_from_slice(&metadata_len.to_be_bytes());
    let payload_len = u16::try_from(payload.len())
        .map_err(|_| XudpError::PayloadTooLarge { len: payload.len(), max: MAX_XUDP_PAYLOAD })?;
    frame.extend_from_slice(&payload_len.to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

fn encode_keep_alive() -> [u8; 8] {
    [0, 4, 0, 0, STATUS_KEEP_ALIVE, OPTION_DATA, 0, 0]
}

fn validate_payload(payload: &[u8]) -> Result<(), XudpError> {
    if payload.is_empty() {
        return Err(XudpError::EmptyPayload);
    }
    if payload.len() > MAX_XUDP_PAYLOAD {
        return Err(XudpError::PayloadTooLarge { len: payload.len(), max: MAX_XUDP_PAYLOAD });
    }
    Ok(())
}

fn encode_target(output: &mut Vec<u8>, target: &str) -> Result<(), XudpError> {
    let (host, port) = parse_target(target)?;
    output.extend_from_slice(&port.to_be_bytes());
    if let Ok(address) = host.parse::<Ipv4Addr>() {
        output.push(ADDRESS_IPV4);
        output.extend_from_slice(&address.octets());
    } else if let Ok(address) = host.parse::<Ipv6Addr>() {
        output.push(ADDRESS_IPV6);
        output.extend_from_slice(&address.octets());
    } else {
        let domain = host.as_bytes();
        let length = u8::try_from(domain.len())
            .map_err(|_| XudpError::DomainTooLong { len: domain.len(), max: usize::from(u8::MAX) })?;
        output.push(ADDRESS_DOMAIN);
        output.push(length);
        output.extend_from_slice(domain);
    }
    Ok(())
}

fn parse_target(target: &str) -> Result<(String, u16), XudpError> {
    crate::wire::parse_target(target).map_err(|error| match error {
        crate::wire::EncodeError::InvalidPort { .. } => XudpError::InvalidPort,
        _ => XudpError::InvalidTarget,
    })
}

fn decode_target(metadata: &[u8], cursor: &mut usize) -> Result<String, XudpError> {
    let port_bytes = take(metadata, cursor, 2)?;
    let port = u16::from_be_bytes([port_bytes[0], port_bytes[1]]);
    let address_type = take(metadata, cursor, 1)?[0];
    let host = match address_type {
        ADDRESS_IPV4 => {
            let raw = take(metadata, cursor, 4)?;
            Ipv4Addr::new(raw[0], raw[1], raw[2], raw[3]).to_string()
        }
        ADDRESS_IPV6 => {
            let raw = take(metadata, cursor, 16)?;
            let mut bytes = [0_u8; 16];
            bytes.copy_from_slice(raw);
            Ipv6Addr::from(bytes).to_string()
        }
        ADDRESS_DOMAIN => {
            let length = usize::from(take(metadata, cursor, 1)?[0]);
            let raw = take(metadata, cursor, length)?;
            std::str::from_utf8(raw).map_err(|_| XudpError::InvalidDomainUtf8)?.to_owned()
        }
        other => return Err(XudpError::UnsupportedAddressType(other)),
    };
    Ok(format_target(&host, port))
}

fn take<'a>(input: &'a [u8], cursor: &mut usize, length: usize) -> Result<&'a [u8], XudpError> {
    let end = cursor.checked_add(length).ok_or(XudpError::TruncatedMetadata)?;
    let output = input.get(*cursor..end).ok_or(XudpError::TruncatedMetadata)?;
    *cursor = end;
    Ok(output)
}

fn format_target(host: &str, port: u16) -> String {
    if host.contains(':') { format!("[{host}]:{port}") } else { format!("{host}:{port}") }
}

enum DecodedFrame {
    Datagram(String, Vec<u8>),
    KeepAlive,
}

/// NOT cancel-safe: `read_exact` may consume a frame prefix. This function is
/// owned by the dedicated reader task and is never raced in `select!`; aborting
/// the task also drops the carrier, so a partial frame is never reused.
async fn read_frame<R>(reader: &mut R, last_target: &mut Option<String>) -> io::Result<DecodedFrame>
where
    R: AsyncRead + Unpin,
{
    let metadata_len = usize::from(reader.read_u16().await?);
    if !(4..=MAX_XUDP_METADATA).contains(&metadata_len) {
        return Err(XudpError::InvalidMetadataLength { len: metadata_len, max: MAX_XUDP_METADATA }.into_data_error());
    }
    let mut metadata = vec![0_u8; metadata_len];
    reader.read_exact(&mut metadata).await?;
    let session_id = u16::from_be_bytes([metadata[0], metadata[1]]);
    if session_id != SESSION_ID {
        return Err(XudpError::UnsupportedSessionId(session_id).into_data_error());
    }
    let status = metadata[2];
    let option = metadata[3];
    if option != OPTION_DATA {
        return Err(XudpError::UnsupportedOption(option).into_data_error());
    }

    let target = match status {
        STATUS_KEEP => {
            if metadata.len() > 4 {
                let network = metadata[4];
                if network != NETWORK_UDP {
                    return Err(XudpError::UnsupportedNetwork(network).into_data_error());
                }
                let mut cursor = 5;
                let target = decode_target(&metadata, &mut cursor).map_err(XudpError::into_data_error)?;
                if cursor != metadata.len() {
                    return Err(XudpError::TruncatedMetadata.into_data_error());
                }
                *last_target = Some(target.clone());
                Some(target)
            } else {
                last_target.clone()
            }
        }
        STATUS_KEEP_ALIVE => None,
        other => return Err(XudpError::UnsupportedStatus(other).into_data_error()),
    };

    let payload_len = usize::from(reader.read_u16().await?);
    if payload_len > MAX_XUDP_PAYLOAD {
        return Err(XudpError::PayloadTooLarge { len: payload_len, max: MAX_XUDP_PAYLOAD }.into_data_error());
    }
    let mut payload = vec![0_u8; payload_len];
    reader.read_exact(&mut payload).await?;
    if status == STATUS_KEEP_ALIVE {
        return Ok(DecodedFrame::KeepAlive);
    }
    validate_payload(&payload).map_err(XudpError::into_data_error)?;
    let target = target.ok_or_else(|| XudpError::MissingSourceTarget.into_data_error())?;
    Ok(DecodedFrame::Datagram(target, payload))
}

#[cfg(test)]
async fn read_datagram<R>(reader: &mut R, last_target: &mut Option<String>) -> io::Result<(String, Vec<u8>)>
where
    R: AsyncRead + Unpin,
{
    loop {
        if let DecodedFrame::Datagram(target, payload) = read_frame(reader, last_target).await? {
            return Ok((target, payload));
        }
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: this task owns the carrier read half for its complete
/// lifetime. External abort is terminal because a partially consumed carrier
/// is unusable and its paired writer must stop with it.
async fn run_reader<R>(
    mut reader: R,
    sender: mpsc::Sender<io::Result<(String, Vec<u8>)>>,
    writer_abort: tokio::task::AbortHandle,
) where
    R: AsyncRead + Unpin,
{
    let mut last_target = None;
    loop {
        match read_frame(&mut reader, &mut last_target).await {
            Ok(DecodedFrame::KeepAlive) => {}
            Ok(DecodedFrame::Datagram(target, payload)) => {
                if sender.send(Ok((target, payload))).await.is_err() {
                    writer_abort.abort();
                    return;
                }
            }
            Err(error) => {
                writer_abort.abort();
                let _ = sender.send(Err(error)).await;
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    fn keep_frame(target: Option<&str>, payload: &[u8]) -> Vec<u8> {
        let mut frame = vec![0_u8; 4];
        frame[2..4].copy_from_slice(&SESSION_ID.to_be_bytes());
        frame.push(STATUS_KEEP);
        frame.push(OPTION_DATA);
        if let Some(target) = target {
            frame.push(NETWORK_UDP);
            encode_target(&mut frame, target).expect("target");
        }
        let metadata_len = u16::try_from(frame.len() - 2).expect("metadata length");
        frame[..2].copy_from_slice(&metadata_len.to_be_bytes());
        frame.extend_from_slice(&u16::try_from(payload.len()).expect("payload length").to_be_bytes());
        frame.extend_from_slice(payload);
        frame
    }

    fn raw_frame(metadata: &[u8], payload: &[u8]) -> Vec<u8> {
        let mut frame = u16::try_from(metadata.len()).expect("metadata length").to_be_bytes().to_vec();
        frame.extend_from_slice(metadata);
        frame.extend_from_slice(&u16::try_from(payload.len()).expect("payload length").to_be_bytes());
        frame.extend_from_slice(payload);
        frame
    }

    async fn read_encoded_frame<R>(reader: &mut R) -> Vec<u8>
    where
        R: AsyncRead + Unpin,
    {
        let metadata_len = usize::from(reader.read_u16().await.expect("metadata length"));
        let mut frame = u16::try_from(metadata_len).expect("metadata length fits").to_be_bytes().to_vec();
        let mut metadata = vec![0_u8; metadata_len];
        reader.read_exact(&mut metadata).await.expect("metadata");
        frame.extend(metadata);
        let payload_len = usize::from(reader.read_u16().await.expect("payload length"));
        frame.extend(u16::try_from(payload_len).expect("payload length fits").to_be_bytes());
        let mut payload = vec![0_u8; payload_len];
        reader.read_exact(&mut payload).await.expect("payload");
        frame.extend(payload);
        frame
    }

    fn keep_alive_frame() -> Vec<u8> {
        raw_frame(&[0, 0, STATUS_KEEP_ALIVE, OPTION_DATA], &[])
    }

    fn test_liveness() -> XudpLivenessConfig {
        XudpLivenessConfig {
            keepalive_interval: Duration::from_millis(10),
            recent_activity_window: Duration::from_millis(200),
        }
    }

    #[test]
    fn new_and_keep_frames_match_xray_shape() {
        let global_id = [0x5a; 8];
        let first = encode_datagram(true, "1.2.3.4:53", b"dns", global_id).expect("new frame");
        assert_eq!(&first[..2], &20_u16.to_be_bytes());
        assert_eq!(&first[2..7], &[0, 0, STATUS_NEW, OPTION_DATA, NETWORK_UDP]);
        assert_eq!(&first[7..9], &53_u16.to_be_bytes());
        assert_eq!(first[9], ADDRESS_IPV4);
        assert_eq!(&first[10..14], &[1, 2, 3, 4]);
        assert_eq!(&first[14..22], &global_id);
        assert_eq!(&first[22..24], &3_u16.to_be_bytes());
        assert_eq!(&first[24..], b"dns");

        let keep = encode_datagram(false, "example.com:5353", b"next", global_id).expect("keep frame");
        assert_eq!(&keep[2..7], &[0, 0, STATUS_KEEP, OPTION_DATA, NETWORK_UDP]);
        assert!(!keep.windows(global_id.len()).any(|window| window == global_id));
    }

    #[test]
    fn keep_alive_frame_matches_xray_shape() {
        assert_eq!(encode_keep_alive(), [0, 4, 0, 0, STATUS_KEEP_ALIVE, OPTION_DATA, 0, 0]);
    }

    #[test]
    fn encodes_ipv6_port_before_address() {
        let frame = encode_datagram(false, "[2001:db8::1]:123", b"v6", [0; 8]).expect("IPv6 frame");
        assert_eq!(&frame[7..9], &123_u16.to_be_bytes());
        assert_eq!(frame[9], ADDRESS_IPV6);
        assert_eq!(&frame[10..26], &"2001:db8::1".parse::<Ipv6Addr>().expect("IPv6").octets());
    }

    #[test]
    fn rejects_invalid_payload_and_target_boundaries() {
        assert_eq!(encode_datagram(true, "example.com:53", b"", [0; 8]), Err(XudpError::EmptyPayload));
        assert_eq!(
            encode_datagram(true, "example.com:53", &vec![0; MAX_XUDP_PAYLOAD + 1], [0; 8]),
            Err(XudpError::PayloadTooLarge { len: MAX_XUDP_PAYLOAD + 1, max: MAX_XUDP_PAYLOAD }),
        );
        let domain = "a".repeat(256);
        assert_eq!(
            encode_datagram(true, &format!("{domain}:53"), b"x", [0; 8]),
            Err(XudpError::DomainTooLong { len: 256, max: 255 }),
        );
        assert!(encode_datagram(true, "example.com:53", &vec![0; MAX_XUDP_PAYLOAD], [0; 8]).is_ok());
    }

    #[tokio::test]
    async fn reader_preserves_and_updates_source_targets() {
        let mut bytes = keep_alive_frame();
        bytes.extend(keep_frame(Some("1.2.3.4:53"), b"one"));
        bytes.extend(keep_frame(None, b"two"));
        bytes.extend(keep_frame(Some("[2001:db8::1]:5353"), b"three"));
        let mut input = bytes.as_slice();
        let mut last = None;

        assert_eq!(
            read_datagram(&mut input, &mut last).await.expect("first"),
            ("1.2.3.4:53".to_owned(), b"one".to_vec())
        );
        assert_eq!(
            read_datagram(&mut input, &mut last).await.expect("second"),
            ("1.2.3.4:53".to_owned(), b"two".to_vec())
        );
        assert_eq!(
            read_datagram(&mut input, &mut last).await.expect("third"),
            ("[2001:db8::1]:5353".to_owned(), b"three".to_vec()),
        );
    }

    #[tokio::test]
    async fn reader_rejects_missing_target_and_unknown_wire_values() {
        let cases = [
            (raw_frame(&[0, 1, STATUS_KEEP, OPTION_DATA], b"x"), "session id"),
            (raw_frame(&[0, 0, 0x7f, OPTION_DATA], b"x"), "status"),
            (raw_frame(&[0, 0, STATUS_KEEP, 0x7f], b"x"), "option"),
            (raw_frame(&[0, 0, STATUS_KEEP, OPTION_DATA, 0x7f], b"x"), "network"),
            (raw_frame(&[0, 0, STATUS_KEEP, OPTION_DATA, NETWORK_UDP, 0, 53, 0x7f], b"x"), "address type"),
        ];
        for (frame, expected) in cases {
            let mut input = frame.as_slice();
            let error = read_datagram(&mut input, &mut None).await.expect_err("malformed frame must fail");
            assert!(error.to_string().contains(expected), "unexpected error class: {error}");
        }

        let missing_target = keep_frame(None, b"x");
        let mut input = missing_target.as_slice();
        let error = read_datagram(&mut input, &mut None).await.expect_err("first downlink target is required");
        assert!(error.to_string().contains("source target"));
    }

    #[tokio::test]
    async fn reader_rejects_truncated_metadata_payload_and_oversized_lengths() {
        let truncated_metadata = [0, 7, 0, 0, STATUS_KEEP];
        let mut input = truncated_metadata.as_slice();
        assert_eq!(
            read_datagram(&mut input, &mut None).await.expect_err("truncated metadata").kind(),
            io::ErrorKind::UnexpectedEof,
        );

        let frame = keep_frame(Some("1.2.3.4:53"), b"payload");
        let mut input = frame[..frame.len() - 1].as_ref();
        assert_eq!(
            read_datagram(&mut input, &mut None).await.expect_err("truncated payload").kind(),
            io::ErrorKind::UnexpectedEof,
        );

        let mut oversized = raw_frame(&[0, 0, STATUS_KEEP, OPTION_DATA], &[]);
        let payload_offset = oversized.len() - 2;
        oversized[payload_offset..]
            .copy_from_slice(&u16::try_from(MAX_XUDP_PAYLOAD + 1).expect("oversized payload length").to_be_bytes());
        let mut input = oversized.as_slice();
        assert_eq!(
            read_datagram(&mut input, &mut Some("1.2.3.4:53".to_owned())).await.expect_err("oversized payload").kind(),
            io::ErrorKind::InvalidData,
        );
    }

    #[tokio::test]
    async fn cancelled_receive_keeps_partial_frame_in_reader_task() {
        let (stream, mut peer) = tokio::io::duplex(128);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        let frame = keep_frame(Some("example.com:53"), b"answer");
        peer.write_all(&frame[..5]).await.expect("partial frame");
        assert!(tokio::time::timeout(std::time::Duration::from_millis(10), session.recv_from()).await.is_err());
        peer.write_all(&frame[5..]).await.expect("remaining frame");

        assert_eq!(
            session.recv_from().await.expect("complete frame"),
            ("example.com:53".to_owned(), b"answer".to_vec())
        );

        let payload_frame = keep_frame(None, b"second-answer");
        let split = payload_frame.len() - 2;
        peer.write_all(&payload_frame[..split]).await.expect("partial payload");
        assert!(tokio::time::timeout(std::time::Duration::from_millis(10), session.recv_from()).await.is_err());
        peer.write_all(&payload_frame[split..]).await.expect("remaining payload");
        assert_eq!(
            session.recv_from().await.expect("complete payload frame"),
            ("example.com:53".to_owned(), b"second-answer".to_vec())
        );
    }

    #[tokio::test]
    async fn udp_443_requires_explicit_flow_permission() {
        let (stream, _peer) = tokio::io::duplex(128);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        let error = session.send_to("example.com:443", b"quic").await.expect_err("UDP/443 must be blocked");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("vision-udp443"));

        let (stream, mut peer) = tokio::io::duplex(128);
        let mut allowed = VlessXudpSession::new(stream, true).expect("udp443-enabled session");
        allowed.send_to("example.com:443", b"quic").await.expect("vision-udp443 permits UDP/443");
        let mut encoded = [0_u8; 32];
        assert!(peer.read(&mut encoded).await.expect("read permitted UDP/443 frame") > 0);
    }

    #[tokio::test]
    async fn stalled_writer_times_out_and_closes_the_write_queue() {
        let (stream, _peer) = tokio::io::duplex(1);
        let mut session = VlessXudpSession::new_with_write_timeout(stream, false, std::time::Duration::from_millis(20))
            .expect("session");

        let error = session.send_to("example.com:53", &[0x5a; 128]).await.expect_err("stalled write must time out");

        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert_eq!(
            session.send_to("example.com:53", b"next").await.expect_err("closed writer must reject reuse").kind(),
            io::ErrorKind::BrokenPipe,
        );
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// NOT cancel-safe: the fixture uses full-frame reads, but no read is
    /// externally raced or reused after cancellation.
    async fn reader_accepts_downlink_after_uplink_only_period() {
        let (stream, mut peer) = tokio::io::duplex(128);
        let mut session = VlessXudpSession::new_with_liveness(stream, false, Duration::from_secs(1), test_liveness())
            .expect("session");
        let peer_task = tokio::spawn(async move {
            let _ = read_encoded_frame(&mut peer).await;
            for _ in 0..4 {
                assert_eq!(read_encoded_frame(&mut peer).await, encode_keep_alive());
            }
            peer.write_all(&keep_frame(Some("1.2.3.4:53"), b"alive")).await.expect("datagram");
        });

        session.send_to("1.2.3.4:53", b"query").await.expect("uplink");

        assert_eq!(
            session.recv_from().await.expect("downlink after uplink-only period"),
            ("1.2.3.4:53".into(), b"alive".to_vec())
        );
        peer_task.await.expect("peer task");
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// Cancel-safe: the timeout races a single-byte `read`, which cannot
    /// partially consume a larger protocol unit.
    async fn writer_does_not_probe_an_unused_association() {
        let (stream, mut peer) = tokio::io::duplex(128);
        let _session = VlessXudpSession::new_with_liveness(stream, false, Duration::from_secs(1), test_liveness())
            .expect("session");

        let mut byte = [0_u8; 1];
        assert!(tokio::time::timeout(Duration::from_millis(35), peer.read(&mut byte)).await.is_err());
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// NOT cancel-safe: frame reads use `read_exact`, but this test never
    /// races or externally cancels them.
    async fn discard_only_peer_does_not_make_xudp_keepalives_fatal() {
        let (stream, mut peer) = tokio::io::duplex(256);
        let mut session = VlessXudpSession::new_with_liveness(stream, false, Duration::from_secs(1), test_liveness())
            .expect("session");

        session.send_to("1.2.3.4:53", b"query").await.expect("uplink");
        let _ = read_encoded_frame(&mut peer).await;
        for _ in 0..4 {
            assert_eq!(read_encoded_frame(&mut peer).await, encode_keep_alive());
        }

        assert!(!session.writer.is_closed());
    }

    #[tokio::test]
    async fn terminal_reader_error_closes_writer_before_error_delivery_can_block() {
        let (stream, mut peer) = tokio::io::duplex(4096);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        for _ in 0..READER_CHANNEL_CAPACITY {
            peer.write_all(&keep_frame(Some("1.2.3.4:53"), b"queued")).await.expect("queued datagram");
        }
        peer.shutdown().await.expect("close peer write half");

        tokio::time::timeout(std::time::Duration::from_secs(1), async {
            while !session.writer.is_closed() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("writer must close before the terminal error is dequeued");
        assert_eq!(
            session.send_to("example.com:53", b"next").await.expect_err("writer must reject reuse").kind(),
            io::ErrorKind::BrokenPipe,
        );
    }

    #[tokio::test]
    async fn cancelled_send_finishes_in_writer_and_preserves_frame_sequence() {
        let (stream, mut peer) = tokio::io::duplex(8);
        let mut session = VlessXudpSession::new_with_write_timeout(stream, false, std::time::Duration::from_secs(1))
            .expect("session");
        {
            let first_send = session.send_to("1.2.3.4:53", &[0x41; 128]);
            tokio::pin!(first_send);
            assert!(tokio::time::timeout(std::time::Duration::from_millis(10), &mut first_send).await.is_err());
        }

        let first = read_encoded_frame(&mut peer).await;
        assert_eq!(first[4], STATUS_NEW);

        let (second_result, second) =
            tokio::join!(session.send_to("1.2.3.4:53", b"second"), read_encoded_frame(&mut peer));
        second_result.expect("second frame");
        assert_eq!(second[4], STATUS_KEEP);
        assert!(session.queue_high_water_mark() >= 1);
    }

    #[tokio::test]
    async fn reader_error_is_delivered_once_and_drop_closes_carrier() {
        let (stream, mut peer) = tokio::io::duplex(128);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        peer.write_all(&raw_frame(&[0, 0, 0x7f, OPTION_DATA], b"x")).await.expect("malformed frame");
        assert_eq!(session.recv_from().await.expect_err("reader error").kind(), io::ErrorKind::InvalidData);
        assert_eq!(session.recv_from().await.expect_err("reader closed").kind(), io::ErrorKind::UnexpectedEof);

        drop(session);
        let mut byte = [0_u8; 1];
        let read = tokio::time::timeout(std::time::Duration::from_secs(1), peer.read(&mut byte))
            .await
            .expect("carrier close timed out")
            .expect("carrier close read");
        assert_eq!(read, 0);
    }
}
