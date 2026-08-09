use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, TcpListener};
use std::str::FromStr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{SystemTime, UNIX_EPOCH};

use crypto_box::aead::Aead;
use crypto_box::{ChaChaBox, PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use hickory_proto::op::{Message, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::TXT;
use hickory_proto::rr::{Name, RData, Record, RecordType};
use ring::signature::{Ed25519KeyPair, KeyPair};

use crate::event::{EventLog, event};
use crate::fault::FaultController;
use crate::types::{FixtureFaultTarget, IO_POLL_DELAY, IO_TIMEOUT};
use crate::util;

use super::{
    handle_streaming_dns_request, parse_dns_question_name, read_length_prefixed_frame, write_length_prefixed_frame,
};

const DNSCRYPT_CERT_MAGIC: [u8; 4] = *b"DNSC";
const DNSCRYPT_ES_VERSION: u16 = 2;
const DNSCRYPT_RESPONSE_MAGIC: [u8; 8] = [0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38];
const DNSCRYPT_NONCE_SIZE: usize = 24;
const DNSCRYPT_QUERY_NONCE_HALF: usize = DNSCRYPT_NONCE_SIZE / 2;
const DNSCRYPT_CERT_SIZE: usize = 124;
const DNSCRYPT_PADDING_BLOCK_SIZE: usize = 64;
const DNSCRYPT_PROVIDER_SEED: [u8; 32] = [7u8; 32];
const DNSCRYPT_RESOLVER_SEED: [u8; 32] = [9u8; 32];

#[derive(Clone)]
struct DnsCryptServerState {
    provider_name: String,
    resolver_secret: CryptoSecretKey,
    certificate_bytes: Vec<u8>,
}

pub(crate) fn start_dns_dnscrypt_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
    provider_name: String,
    provider_public_key_hex: String,
) -> io::Result<(JoinHandle<()>, u16)> {
    let answer_ip =
        Ipv4Addr::from_str(&answer_ip).map_err(|err| io::Error::new(ErrorKind::InvalidInput, err.to_string()))?;
    let server_state = build_dnscrypt_server_state(provider_name, provider_public_key_hex)?;
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    let local_port = listener.local_addr()?.port();
    Ok((
        thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, peer)) => {
                        let events = events.clone();
                        let faults = faults.clone();
                        let server_state = server_state.clone();
                        thread::spawn(move || {
                            let _ = stream.set_nonblocking(false);
                            let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
                            let local = stream.local_addr().ok();
                            loop {
                                let packet = match read_length_prefixed_frame(&mut stream) {
                                    Ok(packet) => packet,
                                    Err(err)
                                        if matches!(
                                            err.kind(),
                                            ErrorKind::UnexpectedEof
                                                | ErrorKind::ConnectionReset
                                                | ErrorKind::ConnectionAborted
                                                | ErrorKind::BrokenPipe
                                        ) =>
                                    {
                                        return;
                                    }
                                    Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                                        continue;
                                    }
                                    Err(_) => return,
                                };

                                if is_dnscrypt_certificate_query(&packet) {
                                    let query_name =
                                        parse_dns_question_name(&packet).unwrap_or_else(|| "unknown".to_string());
                                    events.record(event(
                                        "dns_dnscrypt",
                                        "dnscrypt",
                                        peer,
                                        local,
                                        &query_name,
                                        packet.len(),
                                        None,
                                    ));
                                    let response = build_dnscrypt_cert_response(
                                        &packet,
                                        &server_state.provider_name,
                                        &server_state.certificate_bytes,
                                    );
                                    if write_length_prefixed_frame(&mut stream, &response).is_err() {
                                        return;
                                    }
                                    continue;
                                }

                                let query = match decrypt_dnscrypt_query(&packet, &server_state.resolver_secret) {
                                    Ok(query) => query,
                                    Err(detail) => {
                                        events.record(event(
                                            "dns_dnscrypt",
                                            "dnscrypt",
                                            peer,
                                            local,
                                            &format!("decrypt_error:{detail}"),
                                            packet.len(),
                                            None,
                                        ));
                                        return;
                                    }
                                };
                                let response_result = handle_streaming_dns_request(
                                    "dns_dnscrypt",
                                    "dnscrypt",
                                    FixtureFaultTarget::DnsDnsCrypt,
                                    peer,
                                    local,
                                    &query.query,
                                    None,
                                    &events,
                                    &faults,
                                    answer_ip,
                                    |response| {
                                        let wrapped = encrypt_dnscrypt_response(
                                            response,
                                            &server_state.resolver_secret,
                                            &query.client_public,
                                            &query.nonce,
                                        )
                                        .map_err(util::other_io)?;
                                        write_length_prefixed_frame(&mut stream, &wrapped)
                                    },
                                );
                                if response_result.is_err() {
                                    return;
                                }
                            }
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                    Err(_) => break,
                }
            }
        }),
        local_port,
    ))
}

