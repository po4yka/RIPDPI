use super::awg_cookies::CookieSource;
use super::handshake::{b2s_hash, b2s_keyed_mac_16, b2s_keyed_mac_16_2, b2s_mac_24};
use crate::noise::handshake::{LABEL_COOKIE, LABEL_MAC1};
use crate::noise::{HandshakeInit, HandshakeResponse, Packet, Tunn, TunnResult, WireGuardError};

#[cfg(feature = "mock-instant")]
use mock_instant::Instant;
use portable_atomic::{AtomicU64, Ordering};
use std::net::{IpAddr, SocketAddr};

#[cfg(not(feature = "mock-instant"))]
use crate::sleepyinstant::Instant;

use aead::generic_array::GenericArray;
use aead::{AeadInPlace, KeyInit};
use chacha20poly1305::{Key, XChaCha20Poly1305};
use parking_lot::Mutex;
use rand_core::{OsRng, RngCore};
use subtle::ConstantTimeEq;

const COOKIE_REFRESH: u64 = 120;
const COOKIE_SIZE: usize = 16;
const COOKIE_NONCE_SIZE: usize = 24;

/// How often should reset count in seconds
const RESET_PERIOD: u64 = 1;

type Cookie = [u8; COOKIE_SIZE];

/// There are two places where WireGuard requires "randomness" for cookies
/// * The 24 byte nonce in the cookie massage - here the only goal is to avoid nonce reuse
/// * A secret value that changes every two minutes
/// Because the main goal of the cookie is simply for a party to prove ownership of a source endpoint
/// we can relax the randomness definition a bit, in order to avoid locking, because using less
/// resources is the main goal of any DoS prevention mechanism.
/// In order to avoid locking and calls to rand we derive pseudo random values using the AEAD and
/// some counters.
pub struct RateLimiter {
    /// The key we use to derive the nonce
    nonce_key: [u8; 32],
    /// The key we use to derive the cookie
    secret_key: [u8; 16],
    start_time: Instant,
    /// A single 64 bit counter (should suffice for many years)
    nonce_ctr: AtomicU64,
    mac1_key: [u8; 32],
    cookie_key: Key,
    limit: u64,
    /// The counter since last reset
    count: AtomicU64,
    /// The time last reset was performed on this rate limiter
    last_reset: Mutex<Instant>,
}

impl RateLimiter {
    pub fn new(public_key: &crate::x25519::PublicKey, limit: u64) -> Self {
        let mut secret_key = [0u8; 16];
        OsRng.fill_bytes(&mut secret_key);
        RateLimiter {
            nonce_key: Self::rand_bytes(),
            secret_key,
            start_time: Instant::now(),
            nonce_ctr: AtomicU64::new(0),
            mac1_key: b2s_hash(LABEL_MAC1, public_key.as_bytes()),
            cookie_key: b2s_hash(LABEL_COOKIE, public_key.as_bytes()).into(),
            limit,
            count: AtomicU64::new(0),
            last_reset: Mutex::new(Instant::now()),
        }
    }

    fn rand_bytes() -> [u8; 32] {
        let mut key = [0u8; 32];
        OsRng.fill_bytes(&mut key);
        key
    }

    /// Reset packet count (ideally should be called with a period of 1 second)
    pub fn reset_count(&self) {
        // The rate limiter is not very accurate, but at the scale we care about it doesn't matter much
        let current_time = Instant::now();
        let mut last_reset_time = self.last_reset.lock();
        if current_time.duration_since(*last_reset_time).as_secs() >= RESET_PERIOD {
            self.count.store(0, Ordering::SeqCst);
            *last_reset_time = current_time;
        }
    }

    /// Bind the cookie to the observed UDP endpoint or established carrier identity.
    fn current_cookie(&self, source: CookieSource) -> Cookie {
        let cur_counter = Instant::now().duration_since(self.start_time).as_secs() / COOKIE_REFRESH;
        b2s_keyed_mac_16_2(
            &self.secret_key,
            &cur_counter.to_le_bytes(),
            &source.bytes(),
        )
    }

    fn nonce(&self) -> [u8; COOKIE_NONCE_SIZE] {
        let ctr = self.nonce_ctr.fetch_add(1, Ordering::Relaxed);

        b2s_mac_24(&self.nonce_key, &ctr.to_le_bytes())
    }

    fn is_under_load(&self) -> bool {
        self.count.fetch_add(1, Ordering::SeqCst) >= self.limit
    }

