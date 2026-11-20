//! DNS-Morph bootstrap channel, protocol version 2: a subscription payload delivered as TXT answers on
//! UDP. Each query carries a MAC from the subscriber's token; the bridge seals the payload to the
//! client's fresh X25519 key and signs it with its Ed25519 key. The wire contract is in
//! `docs/server-integration.md` ("DNS-Morph bootstrap channel"). [`fetch`] is the client; [`Responder`]
//! is the bridge side used by a bridge build and by tests.
#![forbid(unsafe_code)]

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::os::fd::{AsRawFd, RawFd};
use std::time::{Duration, Instant};

use data_encoding::BASE32_DNSSEC;
use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::TXT;
use hickory_proto::rr::{DNSClass, Name, RData, Record, RecordType};
use ring::aead::{Aad, CHACHA20_POLY1305, LessSafeKey, NONCE_LEN, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{ED25519, Ed25519KeyPair, KeyPair, UnparsedPublicKey};
use ring::{hkdf, hmac};
use subtle::ConstantTimeEq;
use x25519_dalek::{EphemeralSecret, PublicKey, StaticSecret};

pub const VERSION: u8 = 2;
const KEY_LEN: usize = 32;
const MAC_LEN: usize = 16;
/// Version, client X25519 public key and chunk index; the MAC covers exactly these bytes.
const REQUEST_BODY_LEN: usize = 1 + KEY_LEN + 2;
const REQUEST_LEN: usize = REQUEST_BODY_LEN + MAC_LEN;
const LABEL_LEN: usize = 63;
/// Envelope bytes per TXT answer; with [`MAX_ZONE_LEN`] every response stays below [`MAX_DATAGRAM_LEN`].
pub const CHUNK_LEN: usize = 180;
const CHUNK_HEADER_LEN: usize = 4 + 2;
pub const SIGNATURE_LEN: usize = 64;
const TAG_LEN: usize = 16;
/// Bridge X25519 key, AEAD nonce, AEAD tag and signature around the sealed payload.
pub const ENVELOPE_OVERHEAD: usize = KEY_LEN + NONCE_LEN + TAG_LEN + SIGNATURE_LEN;
pub const MAX_PAYLOAD_LEN: usize = 64 * 1024;
const MAX_ENVELOPE_LEN: usize = MAX_PAYLOAD_LEN + ENVELOPE_OVERHEAD;
/// Classic DNS UDP limit; a larger datagram is rejected, never parsed.
pub const MAX_DATAGRAM_LEN: usize = 512;
/// Keeps an uncompressed reply under [`MAX_DATAGRAM_LEN`] next to the 82-character request labels.
pub const MAX_ZONE_LEN: usize = 60;
const SIGNING_CONTEXT: &[u8] = b"ripdpi-dns-morph-v2\0";
const KEY_INFO: &[u8] = b"ripdpi-dns-morph-v2 payload key";
const NONCE_INFO: &[u8] = b"ripdpi-dns-morph-v2 nonce key";
const MAC_INFO: &[u8] = b"ripdpi-dns-morph-v2 query mac";
const SALT_INFO: &[u8] = b"ripdpi-dns-morph-v2 payload salt";
const BRIDGE_KEY_INFO: &[u8] = b"ripdpi-dns-morph-v2 bridge x25519";
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
    #[error("DNS-Morph payload does not decrypt")]
    Undecryptable,
}

/// A bridge declared by an imported bundle: an IP literal endpoint, its zone, its pinned Ed25519 key and
/// the subscriber token that authorizes this client.
#[derive(Clone)]
pub struct Bridge {
    address: SocketAddr,
    zone: Name,
    public_key: [u8; 32],
    keys: TokenKeys,
}

impl std::fmt::Debug for Bridge {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("Bridge([REDACTED])")
    }
}

impl Bridge {
    pub fn new(address: SocketAddr, zone: &str, public_key: [u8; 32], token: [u8; 32]) -> Result<Self, FetchError> {
        if address.port() == 0 || address.ip().is_unspecified() || address.ip().is_multicast() {
            return Err(FetchError::InvalidBridge("address"));
        }
        Ok(Self { address, zone: parse_zone(zone)?, public_key, keys: TokenKeys::new(&token)? })
    }
}