fn build_dnscrypt_server_state(
    provider_name: String,
    provider_public_key_hex: String,
) -> io::Result<DnsCryptServerState> {
    let signing_key = Ed25519KeyPair::from_seed_unchecked(&DNSCRYPT_PROVIDER_SEED)
        .map_err(|err| io::Error::other(err.to_string()))?;
    let provider_public_bytes: [u8; 32] =
        signing_key.public_key().as_ref().try_into().map_err(|_| io::Error::other("dnscrypt public key size"))?;
    let derived_public_key_hex = hex::encode(provider_public_bytes);
    if !provider_public_key_hex.eq_ignore_ascii_case(&derived_public_key_hex) {
        return Err(io::Error::new(
            ErrorKind::InvalidInput,
            format!(
                "RIPDPI fixture DNSCrypt public key does not match the built-in test certificate (expected {derived_public_key_hex})"
            ),
        ));
    }

    let resolver_secret = CryptoSecretKey::from(DNSCRYPT_RESOLVER_SEED);
    let resolver_public = resolver_secret.public_key();
    let valid_from = unix_time_secs().saturating_sub(60);
    let valid_until = valid_from.saturating_add(86_400);
    let mut client_magic = [0u8; 8];
    client_magic.copy_from_slice(&resolver_public.as_bytes()[..8]);

    let mut inner = [0u8; 52];
    inner[..32].copy_from_slice(resolver_public.as_bytes());
    inner[32..40].copy_from_slice(&client_magic);
    inner[40..44].copy_from_slice(&1u32.to_be_bytes());
    inner[44..48].copy_from_slice(&valid_from.to_be_bytes());
    inner[48..52].copy_from_slice(&valid_until.to_be_bytes());
    let signature = signing_key.sign(&inner);

    let mut certificate_bytes = Vec::with_capacity(DNSCRYPT_CERT_SIZE);
    certificate_bytes.extend_from_slice(&DNSCRYPT_CERT_MAGIC);
    certificate_bytes.extend_from_slice(&DNSCRYPT_ES_VERSION.to_be_bytes());
    certificate_bytes.extend_from_slice(&0u16.to_be_bytes());
    certificate_bytes.extend_from_slice(signature.as_ref());
    certificate_bytes.extend_from_slice(&inner);

    Ok(DnsCryptServerState { provider_name, resolver_secret, certificate_bytes })
}

fn is_dnscrypt_certificate_query(packet: &[u8]) -> bool {
    Message::from_vec(packet)
        .ok()
        .and_then(|message| message.queries.first().map(|query| query.query_type == RecordType::TXT))
        .unwrap_or(false)
}

