//! Shadowsocks TCP framing — encrypt/decrypt with AEAD chunked framing.
//!
//! # Payload wire format (SIP004 / SIP022 TCP)
//!
//! ```text
//! [salt (salt_len bytes)]
//! [chunk 0]
//! [chunk 1]
//! ...
//!
//! Each chunk:
//! [2-byte big-endian length ciphertext + tag (2 + tag_len bytes)]
//! [payload ciphertext + tag (length_plaintext + tag_len bytes)]
//! ```
//!
//! SIP022 streams start with standalone request/response headers before payload chunks.
//! Use the header methods for the first message; see <https://shadowsocks.org/doc/sip022.html>.
//!
//! The nonce for chunk N is the 96-bit little-endian representation of N,
//! incremented by 1 for each chunk (counter mode).

use rand::RngExt;

use crate::cipher::{Cipher, CipherError, CipherKey, SecretString};

/// Maximum plaintext payload per SIP004 chunk.
const SIP004_MAX_CHUNK_LEN: usize = 0x3FFF;

/// Maximum plaintext payload per SIP022 chunk.
const SIP022_MAX_CHUNK_LEN: usize = 0xFFFF;

/// Shadowsocks TCP stream framer/deframer.
///
/// Encrypts plaintext into chunked AEAD frames (with a leading salt) and
/// decrypts them back. This type does not own a network socket — the caller
/// supplies raw byte slices and receives `Vec<u8>` back. Tokio integration
/// lives one layer up in the adapter crates.
pub struct TcpStream {
    cipher: Cipher,
    key: CipherKey,
    counter: u64,
}

impl TcpStream {
    /// Creates a new framer for the given cipher and pre-shared key material.
    ///
    /// For AEAD-2022 ciphers `password` must contain the base64-encoded PSK
    /// from the URI `password` field. For legacy AEAD ciphers
    /// it is the UTF-8 password string wrapped in [`SecretString`].
    pub fn new_encrypt(
        cipher: Cipher,
        password: &SecretString,
        is_aead_2022: bool,
    ) -> Result<(Self, Vec<u8>), CipherError> {
        let mut salt = vec![0u8; cipher.salt_len()];
        rand::rng().fill(&mut salt[..]);
        let key = if is_aead_2022 {
            let psk = base64::decode_psk(password.expose())?;
            CipherKey::derive_aead2022(cipher, &psk, &salt)?
        } else {
            CipherKey::derive_legacy(cipher, password, &salt)?
        };
        Ok((Self { cipher, key, counter: 0 }, salt))
    }

    /// Creates a new deframer given the `salt` read from the wire.
    pub fn new_decrypt(
        cipher: Cipher,
        password: &SecretString,
        salt: &[u8],
        is_aead_2022: bool,
    ) -> Result<Self, CipherError> {
        let key = if is_aead_2022 {
            let psk = base64::decode_psk(password.expose())?;
            CipherKey::derive_aead2022(cipher, &psk, salt)?
        } else {
            CipherKey::derive_legacy(cipher, password, salt)?
        };
        Ok(Self { cipher, key, counter: 0 })
    }

    /// Encrypts the SIP022 request headers, including random padding.
    /// `request` contains a SOCKS address followed by optional initial payload.
    /// Prepend the salt from `new_encrypt` and send all headers in one buffered write.
    pub fn encrypt_request_header(&mut self, timestamp: u64, request: &[u8]) -> Result<Vec<u8>, CipherError> {
        let address_len = socks_address_len(request)?;
        let padding_len = rand::rng().random_range(1..=900_u16);
        let mut body = request[..address_len].to_vec();
        body.extend_from_slice(&padding_len.to_be_bytes());
        let start = body.len();
        body.resize(start + usize::from(padding_len), 0);
        rand::rng().fill(&mut body[start..]);
        body.extend_from_slice(&request[address_len..]);
        self.encrypt_header(timestamp, None, &body)
    }

    /// Encrypts the SIP022 response header and its first payload chunk.
    /// `request_salt` binds this response to the client's request.
    pub fn encrypt_response_header(
        &mut self,
        timestamp: u64,
        request_salt: &[u8],
        payload: &[u8],
    ) -> Result<Vec<u8>, CipherError> {
        if payload.is_empty() {
            return Err(CipherError::InvalidPacket("SIP022 response requires payload"));
        }
        self.encrypt_header(timestamp, Some(request_salt), payload)
    }

