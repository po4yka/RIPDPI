//! Split the client's MTProto byte stream into one WebSocket frame per transport packet.
//!
//! The Telegram gateway handles one transport packet per WS frame: when a frame holds two
//! packets, the second is answered only after a later frame arrives. The tg-ws-proxy
//! reference splits for this reason (its `MsgSplitter`). The bytes stay encrypted; the
//! obfuscated2 keystream only shows where each packet ends.

use aes::Aes256;
use cipher::{KeyIvInit, StreamCipher};

use crate::mtproto::MtprotoTransportFamily;

type Aes256Ctr = ctr::Ctr128BE<Aes256>;

const INIT_LEN: usize = 64;
/// A declared length above this is not MTProto (the largest packet is about 1 MiB), so
/// splitting stops and the rest of the stream passes through unchanged.
const MAX_PACKET_LEN: usize = 2 * 1024 * 1024;

pub(super) struct PacketSplitter {
    state: Option<Splitting>,
}

struct Splitting {
    cipher: Aes256Ctr,
    family: MtprotoTransportFamily,
    /// Client bytes of the packet not yet complete, as sent, and decrypted.
    encrypted: Vec<u8>,
    plain: Vec<u8>,
}

impl PacketSplitter {
    /// Start after the 64-byte obfuscated2 `init`. A seed that is not one passes bytes through unsplit.
    pub(super) fn new(init: &[u8]) -> Self {
        let state = init.get(..INIT_LEN).and_then(|init| {
            let mut cipher = Aes256Ctr::new_from_slices(&init[8..40], &init[40..56]).ok()?;
            let mut plain = [0u8; INIT_LEN];
            plain.copy_from_slice(init);
            cipher.apply_keystream(&mut plain);
            let family = MtprotoTransportFamily::from_tag_bytes(&[plain[56], plain[57], plain[58], plain[59]])?;
            Some(Splitting { cipher, family, encrypted: Vec::new(), plain: Vec::new() })
        });
        Self { state }
    }

    /// Feed client bytes; return the frames to send now, in order. A partial packet is kept.
    pub(super) fn push(&mut self, bytes: &[u8]) -> Vec<Vec<u8>> {
        let Some(state) = self.state.as_mut() else {
            return vec![bytes.to_vec()];
        };
        let start = state.plain.len();
        state.encrypted.extend_from_slice(bytes);
        state.plain.extend_from_slice(bytes);
        state.cipher.apply_keystream(&mut state.plain[start..]);

        let mut frames = Vec::new();
        let mut consumed = 0;
        let mut oversized = false;
        while let Some(len) = packet_len(state.family, &state.plain[consumed..]) {
            if len > MAX_PACKET_LEN {
                oversized = true;
                break;
            }
            if state.plain.len() - consumed < len {
                break;
            }
            frames.push(state.encrypted[consumed..consumed + len].to_vec());
            consumed += len;
        }
        if oversized {
            frames.push(state.encrypted[consumed..].to_vec());
            self.state = None;
            return frames;
        }
        state.encrypted.drain(..consumed);
        state.plain.drain(..consumed);
        frames
    }
}

/// Header plus payload length of the packet at the start of `plain`, once its header is complete.
fn packet_len(family: MtprotoTransportFamily, plain: &[u8]) -> Option<usize> {
    match family {
        MtprotoTransportFamily::Abridged => {
            // The top bit of the first byte asks for a quick ack.
            let words = *plain.first()? & 0x7f;
            if words == 0x7f {
                let len = plain.get(1..4)?;
                Some(4 + u32::from_le_bytes([len[0], len[1], len[2], 0]) as usize * 4)
            } else {
                Some(1 + usize::from(words) * 4)
            }
        }
        MtprotoTransportFamily::Intermediate | MtprotoTransportFamily::PaddedIntermediate => {
            let len = plain.get(..4)?;
            // The top bit asks for a quick ack.
            Some(4 + (u32::from_le_bytes([len[0], len[1], len[2], len[3]]) & 0x7fff_ffff) as usize)
        }
    }
}

