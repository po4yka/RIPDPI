use std::io;
use std::time::{SystemTime, UNIX_EPOCH};

use aes::cipher::{BlockCipherEncrypt, KeyInit};
use aes::Aes128;
use boring::rand::rand_bytes;
use boring::ssl::SslVerifyMode;
use foreign_types_shared::ForeignType;
use ring::hkdf;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_boring::SslStream;
use x25519_dalek::{EphemeralSecret, PublicKey};

use crate::config::VlessRealityConfig;

// BoringSSL FFI functions not publicly re-exported by the boring crate.
// boring-sys uses generated bindings; SSL_set_client_random, SSL_SESSION_new,
// SSL_SESSION_set1_id are absent from those bindings, and SSL_set_session /
// SSL_SESSION_free are present but only accessible via boring's private ffi alias.
// Declaring all five here avoids a boring-sys direct dependency.
// SAFETY: These are stable BoringSSL ABI with correct signatures per boringssl/ssl.h.
extern "C" {
    /// Forces the ClientHello random field to the provided bytes.
    /// Returns 1 on success, 0 on failure.
    fn SSL_set_client_random(ssl: *mut std::ffi::c_void, random: *const u8, random_len: usize) -> std::ffi::c_int;

    /// Allocates a new SSL_SESSION object. |ctx| must not be null. Returns null on allocation failure.
    fn SSL_SESSION_new(ctx: *mut std::ffi::c_void) -> *mut std::ffi::c_void;

    /// Sets the session ID on an SSL_SESSION. Returns 1 on success, 0 on failure.
    fn SSL_SESSION_set1_id(session: *mut std::ffi::c_void, sid: *const u8, sid_len: u32) -> std::ffi::c_int;

    /// Associates a session with an SSL object (increments session refcount).
    /// Returns 1 on success, 0 on failure.
    fn SSL_set_session(ssl: *mut std::ffi::c_void, session: *mut std::ffi::c_void) -> std::ffi::c_int;

    /// Decrements the refcount of a session, freeing it when it reaches zero.
    fn SSL_SESSION_free(session: *mut std::ffi::c_void);

    /// Returns the SSL_CTX associated with the given SSL object.
    fn SSL_get_SSL_CTX(ssl: *const std::ffi::c_void) -> *mut std::ffi::c_void;
}

/// Connect to a VLESS+Reality server over TCP, performing the Reality TLS handshake.
///
/// The Reality protocol embeds authentication into the TLS ClientHello `session_id` field:
/// 1. Generate ephemeral X25519 keypair
/// 2. ECDH shared secret with server public key
/// 3. Derive auth key via HKDF-SHA256 (salt = client_random[20..])
/// 4. Construct and encrypt session_id
/// 5. Inject client_random and session_id into the SSL object before handshake
/// 6. Establish TLS with disabled cert verification (Reality uses its own auth model)
pub async fn connect_reality_tls(tcp: TcpStream, config: &VlessRealityConfig) -> io::Result<SslStream<TcpStream>> {
    connect_reality_tls_inner(tcp, config).await
}

/// Connect Reality TLS over an arbitrary async transport (for chain relay).
///
/// This wraps an existing `AsyncRead + AsyncWrite` stream with Reality TLS,
/// used when tunneling VLESS-over-VLESS (two-hop chain relay).
pub async fn connect_reality_tls_over<S>(transport: S, config: &VlessRealityConfig) -> io::Result<SslStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    connect_reality_tls_inner(transport, config).await
}

/// Generic Reality TLS connection over any async stream.
async fn connect_reality_tls_inner<S>(stream: S, config: &VlessRealityConfig) -> io::Result<SslStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // 1. Generate 32-byte client_random that we will inject into the ClientHello.
    let mut client_random = [0u8; 32];
    rand_bytes(&mut client_random).map_err(|e| io::Error::other(format!("rand_bytes: {e}")))?;

    // 2. Build session_id using client_random[20..] as HKDF salt (Reality spec).
    let session_id = build_reality_session_id(config, &client_random)?;

    // 3. Build the SSL connector with the chosen TLS fingerprint profile.
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|e| io::Error::other(format!("TLS profile: {e}")))?;

    // Reality uses its own auth model -- disable standard cert verification.
    builder.set_verify(SslVerifyMode::NONE);

    let connector = builder.build();
    let config_ssl = connector.configure().map_err(|e| io::Error::other(format!("boring SSL configure: {e}")))?;

    // 4. Obtain the Ssl object so we can mutate it before the handshake.
    let ssl = config_ssl.into_ssl(&config.server_name).map_err(|e| io::Error::other(format!("SSL configure: {e}")))?;

    // 5. Inject client_random into the ClientHello random field.
    // SAFETY: ssl.as_ptr() is valid for the lifetime of ssl; SSL_set_client_random
    // copies the bytes internally and does not retain the pointer after return.
    let ret = unsafe {
        SSL_set_client_random(ssl.as_ptr().cast::<std::ffi::c_void>(), client_random.as_ptr(), client_random.len())
    };
    if ret != 1 {
        return Err(io::Error::other("SSL_set_client_random failed"));
    }

    // 6. Inject session_id: allocate a session, assign the ID, attach to the SSL object.
    // SAFETY: all five FFI functions are called per their documented BoringSSL contract.
    // SSL_SESSION_new returns an owned pointer; SSL_set_session increments its refcount;
    // SSL_SESSION_free decrements it, so the session is freed on the next SSL_free.
    unsafe {
        let ssl_ctx = SSL_get_SSL_CTX(ssl.as_ptr().cast::<std::ffi::c_void>());
        let sess = SSL_SESSION_new(ssl_ctx);
        if sess.is_null() {
            return Err(io::Error::other("SSL_SESSION_new failed"));
        }
        let id_ret = SSL_SESSION_set1_id(sess, session_id.as_ptr(), session_id.len() as u32);
        let set_ret = SSL_set_session(ssl.as_ptr().cast::<std::ffi::c_void>(), sess);
        SSL_SESSION_free(sess);
        if id_ret != 1 || set_ret != 1 {
            return Err(io::Error::other("Reality session_id injection failed"));
        }
    }

    // 7. Complete the TLS handshake.
    let stream_builder = tokio_boring::SslStreamBuilder::new(ssl, stream);
    let tls_stream = stream_builder
        .connect()
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("Reality TLS handshake: {e}")))?;

    tracing::debug!("Reality TLS handshake completed to {}", config.server_name);
    Ok(tls_stream)
}

