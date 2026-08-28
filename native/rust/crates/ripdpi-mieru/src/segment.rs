//! Mieru segment framing over a TCP carrier — AEAD-sealed metadata + optional
//! AEAD-sealed payload, with length-delimited random padding. Transcribed from
//! upstream `docs/protocol.md` §3 and `pkg/protocol/segment.go`; see
//! `PROTOCOL.md` §3-4.
//!
//! On the TCP carrier the 24-byte nonce appears once per direction (first
//! segment); thereafter both peers advance their nonce in lockstep. `padding 1`
//! / `padding 2` lengths travel inside the (decrypted) metadata, so the reader
//! can skip them. `padding 0` is omitted on the stream carrier (its length is
//! not framed) — the prefix/suffix padding already supplies the entropy.

use ring::rand::{SecureRandom, SystemRandom};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::cipher::{self, KEY_LEN, NONCE_LEN, Nonce, TAG_LEN};
use crate::error::{MieruError, Result};
use crate::metadata::{DataAckMeta, METADATA_LEN, ProtocolType, SessionMeta};

/// Upper bound on the random prefix/suffix padding we *emit* (the field is a
/// `u8`, so the wire maximum is 255; we cap lower to bound per-segment overhead).
/// The reader accepts any length the metadata declares.
const MAX_EMIT_PADDING: u8 = 64;
/// Largest plaintext fragment carried in one data segment (upstream `maxPDU`).
pub(crate) const MAX_PDU: usize = 32 * 1024;

/// Outbound (encrypt) half of a connection: fixed AEAD key + this direction's
/// incrementing nonce. The nonce seed is emitted on the wire with the first
/// segment.
pub(crate) struct Encryptor {
    key: [u8; KEY_LEN],
    username: Vec<u8>,
    rng: SystemRandom,
    send_nonce: Option<Nonce>,
}

impl Encryptor {
    pub(crate) fn new(key: [u8; KEY_LEN], username: Vec<u8>) -> Self {
        Self { key, username, rng: SystemRandom::new(), send_nonce: None }
    }

    fn random_padding_len(&self) -> Result<u8> {
        let mut byte = [0u8; 1];
        self.rng.fill(&mut byte).map_err(|_| MieruError::Crypto("padding rng"))?;
        Ok(byte[0] % (MAX_EMIT_PADDING + 1))
    }

    fn random_bytes(&self, len: usize) -> Result<Vec<u8>> {
        let mut buf = vec![0u8; len];
        if len > 0 {
            self.rng.fill(&mut buf).map_err(|_| MieruError::Crypto("padding rng"))?;
        }
        Ok(buf)
    }

    /// Write one segment. `build_meta(prefix_len, suffix_len)` returns the
    /// 32-byte metadata with the chosen padding lengths already encoded, so the
    /// on-wire padding matches the sealed metadata. `payload` is the plaintext
    /// to seal (raw TCP bytes for data segments), or `None`.
    pub(crate) async fn write_segment<W, F>(
        &mut self,
        writer: &mut W,
        prefix_len: u8,
        suffix_len: u8,
        build_meta: F,
        payload: Option<&[u8]>,
    ) -> Result<()>
    where
        W: AsyncWrite + Unpin,
        F: FnOnce(u8, u8) -> [u8; METADATA_LEN],
    {
        let first = self.send_nonce.is_none();
        if first {
            self.send_nonce = Some(Nonce::random(&self.username, &self.rng)?);
        }
        let meta = build_meta(prefix_len, suffix_len);

        // Seal metadata (and payload) under the send nonce in a scoped borrow so
        // the immutable `self` accesses below (padding RNG) don't conflict.
        let (seed, sealed_meta, sealed_payload) = {
            let nonce = self.send_nonce.as_mut().expect("send nonce initialised above");
            let seed = if first { Some(*nonce.as_bytes()) } else { None };
            let sealed_meta = cipher::seal(&self.key, nonce, &meta)?;
            nonce.increment();
            let sealed_payload = match payload {
                Some(plain) => {
                    let sealed = cipher::seal(&self.key, nonce, plain)?;
                    nonce.increment();
                    Some(sealed)
                }
                None => None,
            };
            (seed, sealed_meta, sealed_payload)
        };

        let prefix_pad = self.random_bytes(usize::from(prefix_len))?;
        let suffix_pad = self.random_bytes(usize::from(suffix_len))?;

        if let Some(seed) = seed {
            writer.write_all(&seed).await?;
        }
        writer.write_all(&sealed_meta).await?;
        writer.write_all(&prefix_pad).await?;
        if let Some(sealed) = &sealed_payload {
            writer.write_all(sealed).await?;
        }
        writer.write_all(&suffix_pad).await?;
        writer.flush().await?;
        Ok(())
    }

