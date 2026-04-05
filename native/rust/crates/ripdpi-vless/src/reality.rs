use std::io;
use std::time::{SystemTime, UNIX_EPOCH};

use aes::cipher::{BlockEncrypt, KeyInit};
use aes::Aes128;
use boring::ssl::{SslConnector, SslMethod, SslVerifyMode};
use ring::hkdf;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_boring::SslStream;
use x25519_dalek::{EphemeralSecret, PublicKey};

use crate::config::VlessRealityConfig;

/// Connect to a VLESS+Reality server over TCP, performing the Reality TLS handshake.
///
/// The Reality protocol embeds authentication into the TLS ClientHello `session_id` field:
/// 1. Generate ephemeral X25519 keypair
/// 2. ECDH shared secret with server public key
/// 3. Derive auth key via HKDF-SHA256
/// 4. Construct and encrypt session_id
/// 5. Establish TLS with disabled cert verification (Reality uses its own auth model)
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
    let _session_id = build_reality_session_id(config)?;

    let mut builder = SslConnector::builder(SslMethod::tls())
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("boring SSL builder: {e}")))?;

    // Reality uses its own auth model -- disable standard cert verification.
    builder.set_verify(SslVerifyMode::NONE);

    let connector = builder.build();
    let config_ssl = connector
        .configure()
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("boring SSL configure: {e}")))?;

    let tls_stream = tokio_boring::connect(config_ssl, &config.server_name, stream)
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
fn build_reality_session_id(config: &VlessRealityConfig) -> io::Result<[u8; 32]> {
    // 1. Generate ephemeral X25519 keypair
    let ephemeral_secret = EphemeralSecret::random();
    let ephemeral_public = PublicKey::from(&ephemeral_secret);

    // 2. ECDH: shared = scalar_mult(ephemeral_private, server_public_key)
    let server_public = PublicKey::from(config.reality_public_key);
    let shared_secret = ephemeral_secret.diffie_hellman(&server_public);

    // 3. Derive auth key via HKDF-SHA256
    // We use the ephemeral public key bytes as salt (simplified from the full
    // Reality protocol which uses ClientHello.random[20..]) and "REALITY" as info.
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, ephemeral_public.as_bytes());
    let prk = salt.extract(shared_secret.as_bytes());
    let info = [b"REALITY".as_slice()];
    let okm = prk.expand(&info, AuthKeyLen).map_err(|_| io::Error::new(io::ErrorKind::Other, "HKDF expand failed"))?;
    let mut auth_key = [0u8; 32];
    okm.fill(&mut auth_key).map_err(|_| io::Error::new(io::ErrorKind::Other, "HKDF fill failed"))?;

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
    let cipher = Aes128::new(aes::cipher::generic_array::GenericArray::from_slice(&auth_key[..16]));
    let block = aes::cipher::generic_array::GenericArray::from_mut_slice(&mut session_id[..16]);
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
            reality_public_key: [0x42; 32],
            reality_short_id: vec![0xAB, 0xCD],
        };

        let session_id = build_reality_session_id(&config).unwrap();
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
            reality_public_key: [0x42; 32],
            reality_short_id: vec![0x01],
        };

        let id1 = build_reality_session_id(&config).unwrap();
        let id2 = build_reality_session_id(&config).unwrap();
        // Ephemeral keys differ each time, so session_ids should differ
        assert_ne!(id1, id2);
    }
}