/// Build the 32-byte Reality session_id.
///
/// Format:
/// ```text
/// [version(3B: 0x00,0x00,0x01)]
/// [reserved(1B: 0x00)]
/// [unix_timestamp_secs(4B BE)]
/// [short_id padded to 8B]
/// [ephemeral_public_key first 16B]
/// ```
///
/// The first 16 bytes are encrypted with AES-128-ECB using `auth_key[0..16]`
/// derived from ECDH + HKDF.
///
/// `client_random` must be the 32-byte ClientHello random that was (or will be)
/// injected into the TLS ClientHello. The HKDF salt is `client_random[20..]`
/// (the last 12 bytes) per the xray-core Reality specification.
fn build_reality_session_id(config: &VlessRealityConfig, client_random: &[u8; 32]) -> io::Result<[u8; 32]> {
    // 1. Generate ephemeral X25519 keypair
    let ephemeral_secret = EphemeralSecret::random();
    let ephemeral_public = PublicKey::from(&ephemeral_secret);

    // 2. ECDH: shared = scalar_mult(ephemeral_private, server_public_key)
    let server_public = PublicKey::from(config.reality_public_key);
    let shared_secret = ephemeral_secret.diffie_hellman(&server_public);

    // 3. Derive auth key via HKDF-SHA256.
    // Salt = client_random[20..] (last 12 bytes of the 32-byte ClientHello random)
    // per the xray-core Reality protocol specification.
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, &client_random[20..]);
    let prk = salt.extract(shared_secret.as_bytes());
    let info = [b"REALITY".as_slice()];
    let okm = prk.expand(&info, AuthKeyLen).map_err(|_| io::Error::other("HKDF expand failed"))?;
    let mut auth_key = [0u8; 32];
    okm.fill(&mut auth_key).map_err(|_| io::Error::other("HKDF fill failed"))?;

    // 4. Construct session_id plaintext
    let mut session_id = [0u8; 32];

    // Version
    session_id[0] = 0x00;
    session_id[1] = 0x00;
    session_id[2] = 0x01;

    // Reserved
    session_id[3] = 0x00;

    // Timestamp (4 bytes, big-endian)
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as u32;
    session_id[4..8].copy_from_slice(&now.to_be_bytes());

    // Short ID (padded to 8 bytes)
    let sid_len = config.reality_short_id.len().min(8);
    session_id[8..8 + sid_len].copy_from_slice(&config.reality_short_id[..sid_len]);

    // Ephemeral public key (first 16 bytes fill positions 16..32)
    session_id[16..32].copy_from_slice(&ephemeral_public.as_bytes()[..16]);

    // 5. Encrypt first 16 bytes with AES-128-ECB using auth_key[0..16]
    let cipher = Aes128::new((&auth_key[..16]).try_into().expect("AES-128 key length"));
    let block: &mut aes::cipher::array::Array<u8, _> = (&mut session_id[..16]).try_into().expect("AES block length");
    cipher.encrypt_block(block);

    Ok(session_id)
}

/// HKDF output length marker for 32 bytes.
struct AuthKeyLen;

impl hkdf::KeyType for AuthKeyLen {
    fn len(&self) -> usize {
        32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_id_is_32_bytes() {
        let config = VlessRealityConfig {
            server: "example.com".to_owned(),
            port: 443,
            uuid: [0; 16],
            server_name: "www.example.com".to_owned(),
            tls_fingerprint_profile: "chrome_stable".to_owned(),
            reality_public_key: [0x42; 32],
            reality_short_id: vec![0xAB, 0xCD],
        };

        let client_random = [0u8; 32];
        let session_id = build_reality_session_id(&config, &client_random).unwrap();
        assert_eq!(session_id.len(), 32);
        // The session_id should not be all zeros (it's encrypted + has timestamp)
        assert_ne!(session_id, [0u8; 32]);
    }

    #[test]
    fn session_id_varies_per_call() {
        let config = VlessRealityConfig {
            server: "example.com".to_owned(),
            port: 443,
            uuid: [0; 16],
            server_name: "www.example.com".to_owned(),
            tls_fingerprint_profile: "chrome_stable".to_owned(),
            reality_public_key: [0x42; 32],
            reality_short_id: vec![0x01],
        };

        // With the same client_random, each call still generates a fresh ephemeral
        // X25519 keypair, so the encrypted content (positions 16..32) and the
        // derived auth_key differ, producing distinct session_ids.
        let client_random = [0u8; 32];
        let id1 = build_reality_session_id(&config, &client_random).unwrap();
        let id2 = build_reality_session_id(&config, &client_random).unwrap();
        assert_ne!(id1, id2);
    }
}