    pub(crate) fn format_cookie_reply<'a>(
        &self,
        idx: u32,
        cookie: Cookie,
        mac1: &[u8],
        dst: &'a mut [u8],
    ) -> Result<&'a mut [u8], WireGuardError> {
        if dst.len() < super::COOKIE_REPLY_SZ {
            return Err(WireGuardError::DestinationBufferTooSmall);
        }

        let (message_type, rest) = dst.split_at_mut(4);
        let (receiver_index, rest) = rest.split_at_mut(4);
        let (nonce, rest) = rest.split_at_mut(24);
        let (encrypted_cookie, _) = rest.split_at_mut(16 + 16);

        // msg.message_type = 3
        // msg.reserved_zero = { 0, 0, 0 }
        message_type.copy_from_slice(&super::COOKIE_REPLY.to_le_bytes());
        // msg.receiver_index = little_endian(initiator.sender_index)
        receiver_index.copy_from_slice(&idx.to_le_bytes());
        nonce.copy_from_slice(&self.nonce()[..]);

        let cipher = XChaCha20Poly1305::new(&self.cookie_key);

        let iv = GenericArray::from_slice(nonce);

        encrypted_cookie[..16].copy_from_slice(&cookie);
        let tag = cipher
            .encrypt_in_place_detached(iv, mac1, &mut encrypted_cookie[..16])
            .map_err(|_| WireGuardError::DestinationBufferTooSmall)?;

        encrypted_cookie[16..].copy_from_slice(&tag);

        Ok(&mut dst[..super::COOKIE_REPLY_SZ])
    }

    /// AWG admission preserves MAC1 and Noise authentication with either cookie flag.
    pub(super) fn verify_awg_packet<'a, 'b>(
        &self,
        source: Option<super::awg_cookies::CookieSource>,
        src: &'a [u8],
        dst: &'b mut [u8],
        admission: &mut super::awg_cookies::Admission,
        now: std::time::Duration,
    ) -> Result<Packet<'a>, TunnResult<'b>> {
        let packet = Tunn::parse_incoming_packet(src)?;
        if let Packet::HandshakeInit(HandshakeInit { sender_idx, .. })
        | Packet::HandshakeResponse(HandshakeResponse { sender_idx, .. }) = packet
        {
            let (msg, macs) = src.split_at(src.len() - 32);
            let (mac1, mac2) = macs.split_at(16);
            let expected = b2s_keyed_mac_16(&self.mac1_key, msg);
            if !bool::from(expected[..].ct_eq(mac1)) {
                return Err(TunnResult::Err(WireGuardError::InvalidMac));
            }
            admission.advance(now);
            self.reset_count();
            let under_load = self.is_under_load();
            if !admission.disabled && under_load {
                let source = source.ok_or(TunnResult::Err(WireGuardError::UnderLoad))?;
                let cookie = self.current_cookie(source);
                let expected = b2s_keyed_mac_16_2(&cookie, msg, mac1);
                if !bool::from(expected[..].ct_eq(mac2)) {
                    if !admission.challenge() {
                        return Err(TunnResult::Err(WireGuardError::UnderLoad));
                    }
                    return Err(TunnResult::WriteToNetwork(
                        self.format_cookie_reply(sender_idx, cookie, mac1, dst)
                            .map_err(TunnResult::Err)?,
                    ));
                }
            }
            if !admission.handshake() {
                return Err(TunnResult::Err(WireGuardError::UnderLoad));
            }
        }
        Ok(packet)
    }

    /// IP-only compatibility entry point; the UDP source port is represented as zero.
    /// Use [`Tunn::decapsulate_from`] when the actual endpoint or carrier identity is available.
    pub fn verify_packet<'a, 'b>(
        &self,
        src_addr: Option<IpAddr>,
        src: &'a [u8],
        dst: &'b mut [u8],
    ) -> Result<Packet<'a>, TunnResult<'b>> {
        self.verify_packet_from(
            src_addr.map(|ip| CookieSource::Udp(SocketAddr::new(ip, 0))),
            src,
            dst,
        )
    }

    /// Verify MAC fields and apply the cookie policy to the actual carrier source.
    pub(super) fn verify_packet_from<'a, 'b>(
        &self,
        source: Option<CookieSource>,
        src: &'a [u8],
        dst: &'b mut [u8],
    ) -> Result<Packet<'a>, TunnResult<'b>> {
        let packet = Tunn::parse_incoming_packet(src)?;

        // Verify and rate limit handshake messages only
        if let Packet::HandshakeInit(HandshakeInit { sender_idx, .. })
        | Packet::HandshakeResponse(HandshakeResponse { sender_idx, .. }) = packet
        {
            let (msg, macs) = src.split_at(src.len() - 32);
            let (mac1, mac2) = macs.split_at(16);

            let computed_mac1 = b2s_keyed_mac_16(&self.mac1_key, msg);
            if !bool::from(computed_mac1[..].ct_eq(mac1)) {
                return Err(TunnResult::Err(WireGuardError::InvalidMac));
            }

            if self.is_under_load() {
                let source = match source {
                    None => return Err(TunnResult::Err(WireGuardError::UnderLoad)),
                    Some(source) => source,
                };
                let cookie = self.current_cookie(source);
                let computed_mac2 = b2s_keyed_mac_16_2(&cookie, msg, mac1);

                if !bool::from(computed_mac2[..].ct_eq(mac2)) {
                    let cookie_packet = self
                        .format_cookie_reply(sender_idx, cookie, mac1, dst)
                        .map_err(TunnResult::Err)?;
                    return Err(TunnResult::WriteToNetwork(cookie_packet));
                }
            }
        }

        Ok(packet)
    }
}

#[cfg(all(test, feature = "mock-instant"))]
mod tests {
    use super::*;
    use crate::x25519::{PublicKey, StaticSecret};
    use std::time::Duration;

    #[test]
    fn cookie_secret_rotates_after_two_minutes() {
        let limiter = RateLimiter::new(&PublicKey::from(&StaticSecret::from([7; 32])), 10);
        let address = CookieSource::Udp("192.0.2.1:9000".parse().unwrap());
        let first = limiter.current_cookie(address);
        mock_instant::MockClock::advance(Duration::from_secs(119));
        assert_eq!(limiter.current_cookie(address), first);
        mock_instant::MockClock::advance(Duration::from_secs(1));
        assert_ne!(limiter.current_cookie(address), first);
    }
}
