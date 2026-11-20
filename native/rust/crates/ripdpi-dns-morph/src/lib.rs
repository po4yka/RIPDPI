//! DNS-Morph bootstrap channel, protocol version 1: a signed subscription payload delivered as TXT
//! answers on UDP/53. The wire contract is in `docs/server-integration.md` ("DNS-Morph bootstrap channel").
//! [`fetch`] is the client; [`Responder`] is the bridge side used by the reference bridge and by tests.
#![forbid(unsafe_code)]

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::os::fd::{AsRawFd, RawFd};
use std::time::{Duration, Instant};

use data_encoding::BASE32_DNSSEC;
use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::TXT;
use hickory_proto::rr::{DNSClass, Name, RData, Record, RecordType};
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{ED25519, Ed25519KeyPair, KeyPair, UnparsedPublicKey};

pub const VERSION: u8 = 1;
pub const NONCE_LEN: usize = 8;
const REQUEST_LEN: usize = 1 + NONCE_LEN + 2;
/// Envelope bytes per TXT answer; keeps every response below [`MAX_DATAGRAM_LEN`].
pub const CHUNK_LEN: usize = 180;
const CHUNK_HEADER_LEN: usize = 4 + 2;
pub const SIGNATURE_LEN: usize = 64;
pub const MAX_PAYLOAD_LEN: usize = 64 * 1024;
const MAX_ENVELOPE_LEN: usize = MAX_PAYLOAD_LEN + SIGNATURE_LEN;
/// Classic DNS UDP limit; a larger datagram is rejected, never parsed.
pub const MAX_DATAGRAM_LEN: usize = 512;
/// Keeps an uncompressed reply under [`MAX_DATAGRAM_LEN`].
const MAX_ZONE_LEN: usize = 100;
const SIGNING_CONTEXT: &[u8] = b"ripdpi-dns-morph-v1\0";
const ATTEMPT_TIMEOUT: Duration = Duration::from_secs(2);
const ATTEMPTS: u32 = 3;
/// Whole-fetch budget for a [`MAX_PAYLOAD_LEN`] payload at stop-and-wait pace.
pub const FETCH_DEADLINE: Duration = Duration::from_secs(90);

