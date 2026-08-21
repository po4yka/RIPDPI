//! Reality session_id sealing — spec-conformant C1+C2 crypto primitive.
//!
//! Implements the AES-256-GCM seal described in xray-core
//! `transport/internet/reality/reality.go:167-174`. The output is the
//! 32-byte payload that overwrites the TLS ClientHello session_id slot
//! (offset 39..71 in the raw handshake-message bytes).
//!
//! Two upstream-conformance fixes are bundled here (audit findings C1
//! and C2 in `docs/design/reality-boringssl-patch.md`):
//!
//! * **C1** — HKDF salt is `client_random[..20]` (first 20 bytes), not
//!   `client_random[20..]`. The earlier `build_reality_session_id`
//!   implementation in [`crate::reality`] sliced the wrong half.
//! * **C2** — Cipher is **AES-256-GCM** with the full 32-byte AuthKey,
//!   nonce = `client_random[20..32]`, AAD = the raw ClientHello with
//!   the session_id slot zeroed, plaintext = the 16-byte session_id
//!   plaintext. The earlier code used AES-128-ECB on the first 16
//!   bytes of session_id, which has neither authentication nor the
//!   correct keyspace.
//!
//! This module is the crypto primitive only. The live handshake path
//! extracts the TLS 1.3 client `key_share` X25519 private key through
//! [`crate::reality_hook`], the H1 BoringSSL hook described in
//! `docs/design/reality-boringssl-patch.md`.

use std::io;
use std::time::{SystemTime, UNIX_EPOCH};

use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::hkdf;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

/// Offset of the session_id field inside the raw ClientHello
/// handshake message (including the 4-byte handshake header).
///
/// Layout up to this point: `type(1) | length(3) | legacy_version(2)
/// | random(32) | session_id_length(1)` = 39 bytes.
pub(crate) const SESSION_ID_OFFSET: usize = 39;
/// Length of the session_id slot the Reality cipher overwrites.
pub(crate) const SESSION_ID_LEN: usize = 32;

const SESSION_ID_END: usize = SESSION_ID_OFFSET + SESSION_ID_LEN;

/// Build the 16-byte session_id plaintext. Layout matches xray-core's
/// REALITY client (version 1.x signature, reserved 0, big-endian unix
/// timestamp, short_id padded to 8 bytes).
pub(crate) fn build_session_id_plaintext(short_id: &[u8], now_secs: u32) -> [u8; 16] {
    let mut pt = [0u8; 16];
    pt[0] = 0x00;
    pt[1] = 0x00;
    pt[2] = 0x01;
    pt[3] = 0x00;
    pt[4..8].copy_from_slice(&now_secs.to_be_bytes());
    let sid_len = short_id.len().min(8);
    pt[8..8 + sid_len].copy_from_slice(&short_id[..sid_len]);
    pt
}

/// Derive the 32-byte Reality AuthKey.
///
/// Matches xray-core `reality.go:167`:
/// `hkdf.New(sha256.New, ECDH(priv, server_pub), Random[:20], "REALITY")`.
/// IKM = ECDH shared secret, salt = `client_random[..20]`, info =
/// `b"REALITY"`, OKM length = 32. The key is returned zeroized on drop.
pub(crate) fn derive_auth_key(
    priv_key: &[u8; 32],
    server_pub: &[u8; 32],
    client_random: &[u8; 32],
) -> Zeroizing<[u8; 32]> {
    let secret = StaticSecret::from(*priv_key);
    let pub_key = PublicKey::from(*server_pub);
    let shared = secret.diffie_hellman(&pub_key);

    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, &client_random[..20]);
    let prk = salt.extract(shared.as_bytes());
    let info: [&[u8]; 1] = [b"REALITY"];
    let okm = prk.expand(&info, AuthKeyLen).expect("HKDF expand auth_key (length is known-good)");
    let mut auth_key = Zeroizing::new([0u8; 32]);
    okm.fill(auth_key.as_mut()).expect("HKDF fill auth_key (length is known-good)");
    auth_key
}