/// An obfuscated2 init for `family` and the client stream of `packets` (plaintext) after it.
#[cfg(test)]
pub(super) fn obfuscated_client_stream(family: MtprotoTransportFamily, packets: &[&[u8]]) -> (Vec<u8>, Vec<u8>) {
    let mut init: Vec<u8> = (0..INIT_LEN as u8).map(|value| value.wrapping_mul(37).wrapping_add(11)).collect();
    let mut cipher = Aes256Ctr::new_from_slices(&init[8..40], &init[40..56]).expect("key and iv");
    let mut keystream = [0u8; INIT_LEN];
    cipher.apply_keystream(&mut keystream);
    for (index, tag) in family.tag_bytes().into_iter().enumerate() {
        init[56 + index] = tag ^ keystream[56 + index];
    }
    let mut stream = packets.concat();
    cipher.apply_keystream(&mut stream);
    (init, stream)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn abridged(words: u8) -> Vec<u8> {
        let mut packet = vec![words];
        packet.extend(std::iter::repeat_n(0x42, usize::from(words) * 4));
        packet
    }

    #[test]
    fn two_coalesced_abridged_packets_become_two_frames() {
        let (first, second) = (abridged(2), abridged(3));
        let (init, stream) = obfuscated_client_stream(MtprotoTransportFamily::Abridged, &[&first, &second]);

        let frames = PacketSplitter::new(&init).push(&stream);

        assert_eq!(frames, vec![stream[..9].to_vec(), stream[9..].to_vec()]);
    }

    #[test]
    fn a_packet_cut_across_reads_waits_for_its_tail() {
        let packet = abridged(4);
        let (init, stream) = obfuscated_client_stream(MtprotoTransportFamily::Abridged, &[&packet]);
        let mut splitter = PacketSplitter::new(&init);

        assert!(splitter.push(&stream[..5]).is_empty());
        assert_eq!(splitter.push(&stream[5..]), vec![stream.clone()]);
    }

    #[test]
    fn intermediate_and_long_abridged_headers_give_the_packet_length() {
        let mut intermediate = 6u32.to_le_bytes().to_vec();
        intermediate.extend([7; 6]);
        let (init, stream) =
            obfuscated_client_stream(MtprotoTransportFamily::Intermediate, &[&intermediate, &intermediate]);
        assert_eq!(PacketSplitter::new(&init).push(&stream), vec![stream[..10].to_vec(), stream[10..].to_vec()]);

        // 0x7f, then a 3-byte length in words; the 0x80 quick-ack bit does not count.
        let mut long = vec![0xff, 0x80, 0x00, 0x00];
        long.extend(std::iter::repeat_n(1, 0x80 * 4));
        let (init, stream) = obfuscated_client_stream(MtprotoTransportFamily::Abridged, &[&long, &abridged(1)]);
        assert_eq!(PacketSplitter::new(&init).push(&stream), vec![stream[..516].to_vec(), stream[516..].to_vec()]);
    }

    #[test]
    fn a_seed_that_is_not_obfuscated2_passes_through_unsplit() {
        let mut splitter = PacketSplitter::new(&[0xab; 64]);

        assert_eq!(splitter.push(b"uplink"), vec![b"uplink".to_vec()]);
    }

    #[test]
    fn an_impossible_length_stops_splitting_and_forwards_the_rest() {
        let mut huge = 0x7fff_fff0u32.to_le_bytes().to_vec();
        huge.extend([0; 4]);
        let (init, stream) = obfuscated_client_stream(MtprotoTransportFamily::Intermediate, &[&huge]);
        let mut splitter = PacketSplitter::new(&init);

        assert_eq!(splitter.push(&stream), vec![stream.clone()]);
        assert_eq!(splitter.push(b"later"), vec![b"later".to_vec()]);
    }
}