/// Fetches, verifies and opens the bridge payload. `protect` runs on the socket before its first datagram.
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
    let client_secret = EphemeralSecret::random();
    let client_key = PublicKey::from(&client_secret);
    let mut envelope = Vec::new();
    let mut total: Option<usize> = None;
    let mut index: u16 = 0;
    while total.is_none_or(|total| envelope.len() < total) {
        let name = request_name(&bridge.zone, &bridge.keys, client_key.as_bytes(), index)?;
        let chunk = exchange(&socket, &rng, name, deadline)?;
        let (chunk_total, chunk_index, data) = split_chunk(&chunk)?;
        if chunk_total > MAX_ENVELOPE_LEN {
            return Err(FetchError::PayloadTooLarge);
        }
        if chunk_index != index || chunk_total <= ENVELOPE_OVERHEAD {
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
    let (body, signature) = envelope.split_at(envelope.len() - SIGNATURE_LEN);
    UnparsedPublicKey::new(&ED25519, bridge.public_key)
        .verify(&signed_message(client_key.as_bytes(), body), signature)
        .map_err(|_| FetchError::BadSignature)?;
    let (bridge_key, sealed) = body.split_at(KEY_LEN);
    let (nonce, sealed) = sealed.split_at(NONCE_LEN);
    let bridge_key: [u8; KEY_LEN] = bridge_key.try_into().map_err(|_| FetchError::Malformed("bridge key"))?;
    let shared = client_secret.diffie_hellman(&PublicKey::from(bridge_key));
    if !shared.was_contributory() {
        return Err(FetchError::Malformed("bridge key"));
    }
    let key = session_key(&bridge.keys, shared.as_bytes(), client_key.as_bytes(), &bridge_key, KEY_INFO)?;
    let key = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, &key).map_err(|_| FetchError::Malformed("key"))?);
    let nonce = Nonce::try_assume_unique_for_key(nonce).map_err(|_| FetchError::Malformed("nonce"))?;
    let mut sealed = sealed.to_vec();
    let payload = key
        .open_in_place(nonce, Aad::from(client_key.as_bytes()), &mut sealed)
        .map_err(|_| FetchError::Undecryptable)?;
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

fn request_name(zone: &Name, keys: &TokenKeys, client_key: &[u8; KEY_LEN], index: u16) -> Result<Name, FetchError> {
    let mut request = [0u8; REQUEST_LEN];
    request[0] = VERSION;
    request[1..=KEY_LEN].copy_from_slice(client_key);
    request[KEY_LEN + 1..REQUEST_BODY_LEN].copy_from_slice(&index.to_be_bytes());
    let mac = keys.query_mac(zone, &request[..REQUEST_BODY_LEN]);
    request[REQUEST_BODY_LEN..].copy_from_slice(&mac);
    let encoded = BASE32_DNSSEC.encode(&request);
    let (first, second) = encoded.split_at(LABEL_LEN);
    zone.prepend_label(second).and_then(|name| name.prepend_label(first)).map_err(|_| FetchError::InvalidBridge("zone"))
}

/// Separate subkeys from one subscriber token, so the query MAC and the payload KDF never share a key.
#[derive(Clone)]
struct TokenKeys {
    mac: hmac::Key,
    payload_salt: [u8; 32],
}

impl TokenKeys {
    fn new(token: &[u8; 32]) -> Result<Self, FetchError> {
        if token.iter().all(|byte| *byte == 0) {
            return Err(FetchError::InvalidBridge("token"));
        }
        let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, &[]).extract(token);
        let mut mac = [0u8; 32];
        let mut payload_salt = [0u8; 32];
        expand(&prk, &[MAC_INFO], &mut mac)?;
        expand(&prk, &[SALT_INFO], &mut payload_salt)?;
        Ok(Self { mac: hmac::Key::new(hmac::HMAC_SHA256, &mac), payload_salt })
    }

    /// Binds the request to this bridge's zone, so a captured query does not replay against another bridge.
    fn query_mac(&self, zone: &Name, body: &[u8]) -> [u8; MAC_LEN] {
        let tag = hmac::sign(&self.mac, &[body, zone.to_ascii().as_bytes()].concat());
        let mut mac = [0u8; MAC_LEN];
        mac.copy_from_slice(&tag.as_ref()[..MAC_LEN]);
        mac
    }
}