/// Seal the Reality session_id into the 32-byte slot of the ClientHello.
///
/// Inputs:
/// - `priv_key`: TLS 1.3 client `key_share` X25519 private key. The
///   value flowing through this argument MUST be the private key
///   BoringSSL generated for the X25519 key_share entry on this
///   handshake — see the BoringSSL patch design (audit finding H1).
/// - `server_pub`: static Reality server X25519 public key.
/// - `client_random`: 32-byte ClientHello random.
/// - `short_id`: Reality short id (padded to 8 bytes inside the
///   plaintext; longer values are silently truncated).
/// - `raw_hello`: the raw ClientHello handshake message including the
///   4-byte handshake header. The function clones this into an AAD
///   buffer and zeroes bytes `[39..71]` before sealing; the caller's
///   buffer is not modified.
/// - `now_secs`: unix timestamp seconds for the plaintext timestamp.
///
/// Returns the 32-byte sealed session_id (16 B ciphertext + 16 B GCM
/// tag) to be written back to `raw_hello[39..71]` before the body is
/// fed to the TLS transcript hash. The wire output matches xray-core's
/// `aead.Seal(SessionId[:0], Random[20:], SessionId[:16], Raw)`.
pub(crate) fn seal_session_id(
    priv_key: &[u8; 32],
    server_pub: &[u8; 32],
    client_random: &[u8; 32],
    short_id: &[u8],
    raw_hello: &[u8],
    now_secs: u32,
) -> io::Result<[u8; 32]> {
    if raw_hello.len() < SESSION_ID_END {
        return Err(io::Error::other(format!(
            "raw_hello too short for Reality seal: {} < {}",
            raw_hello.len(),
            SESSION_ID_END
        )));
    }

    let auth_key = derive_auth_key(priv_key, server_pub, client_random);
    let plaintext = build_session_id_plaintext(short_id, now_secs);

    let mut aad_buf = raw_hello.to_vec();
    aad_buf[SESSION_ID_OFFSET..SESSION_ID_END].fill(0);

    let unbound = UnboundKey::new(&AES_256_GCM, auth_key.as_slice())
        .map_err(|_| io::Error::other("AES-256-GCM key construction failed"))?;
    let key = LessSafeKey::new(unbound);
    let nonce_bytes: [u8; 12] = client_random[20..32].try_into().expect("12-byte nonce slice from 32B random");
    let nonce = Nonce::assume_unique_for_key(nonce_bytes);

    let mut in_out = plaintext.to_vec();
    key.seal_in_place_append_tag(nonce, Aad::from(aad_buf.as_slice()), &mut in_out)
        .map_err(|_| io::Error::other("AES-256-GCM seal failed"))?;

    if in_out.len() != SESSION_ID_LEN {
        return Err(io::Error::other(format!(
            "unexpected sealed length: {} (expected {SESSION_ID_LEN})",
            in_out.len()
        )));
    }
    let mut out = [0u8; SESSION_ID_LEN];
    out.copy_from_slice(&in_out);
    Ok(out)
}

/// Convenience wrapper that supplies `SystemTime::now()` as the timestamp.
#[allow(dead_code)]
pub(crate) fn seal_session_id_now(
    priv_key: &[u8; 32],
    server_pub: &[u8; 32],
    client_random: &[u8; 32],
    short_id: &[u8],
    raw_hello: &[u8],
) -> io::Result<[u8; 32]> {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as u32;
    seal_session_id(priv_key, server_pub, client_random, short_id, raw_hello, now)
}

struct AuthKeyLen;