    /// Decrypts and validates SIP022 request headers, removing padding.
    /// The result contains the SOCKS address and optional initial payload.
    /// A server must also check the request salt against its replay cache.
    pub fn decrypt_request_header(&mut self, data: &[u8], now: u64) -> Result<Option<(Vec<u8>, usize)>, CipherError> {
        let Some((body, consumed)) = self.decrypt_header(data, None, now)? else {
            return Ok(None);
        };
        let address_len = socks_address_len(&body)?;
        let padding = body
            .get(address_len..address_len + 2)
            .ok_or(CipherError::InvalidPacket("missing SIP022 request padding length"))?;
        let padding_len = usize::from(u16::from_be_bytes([padding[0], padding[1]]));
        let payload = body
            .get(address_len + 2 + padding_len..)
            .ok_or(CipherError::InvalidPacket("truncated SIP022 request padding"))?;
        if padding_len > 900 || (padding_len == 0 && payload.is_empty()) {
            return Err(CipherError::InvalidPacket("invalid SIP022 request padding"));
        }
        let mut request = body[..address_len].to_vec();
        request.extend_from_slice(payload);
        Ok(Some((request, consumed)))
    }

    /// Decrypts the SIP022 response header and first payload.
    /// Validates direction, timestamp and request-salt binding before returning data.
    pub fn decrypt_response_header(
        &mut self,
        data: &[u8],
        request_salt: &[u8],
        now: u64,
    ) -> Result<Option<(Vec<u8>, usize)>, CipherError> {
        self.decrypt_header(data, Some(request_salt), now)
    }

    fn check_header_state(&self, request_salt: Option<&[u8]>) -> Result<(), CipherError> {
        if !self.cipher.is_aead_2022() || self.counter != 0 {
            return Err(CipherError::InvalidPacket("SIP022 header must start a new stream"));
        }
        if request_salt.is_some_and(|salt| salt.len() != self.cipher.salt_len()) {
            return Err(CipherError::InvalidPacket("invalid SIP022 request salt length"));
        }
        Ok(())
    }

    fn encrypt_header(
        &mut self,
        timestamp: u64,
        request_salt: Option<&[u8]>,
        payload: &[u8],
    ) -> Result<Vec<u8>, CipherError> {
        self.check_header_state(request_salt)?;
        let length = u16::try_from(payload.len()).map_err(|_| CipherError::InvalidPacket("SIP022 header too large"))?;
        let mut header = vec![u8::from(request_salt.is_some())];
        header.extend_from_slice(&timestamp.to_be_bytes());
        if let Some(salt) = request_salt {
            header.extend_from_slice(salt);
        }
        header.extend_from_slice(&length.to_be_bytes());
        let mut encrypted = self.key.encrypt(&counter_nonce(0), &header)?;
        encrypted.extend(self.key.encrypt(&counter_nonce(1), payload)?);
        self.counter = 2;
        Ok(encrypted)
    }

    fn decrypt_header(
        &mut self,
        data: &[u8],
        request_salt: Option<&[u8]>,
        now: u64,
    ) -> Result<Option<(Vec<u8>, usize)>, CipherError> {
        self.check_header_state(request_salt)?;
        let header_len = 11 + request_salt.map_or(0, <[u8]>::len);
        let header_frame = header_len + self.cipher.tag_len();
        let Some(encrypted_header) = data.get(..header_frame) else {
            return Ok(None);
        };
        let header = self.key.decrypt(&counter_nonce(0), encrypted_header)?;
        if header[0] != u8::from(request_salt.is_some()) {
            return Err(CipherError::InvalidPacket("unexpected SIP022 TCP header type"));
        }
        let timestamp = u64::from_be_bytes(header[1..9].try_into().map_err(|_| CipherError::AeadFailed)?);
        if timestamp.abs_diff(now) > 30 {
            return Err(CipherError::Replay);
        }
        if let Some(salt) = request_salt
            && &header[9..header_len - 2] != salt
        {
            return Err(CipherError::InvalidPacket("SIP022 response request salt mismatch"));
        }
        let length = usize::from(u16::from_be_bytes([header[header_len - 2], header[header_len - 1]]));
        if request_salt.is_some() && length == 0 {
            return Err(CipherError::InvalidPacket("SIP022 response requires payload"));
        }
        let consumed = header_frame + length + self.cipher.tag_len();
        let Some(encrypted_payload) = data.get(header_frame..consumed) else {
            return Ok(None);
        };
        let payload = self.key.decrypt(&counter_nonce(1), encrypted_payload)?;
        self.counter = 2;
        Ok(Some((payload, consumed)))
    }