#[derive(Debug, thiserror::Error)]
pub enum FetchError {
    #[error("invalid DNS-Morph bridge: {0}")]
    InvalidBridge(&'static str),
    #[error("protect DNS-Morph socket: {0}")]
    Protect(io::Error),
    #[error("DNS-Morph socket: {0}")]
    Io(#[from] io::Error),
    #[error("DNS-Morph bridge did not answer before the deadline")]
    Timeout,
    #[error("DNS-Morph bridge answered {0:?}")]
    Rejected(ResponseCode),
    #[error("DNS-Morph datagram exceeds {MAX_DATAGRAM_LEN} bytes")]
    OversizedDatagram,
    #[error("DNS-Morph payload exceeds {MAX_PAYLOAD_LEN} bytes")]
    PayloadTooLarge,
    #[error("malformed DNS-Morph response: {0}")]
    Malformed(&'static str),
    #[error("DNS-Morph payload signature is invalid")]
    BadSignature,
}

/// A bridge declared by an imported bundle: an IP literal endpoint, its zone and its pinned Ed25519 key.
#[derive(Debug, Clone)]
pub struct Bridge {
    address: SocketAddr,
    zone: Name,
    public_key: [u8; 32],
}

impl Bridge {
    pub fn new(address: SocketAddr, zone: &str, public_key: [u8; 32]) -> Result<Self, FetchError> {
        if address.port() == 0 || address.ip().is_unspecified() || address.ip().is_multicast() {
            return Err(FetchError::InvalidBridge("address"));
        }
        Ok(Self { address, zone: parse_zone(zone)?, public_key })
    }
}

/// Fetches and verifies the bridge payload. `protect` runs on the socket before its first datagram.
pub fn fetch(
    bridge: &Bridge,
    deadline: Duration,
    protect: impl FnOnce(RawFd) -> io::Result<()>,
) -> Result<Vec<u8>, FetchError> {
    let deadline = Instant::now() + deadline;
    let local: SocketAddr = match bridge.address {
        SocketAddr::V4(_) => (Ipv4Addr::UNSPECIFIED, 0).into(),
        SocketAddr::V6(_) => (Ipv6Addr::UNSPECIFIED, 0).into(),
    };
    let socket = UdpSocket::bind(local)?;
    protect(socket.as_raw_fd()).map_err(FetchError::Protect)?;
    socket.connect(bridge.address)?;

    let rng = SystemRandom::new();
    let mut nonce = [0u8; NONCE_LEN];
    rng.fill(&mut nonce).map_err(|_| io::Error::other("random nonce"))?;
    let mut envelope = Vec::new();
    let mut total: Option<usize> = None;
    let mut index: u16 = 0;
    while total.is_none_or(|total| envelope.len() < total) {
        let name = request_name(&bridge.zone, nonce, index)?;
        let chunk = exchange(&socket, &rng, name, deadline)?;
        let (chunk_total, chunk_index, data) = split_chunk(&chunk)?;
        if chunk_total > MAX_ENVELOPE_LEN {
            return Err(FetchError::PayloadTooLarge);
        }
        if chunk_index != index || chunk_total <= SIGNATURE_LEN {
            return Err(FetchError::Malformed("chunk header"));
        }
        if *total.get_or_insert(chunk_total) != chunk_total {
            return Err(FetchError::Malformed("envelope length changed"));
        }
        if data.len() != (chunk_total - envelope.len()).min(CHUNK_LEN) {
            return Err(FetchError::Malformed("chunk length"));
        }
        envelope.extend_from_slice(data);
        index = index.checked_add(1).ok_or(FetchError::Malformed("chunk count"))?;
    }
    let (payload, signature) = envelope.split_at(envelope.len() - SIGNATURE_LEN);
    UnparsedPublicKey::new(&ED25519, bridge.public_key)
        .verify(&signed_message(&nonce, payload), signature)
        .map_err(|_| FetchError::BadSignature)?;
    Ok(payload.to_vec())
}

/// Stop-and-wait exchange of one chunk query. Datagrams that do not answer this query are ignored.
fn exchange(socket: &UdpSocket, rng: &SystemRandom, name: Name, deadline: Instant) -> Result<Vec<u8>, FetchError> {
    let mut buffer = [0u8; MAX_DATAGRAM_LEN + 1];
    for _ in 0..ATTEMPTS {
        let mut id = [0u8; 2];
        rng.fill(&mut id).map_err(|_| io::Error::other("random query id"))?;
        let mut query = Message::new(u16::from_be_bytes(id), MessageType::Query, OpCode::Query);
        query.add_query(Query::new(name.clone(), RecordType::TXT));
        socket.send(&query.to_vec().map_err(|_| FetchError::Malformed("encode query"))?)?;
        let attempt_end = (Instant::now() + ATTEMPT_TIMEOUT).min(deadline);
        while let Some(wait) = attempt_end.checked_duration_since(Instant::now()).filter(|wait| !wait.is_zero()) {
            socket.set_read_timeout(Some(wait))?;
            let len = match socket.recv(&mut buffer) {
                Ok(len) => len,
                Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => break,
                Err(error) => return Err(error.into()),
            };
            if len > MAX_DATAGRAM_LEN {
                return Err(FetchError::OversizedDatagram);
            }
            let Ok(response) = Message::from_vec(&buffer[..len]) else { continue };
            if response.metadata.id == query.metadata.id
                && response.metadata.message_type == MessageType::Response
                && response.queries == query.queries
            {
                return single_txt(response);
            }
        }
        if Instant::now() >= deadline {
            break;
        }
    }
    Err(FetchError::Timeout)
}

fn single_txt(response: Message) -> Result<Vec<u8>, FetchError> {
    if response.metadata.response_code != ResponseCode::NoError {
        return Err(FetchError::Rejected(response.metadata.response_code));
    }
    if response.metadata.truncation {
        return Err(FetchError::Malformed("truncated"));
    }
    match response.answers.as_slice() {
        [record] if record.dns_class == DNSClass::IN => match &record.data {
            RData::TXT(txt) => match &*txt.txt_data {
                [data] => Ok(data.to_vec()),
                _ => Err(FetchError::Malformed("TXT strings")),
            },
            _ => Err(FetchError::Malformed("answer type")),
        },
        _ => Err(FetchError::Malformed("answer count")),
    }
}

fn split_chunk(chunk: &[u8]) -> Result<(usize, u16, &[u8]), FetchError> {
    let (header, data) = chunk.split_at_checked(CHUNK_HEADER_LEN).ok_or(FetchError::Malformed("chunk header"))?;
    let total = u32::from_be_bytes([header[0], header[1], header[2], header[3]]);
    let total = usize::try_from(total).map_err(|_| FetchError::PayloadTooLarge)?;
    Ok((total, u16::from_be_bytes([header[4], header[5]]), data))
}

fn request_name(zone: &Name, nonce: [u8; NONCE_LEN], index: u16) -> Result<Name, FetchError> {
    let mut request = [0u8; REQUEST_LEN];
    request[0] = VERSION;
    request[1..=NONCE_LEN].copy_from_slice(&nonce);
    request[NONCE_LEN + 1..].copy_from_slice(&index.to_be_bytes());
    zone.prepend_label(BASE32_DNSSEC.encode(&request)).map_err(|_| FetchError::InvalidBridge("zone"))
}

fn signed_message(nonce: &[u8], payload: &[u8]) -> Vec<u8> {
    [SIGNING_CONTEXT, nonce, payload].concat()
}

fn parse_zone(zone: &str) -> Result<Name, FetchError> {
    let valid = !zone.is_empty()
        && zone.len() <= MAX_ZONE_LEN
        && zone.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
                && !label.starts_with('-')
                && !label.ends_with('-')
        });
    if !valid {
        return Err(FetchError::InvalidBridge("zone"));
    }
    let mut name = Name::from_ascii(zone).map_err(|_| FetchError::InvalidBridge("zone"))?.to_lowercase();
    name.set_fqdn(true);
    Ok(name)
}

/// The bridge side of the channel. It answers only DNS-Morph requests under its zone.
pub struct Responder {
    zone: Name,
    key: Ed25519KeyPair,
    payload: Vec<u8>,
}

impl Responder {
    pub fn new(zone: &str, seed: &[u8; 32], payload: Vec<u8>) -> Result<Self, FetchError> {
        if payload.len() > MAX_PAYLOAD_LEN {
            return Err(FetchError::PayloadTooLarge);
        }
        if payload.is_empty() {
            return Err(FetchError::InvalidBridge("empty payload"));
        }
        let key = Ed25519KeyPair::from_seed_unchecked(seed).map_err(|_| FetchError::InvalidBridge("signing key"))?;
        Ok(Self { zone: parse_zone(zone)?, key, payload })
    }