    /// Choose a random padding length pair for the next outbound segment.
    pub(crate) fn next_padding(&self) -> Result<(u8, u8)> {
        Ok((self.random_padding_len()?, self.random_padding_len()?))
    }
}

/// Inbound (decrypt) half of a connection: fixed AEAD key + this direction's
/// incrementing nonce, seeded from the first segment's on-wire nonce.
pub(crate) struct Decryptor {
    key: [u8; KEY_LEN],
    recv_nonce: Option<Nonce>,
}

impl Decryptor {
    pub(crate) fn new(key: [u8; KEY_LEN]) -> Self {
        Self { key, recv_nonce: None }
    }

    /// Read one segment, returning its decrypted 32-byte metadata and the
    /// decrypted payload plaintext (if the segment carried one).
    pub(crate) async fn read_segment<R>(&mut self, reader: &mut R) -> Result<([u8; METADATA_LEN], Option<Vec<u8>>)>
    where
        R: AsyncRead + Unpin,
    {
        if self.recv_nonce.is_none() {
            let mut seed = [0u8; NONCE_LEN];
            reader.read_exact(&mut seed).await?;
            self.recv_nonce = Some(Nonce::from_bytes(seed));
        }
        let nonce = self.recv_nonce.as_mut().expect("recv nonce initialised above");

        let mut sealed_meta = [0u8; METADATA_LEN + TAG_LEN];
        reader.read_exact(&mut sealed_meta).await?;
        let meta_vec = cipher::open(&self.key, nonce, &sealed_meta)?;
        nonce.increment();
        let mut meta = [0u8; METADATA_LEN];
        meta.copy_from_slice(&meta_vec);

        let (prefix_len, payload_len, suffix_len) = parse_lengths(&meta)?;

        skip_exact(reader, usize::from(prefix_len)).await?;

        let payload = if payload_len > 0 {
            let mut sealed_payload = vec![0u8; usize::from(payload_len) + TAG_LEN];
            reader.read_exact(&mut sealed_payload).await?;
            let plain = cipher::open(&self.key, nonce, &sealed_payload)?;
            nonce.increment();
            Some(plain)
        } else {
            None
        };

        skip_exact(reader, usize::from(suffix_len)).await?;
        Ok((meta, payload))
    }
}

/// Extract (prefix_len, payload_len, suffix_len) from a decrypted metadata
/// header, honoring the data vs session layouts (`PROTOCOL.md` §2).
fn parse_lengths(meta: &[u8; METADATA_LEN]) -> Result<(u8, u16, u8)> {
    let protocol = ProtocolType::from_u8(meta[0])?;
    if protocol.is_session() {
        let parsed = SessionMeta::unmarshal(meta)?;
        Ok((0, parsed.payload_len, parsed.suffix_len))
    } else {
        let parsed = DataAckMeta::unmarshal(meta)?;
        Ok((parsed.prefix_len, parsed.payload_len, parsed.suffix_len))
    }
}

async fn skip_exact<R: AsyncRead + Unpin>(reader: &mut R, len: usize) -> Result<()> {
    if len == 0 {
        return Ok(());
    }
    let mut remaining = len;
    let mut scratch = [0u8; 256];
    while remaining > 0 {
        let take = remaining.min(scratch.len());
        reader.read_exact(&mut scratch[..take]).await?;
        remaining -= take;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metadata::DataAckMeta;

    #[tokio::test]
    async fn segment_round_trips_through_a_pipe() {
        // Two codecs sharing a key model the two ends of one direction.
        let key = cipher::derive_key(b"pw", b"user", 1_000_000_000, 0);
        let mut writer_codec = Encryptor::new(key, b"user".to_vec());
        let mut reader_codec = Decryptor::new(key);

        let (mut w, mut r) = tokio::io::duplex(64 * 1024);
        let payload = b"hello through the segment layer".to_vec();
        let payload_for_meta = payload.clone();

        let write = async {
            let (p, s) = writer_codec.next_padding().unwrap();
            writer_codec
                .write_segment(
                    &mut w,
                    p,
                    s,
                    |prefix, suffix| {
                        DataAckMeta {
                            protocol: ProtocolType::DataClientToServer,
                            session_id: 42,
                            seq: 1,
                            unack_seq: 0,
                            window_size: 0,
                            fragment: 0,
                            prefix_len: prefix,
                            payload_len: u16::try_from(payload_for_meta.len()).unwrap(),
                            suffix_len: suffix,
                        }
                        .marshal(1_000_000_000)
                    },
                    Some(&payload),
                )
                .await
                .unwrap();
        };
        let read = async {
            let (meta, got) = reader_codec.read_segment(&mut r).await.unwrap();
            assert_eq!(meta[0], ProtocolType::DataClientToServer.as_u8());
            let blob = got.expect("payload present");
            assert_eq!(blob, b"hello through the segment layer");
        };
        tokio::join!(write, read);
    }
}