    /// Encrypts `plaintext` into one or more AEAD chunks (without the leading
    /// salt — the caller prepends it from [`new_encrypt`]).
    pub fn encrypt_payload(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        let mut out = Vec::new();
        let mut offset = 0;
        let max_chunk_len = max_chunk_len(self.cipher);
        while offset < plaintext.len() || (plaintext.is_empty() && offset == 0) {
            let end = (offset + max_chunk_len).min(plaintext.len());
            let chunk = &plaintext[offset..end];
            let len_bytes = (chunk.len() as u16).to_be_bytes();

            // Encrypt 2-byte length field.
            let nonce = counter_nonce(self.counter);
            self.counter += 1;
            let len_ct = self.key.encrypt(&nonce, &len_bytes)?;
            out.extend_from_slice(&len_ct);

            // Encrypt payload.
            let nonce2 = counter_nonce(self.counter);
            self.counter += 1;
            let payload_ct = self.key.encrypt(&nonce2, chunk)?;
            out.extend_from_slice(&payload_ct);

            offset = end;
            if plaintext.is_empty() {
                break;
            }
        }
        Ok(out)
    }

    /// Decrypts a single AEAD chunk from `data` starting at `offset`.
    ///
    /// Returns `(plaintext, bytes_consumed)` on success. Returns `None` when
    /// there are not yet enough bytes for a complete chunk (caller should buffer
    /// and retry). Returns `Err` on authentication failure.
    pub fn decrypt_chunk(&mut self, data: &[u8], offset: usize) -> Result<Option<(Vec<u8>, usize)>, CipherError> {
        let data = data.get(offset..).ok_or(CipherError::InvalidPacket("invalid TCP frame offset"))?;
        let offset = 0;
        let tag_len = self.cipher.tag_len();
        let len_frame = 2 + tag_len;

        if data.len() < offset + len_frame {
            return Ok(None);
        }

        // Decrypt length field.
        let nonce = counter_nonce(self.counter);
        let len_ct = &data[offset..offset + len_frame];
        let len_bytes = self.key.decrypt(&nonce, len_ct)?;
        let payload_len = u16::from_be_bytes([len_bytes[0], len_bytes[1]]) as usize;
        if payload_len > max_chunk_len(self.cipher) {
            return Err(CipherError::InvalidPacket("TCP chunk exceeds cipher limit"));
        }
        let payload_frame = payload_len + tag_len;

        if data.len() < offset + len_frame + payload_frame {
            return Ok(None);
        }

        self.counter += 1;
        let nonce2 = counter_nonce(self.counter);
        self.counter += 1;
        let payload_ct = &data[offset + len_frame..offset + len_frame + payload_frame];
        let plaintext = self.key.decrypt(&nonce2, payload_ct)?;

        Ok(Some((plaintext, len_frame + payload_frame)))
    }
}

fn socks_address_len(data: &[u8]) -> Result<usize, CipherError> {
    let len = match data.first() {
        Some(1) => 7,
        Some(4) => 19,
        Some(3) => match data.get(1) {
            Some(1..=255) => 4 + usize::from(data[1]),
            _ => return Err(CipherError::InvalidPacket("invalid SOCKS domain length")),
        },
        _ => return Err(CipherError::InvalidPacket("invalid SOCKS address type")),
    };
    if data.len() < len {
        return Err(CipherError::InvalidPacket("truncated SOCKS address"));
    }
    Ok(len)
}

/// Builds a 12-byte little-endian nonce from a 64-bit counter (upper 4 bytes = 0).
fn counter_nonce(counter: u64) -> Vec<u8> {
    let mut nonce = [0u8; 12];
    nonce[..8].copy_from_slice(&counter.to_le_bytes());
    nonce.to_vec()
}

fn max_chunk_len(cipher: Cipher) -> usize {
    if cipher.is_aead_2022() { SIP022_MAX_CHUNK_LEN } else { SIP004_MAX_CHUNK_LEN }
}

/// Minimal base64 decode helper scoped to this module.
mod base64 {
    use crate::cipher::CipherError;

    pub fn decode_psk(b64: &str) -> Result<Vec<u8>, CipherError> {
        ::base64::engine::Engine::decode(&::base64::engine::general_purpose::STANDARD, b64.trim())
            .or_else(|_| {
                ::base64::engine::Engine::decode(&::base64::engine::general_purpose::URL_SAFE_NO_PAD, b64.trim())
            })
            .map_err(|_| CipherError::KeyLength)
    }
}