fn build_dnscrypt_cert_response(query: &[u8], provider_name: &str, cert_bytes: &[u8]) -> Vec<u8> {
    let request = Message::from_vec(query).expect("fixture dnscrypt cert query parses");
    let mut response = Message::response(request.metadata.id, OpCode::Query);
    response.metadata.recursion_desired = request.metadata.recursion_desired;
    response.metadata.recursion_available = true;
    response.metadata.response_code = ResponseCode::NoError;
    response.add_query(Query::new(Name::from_ascii(provider_name).expect("fixture provider name"), RecordType::TXT));
    response.add_answer(Record::from_rdata(
        Name::from_ascii(provider_name).expect("fixture provider name"),
        600,
        RData::TXT(TXT::from_bytes(vec![cert_bytes])),
    ));
    response.to_vec().expect("fixture dnscrypt cert response encodes")
}

struct DecryptedDnsCryptQuery {
    query: Vec<u8>,
    client_public: [u8; 32],
    nonce: [u8; DNSCRYPT_NONCE_SIZE],
}

fn decrypt_dnscrypt_query(packet: &[u8], resolver_secret: &CryptoSecretKey) -> Result<DecryptedDnsCryptQuery, String> {
    if packet.len() <= 52 {
        return Err("dnscrypt_query_too_short".to_string());
    }
    let mut client_magic = [0u8; 8];
    client_magic.copy_from_slice(&packet[..8]);
    let resolver_public = resolver_secret.public_key();
    let expected_magic = &resolver_public.as_bytes()[..8];
    if client_magic != expected_magic {
        return Err("dnscrypt_client_magic_mismatch".to_string());
    }

    let mut client_public = [0u8; 32];
    client_public.copy_from_slice(&packet[8..40]);
    let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
    nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);

    let crypto_box = ChaChaBox::new(&CryptoPublicKey::from(client_public), resolver_secret);
    let plaintext =
        crypto_box.decrypt((&nonce).into(), &packet[52..]).map_err(|err| format!("dnscrypt_request_decrypt:{err}"))?;
    let query = dnscrypt_unpad(&plaintext)?;
    Ok(DecryptedDnsCryptQuery { query, client_public, nonce })
}

fn encrypt_dnscrypt_response(
    response_packet: &[u8],
    resolver_secret: &CryptoSecretKey,
    client_public: &[u8; 32],
    nonce: &[u8; DNSCRYPT_NONCE_SIZE],
) -> Result<Vec<u8>, String> {
    let crypto_box = ChaChaBox::new(&CryptoPublicKey::from(*client_public), resolver_secret);
    let mut response_nonce = *nonce;
    response_nonce[DNSCRYPT_QUERY_NONCE_HALF..].fill(0x11);
    let ciphertext = crypto_box
        .encrypt((&response_nonce).into(), dnscrypt_pad(response_packet).as_slice())
        .map_err(|err| err.to_string())?;

    let mut wrapped = Vec::with_capacity(8 + DNSCRYPT_NONCE_SIZE + ciphertext.len());
    wrapped.extend_from_slice(&DNSCRYPT_RESPONSE_MAGIC);
    wrapped.extend_from_slice(&response_nonce);
    wrapped.extend_from_slice(&ciphertext);
    Ok(wrapped)
}

fn dnscrypt_pad(payload: &[u8]) -> Vec<u8> {
    let mut padded =
        Vec::with_capacity((payload.len() + 1).div_ceil(DNSCRYPT_PADDING_BLOCK_SIZE) * DNSCRYPT_PADDING_BLOCK_SIZE);
    padded.extend_from_slice(payload);
    padded.push(0x80);
    while padded.len() % DNSCRYPT_PADDING_BLOCK_SIZE != 0 {
        padded.push(0x00);
    }
    padded
}

fn dnscrypt_unpad(payload: &[u8]) -> Result<Vec<u8>, String> {
    let marker =
        payload.iter().rposition(|byte| *byte != 0x00).ok_or_else(|| "dnscrypt_padding_marker_missing".to_string())?;
    if payload[marker] != 0x80 {
        return Err("dnscrypt_padding_marker_invalid".to_string());
    }
    Ok(payload[..marker].to_vec())
}

fn unix_time_secs() -> u32 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs().try_into().unwrap_or(u32::MAX)
}