    pub fn public_key(&self) -> [u8; 32] {
        let mut key = [0u8; 32];
        key.copy_from_slice(self.key.public_key().as_ref());
        key
    }

    /// Returns `None` when the datagram is not a DNS-Morph request; the bridge forwards it unchanged.
    pub fn respond(&self, datagram: &[u8]) -> Option<Vec<u8>> {
        let request = Message::from_vec(datagram).ok()?;
        let [question] = request.queries.as_slice() else { return None };
        let name = question.name.to_lowercase();
        if request.metadata.message_type != MessageType::Query
            || question.query_type != RecordType::TXT
            || question.query_class != DNSClass::IN
            || usize::from(name.num_labels()) != usize::from(self.zone.num_labels()) + 1
            || !self.zone.zone_of(&name)
        {
            return None;
        }
        let decoded = BASE32_DNSSEC.decode(name.iter().next()?).ok()?;
        if decoded.len() != REQUEST_LEN || decoded[0] != VERSION {
            return None;
        }
        let (nonce, index) = decoded[1..].split_at(NONCE_LEN);
        let index = usize::from(u16::from_be_bytes([index[0], index[1]]));
        let signature = self.key.sign(&signed_message(nonce, &self.payload));
        let envelope = [self.payload.as_slice(), signature.as_ref()].concat();

        let mut response = Message::response(request.metadata.id, OpCode::Query);
        response.metadata.authoritative = true;
        response.add_query(question.clone());
        let start = index * CHUNK_LEN;
        if start < envelope.len() {
            let total = u32::try_from(envelope.len()).ok()?.to_be_bytes();
            let data = &envelope[start..envelope.len().min(start + CHUNK_LEN)];
            let chunk = [&total[..], &(index as u16).to_be_bytes(), data].concat();
            response.add_answer(Record::from_rdata(
                question.name.clone(),
                0,
                RData::TXT(TXT::from_bytes(vec![&chunk])),
            ));
        } else {
            response.metadata.response_code = ResponseCode::Refused;
        }
        response.to_vec().ok()
    }
}