/// A per-fetch secret bound to both public keys; `info` separates the AEAD key from the nonce key.
fn session_key(
    keys: &TokenKeys,
    shared: &[u8; 32],
    client_key: &[u8; KEY_LEN],
    bridge_key: &[u8; KEY_LEN],
    info: &[u8],
) -> Result<[u8; 32], FetchError> {
    let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, &keys.payload_salt).extract(shared);
    let mut key = [0u8; 32];
    expand(&prk, &[info, client_key, bridge_key], &mut key)?;
    Ok(key)
}

fn expand(prk: &hkdf::Prk, info: &[&[u8]], out: &mut [u8; 32]) -> Result<(), FetchError> {
    prk.expand(info, hkdf::HKDF_SHA256).and_then(|okm| okm.fill(out)).map_err(|_| FetchError::Malformed("key schedule"))
}

fn signed_message(client_key: &[u8], body: &[u8]) -> Vec<u8> {
    [SIGNING_CONTEXT, client_key, body].concat()
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

/// One authorized client of a bridge and the payload sealed for it.
pub struct Subscriber {
    pub token: [u8; 32],
    pub payload: Vec<u8>,
}

/// The bridge side of the channel. It answers only authenticated DNS-Morph requests under its zone.
pub struct Responder {
    zone: Name,
    signing_key: Ed25519KeyPair,
    sealing_key: StaticSecret,
    subscribers: Vec<(TokenKeys, Vec<u8>)>,
}

impl Responder {
    pub fn new(zone: &str, seed: &[u8; 32], subscribers: Vec<Subscriber>) -> Result<Self, FetchError> {
        for subscriber in &subscribers {
            if subscriber.payload.len() > MAX_PAYLOAD_LEN {
                return Err(FetchError::PayloadTooLarge);
            }
            if subscriber.payload.is_empty() {
                return Err(FetchError::InvalidBridge("empty payload"));
            }
        }
        let mut tokens: Vec<[u8; 32]> = subscribers.iter().map(|subscriber| subscriber.token).collect();
        tokens.sort_unstable();
        tokens.dedup();
        if tokens.len() != subscribers.len() {
            return Err(FetchError::InvalidBridge("duplicate token"));
        }
        let subscribers = subscribers
            .into_iter()
            .map(|subscriber| Ok((TokenKeys::new(&subscriber.token)?, subscriber.payload)))
            .collect::<Result<Vec<_>, FetchError>>()?;
        let signing_key =
            Ed25519KeyPair::from_seed_unchecked(seed).map_err(|_| FetchError::InvalidBridge("signing key"))?;
        // One seed serves both keys; the X25519 key is a separate HKDF output, so neither key reveals the other.
        let mut sealing_seed = [0u8; 32];
        hkdf::Salt::new(hkdf::HKDF_SHA256, &[])
            .extract(seed)
            .expand(&[BRIDGE_KEY_INFO], hkdf::HKDF_SHA256)
            .and_then(|okm| okm.fill(&mut sealing_seed))
            .map_err(|_| FetchError::InvalidBridge("signing key"))?;
        Ok(Self { zone: parse_zone(zone)?, signing_key, sealing_key: StaticSecret::from(sealing_seed), subscribers })
    }

    pub fn public_key(&self) -> [u8; 32] {
        let mut key = [0u8; 32];
        key.copy_from_slice(self.signing_key.public_key().as_ref());
        key
    }

    /// Returns `None` when the datagram is not an authenticated DNS-Morph request; the bridge forwards it
    /// unchanged, so an unauthorized prober sees an ordinary resolver.
    pub fn respond(&self, datagram: &[u8]) -> Option<Vec<u8>> {
        let request = Message::from_vec(datagram).ok()?;
        let [question] = request.queries.as_slice() else { return None };
        let name = question.name.to_lowercase();
        if request.metadata.message_type != MessageType::Query
            || request.metadata.op_code != OpCode::Query
            || question.query_type != RecordType::TXT
            || question.query_class != DNSClass::IN
            || usize::from(name.num_labels()) != usize::from(self.zone.num_labels()) + 2
            || !self.zone.zone_of(&name)
        {
            return None;
        }
        let mut labels = name.iter();
        let encoded = [labels.next()?, labels.next()?].concat();
        let decoded = BASE32_DNSSEC.decode(&encoded).ok()?;
        if decoded.len() != REQUEST_LEN || decoded[0] != VERSION {
            return None;
        }
        let (body, mac) = decoded.split_at(REQUEST_BODY_LEN);
        let (keys, payload) =
            self.subscribers.iter().find(|(keys, _)| bool::from(keys.query_mac(&self.zone, body).ct_eq(mac)))?;
        let client_key: [u8; KEY_LEN] = body[1..=KEY_LEN].try_into().ok()?;
        let index = u16::from_be_bytes([body[KEY_LEN + 1], body[KEY_LEN + 2]]);
        let envelope = self.seal(keys, payload, &client_key)?;

        let mut response = Message::response(request.metadata.id, OpCode::Query);
        response.metadata.authoritative = true;
        response.add_query(question.clone());
        let start = usize::from(index) * CHUNK_LEN;
        if start < envelope.len() {
            let total = u32::try_from(envelope.len()).ok()?.to_be_bytes();
            let data = &envelope[start..envelope.len().min(start + CHUNK_LEN)];
            let chunk = [&total[..], &index.to_be_bytes(), data].concat();
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

    /// Deterministic per client key and payload, so every chunk query of one fetch sees the same envelope.
    fn seal(&self, keys: &TokenKeys, payload: &[u8], client_key: &[u8; KEY_LEN]) -> Option<Vec<u8>> {
        let shared = self.sealing_key.diffie_hellman(&PublicKey::from(*client_key));
        if !shared.was_contributory() {
            return None;
        }
        let bridge_key = *PublicKey::from(&self.sealing_key).as_bytes();
        let aead = session_key(keys, shared.as_bytes(), client_key, &bridge_key, KEY_INFO).ok()?;
        let nonce_key = session_key(keys, shared.as_bytes(), client_key, &bridge_key, NONCE_INFO).ok()?;
        // A keyed hash of the payload: deterministic within a fetch, never reused for a different payload,
        // and not a public fingerprint of the plaintext.
        let mut nonce = [0u8; NONCE_LEN];
        nonce.copy_from_slice(
            &hmac::sign(&hmac::Key::new(hmac::HMAC_SHA256, &nonce_key), payload).as_ref()[..NONCE_LEN],
        );
        let key = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, &aead).ok()?);
        let mut sealed = payload.to_vec();
        key.seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(client_key), &mut sealed).ok()?;
        let body = [&bridge_key[..], &nonce[..], &sealed].concat();
        let signature = self.signing_key.sign(&signed_message(client_key, &body));
        Some([body.as_slice(), signature.as_ref()].concat())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: [u8; 32] = [11; 32];

    fn responder(zone: &str) -> Responder {
        Responder::new(zone, &[7; 32], vec![Subscriber { token: TOKEN, payload: vec![1; 50] }]).expect("responder")
    }

    fn query(zone: &str, client_key: [u8; KEY_LEN]) -> Vec<u8> {
        let zone = parse_zone(zone).expect("zone");
        let name = request_name(&zone, &TokenKeys::new(&TOKEN).expect("keys"), &client_key, 0).expect("name");
        let mut query = Message::new(1, MessageType::Query, OpCode::Query);
        query.add_query(Query::new(name, RecordType::TXT));
        query.to_vec().expect("query")
    }

    #[test]
    fn answers_an_authorized_query_for_its_own_zone_only() {
        let client = *PublicKey::from(&StaticSecret::from([3; 32])).as_bytes();
        assert!(responder("m.example").respond(&query("m.example", client)).is_some());
        // The MAC covers the zone, so the same query is silent at a bridge with another zone.
        assert!(responder("other.example").respond(&query("m.example", client)).is_none());
    }

    #[test]
    fn stays_silent_for_a_low_order_client_key() {
        assert!(responder("m.example").respond(&query("m.example", [0; KEY_LEN])).is_none());
    }

    #[test]
    fn rejects_duplicate_and_all_zero_tokens() {
        let duplicate =
            vec![Subscriber { token: TOKEN, payload: vec![1] }, Subscriber { token: TOKEN, payload: vec![2] }];
        assert!(Responder::new("m.example", &[7; 32], duplicate).is_err());
        assert!(Responder::new("m.example", &[7; 32], vec![Subscriber { token: [0; 32], payload: vec![1] }]).is_err());
        assert!(Bridge::new("192.0.2.1:53".parse().expect("address"), "m.example", [0; 32], [0; 32]).is_err());
    }
}