impl hkdf::KeyType for AuthKeyLen {
    fn len(&self) -> usize {
        32
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::aead::{BoundKey, NonceSequence, OpeningKey};

    fn make_raw_hello() -> Vec<u8> {
        // Stub ClientHello layout up through session_id slot:
        //   type(1) | length(3) | legacy_version(2) | random(32) |
        //   session_id_length(1) | session_id(32) | trailer(8) = 80 B.
        let mut v = vec![0u8; 80];
        v[0] = 0x01; // handshake type = ClientHello
        v[1..4].copy_from_slice(&[0x00, 0x00, 0x4c]); // body length = 76
        v[4..6].copy_from_slice(&[0x03, 0x03]); // legacy_version = TLS 1.2
        v[38] = 0x20; // session_id_length = 32
        v
    }

    fn server_pub_from_seed(seed: u8) -> [u8; 32] {
        let secret = StaticSecret::from([seed; 32]);
        *PublicKey::from(&secret).as_bytes()
    }

    /// Round-trip: seal then decrypt — confirms the AES-256-GCM
    /// construction (key derivation + nonce + AAD + plaintext) is
    /// internally consistent and the output decrypts back to the
    /// expected plaintext bytes.
    #[test]
    fn seal_round_trips_with_matching_inputs() {
        let priv_key = [0x11; 32];
        let server_pub = server_pub_from_seed(0x22);
        let client_random = [0x33; 32];
        let short_id = vec![0xAB, 0xCD];
        let raw = make_raw_hello();
        let now: u32 = 1_700_000_000;

        let sealed =
            seal_session_id(&priv_key, &server_pub, &client_random, &short_id, &raw, now).expect("seal succeeds");
        assert_eq!(sealed.len(), SESSION_ID_LEN);

        let auth_key = derive_auth_key(&priv_key, &server_pub, &client_random);
        let unbound = UnboundKey::new(&AES_256_GCM, auth_key.as_slice()).unwrap();

        struct Once([u8; 12], bool);
        impl NonceSequence for Once {
            fn advance(&mut self) -> Result<Nonce, ring::error::Unspecified> {
                if self.1 {
                    return Err(ring::error::Unspecified);
                }
                self.1 = true;
                Ok(Nonce::assume_unique_for_key(self.0))
            }
        }
        let nonce_bytes: [u8; 12] = client_random[20..32].try_into().unwrap();
        let mut key = OpeningKey::new(unbound, Once(nonce_bytes, false));

        let mut aad_buf = raw.clone();
        aad_buf[SESSION_ID_OFFSET..SESSION_ID_END].fill(0);

        let mut in_out = sealed.to_vec();
        let pt = key.open_in_place(Aad::from(aad_buf.as_slice()), &mut in_out).expect("decrypt");
        let expected_pt = build_session_id_plaintext(&short_id, now);
        assert_eq!(pt, &expected_pt[..]);
    }

    /// AAD must influence the seal — a 1-byte mutation outside the
    /// session_id slot produces a different sealed value. Confirms the
    /// AAD wiring matches xray-core's `aead.Seal(..., ..., ..., Raw)`.
    #[test]
    fn seal_changes_when_raw_hello_changes_outside_session_id_slot() {
        let priv_key = [0x11; 32];
        let server_pub = server_pub_from_seed(0x44);
        let client_random = [0x33; 32];
        let short_id = vec![0xAB];

        let mut raw_a = make_raw_hello();
        let mut raw_b = make_raw_hello();
        raw_a[5] = 0x03;
        raw_b[5] = 0x04;

        let a = seal_session_id(&priv_key, &server_pub, &client_random, &short_id, &raw_a, 100).unwrap();
        let b = seal_session_id(&priv_key, &server_pub, &client_random, &short_id, &raw_b, 100).unwrap();
        assert_ne!(a, b, "AAD must influence the GCM seal");
    }

    /// Mutating bytes inside the session_id slot must NOT change the
    /// seal — those bytes are zeroed before forming the AAD, matching
    /// the `hello.Raw` substitution rule (`SessionId[:0]` overwrite in
    /// xray-core `reality.go:174`).
    #[test]
    fn seal_unaffected_by_session_id_slot_contents() {
        let priv_key = [0x11; 32];
        let server_pub = server_pub_from_seed(0x55);
        let client_random = [0x33; 32];
        let short_id = vec![0xAB];

        let mut raw_a = make_raw_hello();
        let mut raw_b = make_raw_hello();
        for byte in &mut raw_a[SESSION_ID_OFFSET..SESSION_ID_END] {
            *byte = 0xAA;
        }
        for byte in &mut raw_b[SESSION_ID_OFFSET..SESSION_ID_END] {
            *byte = 0xBB;
        }

        let a = seal_session_id(&priv_key, &server_pub, &client_random, &short_id, &raw_a, 100).unwrap();
        let b = seal_session_id(&priv_key, &server_pub, &client_random, &short_id, &raw_b, 100).unwrap();
        assert_eq!(a, b, "session_id slot must be excluded from AAD");
    }

    /// **C1 fix verification.** HKDF salt is `client_random[..20]`;
    /// mutating any byte inside [0..20] must change the AuthKey, and
    /// mutating any byte inside [20..32] must NOT change the AuthKey.
    #[test]
    fn auth_key_salt_uses_first_twenty_bytes_of_client_random() {
        let priv_key = [0x11; 32];
        let server_pub = server_pub_from_seed(0x66);

        let mut cr_a = [0u8; 32];
        let mut cr_b = [0u8; 32];
        cr_a[5] = 0x01;
        cr_b[5] = 0x02;

        let k_a = derive_auth_key(&priv_key, &server_pub, &cr_a);
        let k_b = derive_auth_key(&priv_key, &server_pub, &cr_b);
        assert_ne!(k_a, k_b, "mutation in client_random[..20] must alter AuthKey (C1 spec)");

        let mut cr_c = [0u8; 32];
        let mut cr_d = [0u8; 32];
        cr_c[25] = 0x01;
        cr_d[25] = 0x02;
        let k_c = derive_auth_key(&priv_key, &server_pub, &cr_c);
        let k_d = derive_auth_key(&priv_key, &server_pub, &cr_d);
        assert_eq!(k_c, k_d, "mutation in client_random[20..] must NOT alter AuthKey (C1 spec)");
    }

    /// The nonce is `client_random[20..32]` — mutating any byte in
    /// that range must change the sealed output (the AuthKey stays
    /// stable per the C1 test above, so the difference is attributable
    /// to the nonce alone).
    #[test]
    fn seal_nonce_uses_last_twelve_bytes_of_client_random() {
        let priv_key = [0x11; 32];
        let server_pub = server_pub_from_seed(0x77);
        let short_id = vec![0xCD];
        let raw = make_raw_hello();

        let mut cr_a = [0x33u8; 32];
        let mut cr_b = [0x33u8; 32];
        cr_a[25] = 0xAA;
        cr_b[25] = 0xBB;

        let a = seal_session_id(&priv_key, &server_pub, &cr_a, &short_id, &raw, 100).unwrap();
        let b = seal_session_id(&priv_key, &server_pub, &cr_b, &short_id, &raw, 100).unwrap();
        assert_ne!(a, b, "nonce change must alter GCM ciphertext+tag (C2 spec)");
    }

    /// Short raw_hello (< session_id end offset) is rejected with a
    /// named error rather than panicking on a slice bounds violation.
    #[test]
    fn seal_rejects_short_raw_hello() {
        let result = seal_session_id(&[0; 32], &[0; 32], &[0; 32], &[], &[0; SESSION_ID_END - 1], 0);
        assert!(result.is_err(), "raw_hello < 71 bytes must error");
    }

    /// Plaintext layout: version `0x00 0x00 0x01`, reserved zero,
    /// timestamp big-endian, short_id padded.
    #[test]
    fn plaintext_layout_matches_xray_reality_signature() {
        let pt = build_session_id_plaintext(&[0xAB, 0xCD], 0x01020304);
        assert_eq!(pt[0..3], [0x00, 0x00, 0x01]);
        assert_eq!(pt[3], 0x00);
        assert_eq!(pt[4..8], [0x01, 0x02, 0x03, 0x04]);
        assert_eq!(pt[8..10], [0xAB, 0xCD]);
        assert_eq!(pt[10..16], [0; 6]);
    }

    /// Short ids longer than 8 bytes are truncated (matches xray-core
    /// upstream, which restricts shortid to 8 hex bytes max).
    #[test]
    fn plaintext_truncates_oversized_short_id() {
        let long = [0xAA; 16];
        let pt = build_session_id_plaintext(&long, 0);
        assert_eq!(pt[8..16], [0xAA; 8]);
    }

    /// `seal_session_id_now` is a thin wrapper around `seal_session_id`
    /// with `SystemTime::now()` plumbed in; verify it returns 32 bytes
    /// and does not panic on a minimum-length raw_hello.
    #[test]
    fn seal_session_id_now_returns_full_slot() {
        let raw = make_raw_hello();
        let sealed = seal_session_id_now(&[0x11; 32], &server_pub_from_seed(0x88), &[0x33; 32], &[0x01], &raw)
            .expect("seal_session_id_now succeeds");
        assert_eq!(sealed.len(), SESSION_ID_LEN);
    }

    /// **Frozen test vector.** Locks the output of `seal_session_id`
    /// against accidental regression (a wrong-direction salt slice,
    /// an off-by-one in the AAD zeroing window, a swap from
    /// AES-256-GCM to some other AEAD). The expected bytes were
    /// captured from this same implementation; if a future commit
    /// changes the seal output, this test fails and the diff must be
    /// reviewed against `docs/design/reality-boringssl-patch.md`
    /// before re-blessing.
    ///
    /// Inputs are also mirrored by `test-lab/reality-vector/main.go`,
    /// the Go reference program used to confirm cross-implementation
    /// interop with xray-core upstream.
    #[test]
    fn seal_session_id_matches_frozen_vector() {
        let (priv_key, server_pub, client_random, short_id, raw, now) = frozen_vector_inputs();
        let sealed =
            seal_session_id(&priv_key, &server_pub, &client_random, &short_id, &raw, now).expect("seal frozen vector");
        let expected: [u8; SESSION_ID_LEN] = [
            0x17, 0xa7, 0xaf, 0x6b, 0x73, 0x36, 0x79, 0x33, 0xc3, 0x4d, 0xdc, 0x9a, 0x7a, 0x3a, 0xfb, 0xc1, 0x7b, 0x75,
            0xfa, 0x06, 0x3c, 0x98, 0xb6, 0xaa, 0xda, 0x10, 0x7d, 0xc5, 0x90, 0x85, 0x3d, 0xe0,
        ];
        assert_eq!(sealed, expected, "frozen vector regressed; review against the Reality spec before re-blessing");
    }

    fn frozen_vector_inputs() -> ([u8; 32], [u8; 32], [u8; 32], Vec<u8>, Vec<u8>, u32) {
        let priv_key = [0x11u8; 32];
        let secret = StaticSecret::from([0x22u8; 32]);
        let server_pub = *PublicKey::from(&secret).as_bytes();
        let mut client_random = [0u8; 32];
        for (i, byte) in client_random.iter_mut().enumerate() {
            *byte = (i as u8).wrapping_add(1);
        }
        let short_id = vec![0xAB, 0xCD];
        let mut raw = vec![0u8; 80];
        raw[0] = 0x01;
        raw[1..4].copy_from_slice(&[0x00, 0x00, 0x4c]);
        raw[4..6].copy_from_slice(&[0x03, 0x03]);
        raw[6..38].copy_from_slice(&client_random);
        raw[38] = 0x20;
        let now: u32 = 1_700_000_000;
        (priv_key, server_pub, client_random, short_id, raw, now)
    }
}
