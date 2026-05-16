use aes::Aes256;
use cipher::{KeyIvInit, StreamCipher};

use crate::dc::TelegramDc;

type Aes256Ctr = ctr::Ctr128BE<Aes256>;

const ENCRYPTED_PREFIX_TLS: u32 = 0x0201_0316;
const ENCRYPTED_PREFIX_GET: u32 = 0x2054_4547;
const ENCRYPTED_PREFIX_HEAD: u32 = 0x4441_4548;
const ENCRYPTED_PREFIX_OPTIONS: u32 = 0x4954_504f;
const ENCRYPTED_PREFIX_POST: u32 = 0x5453_4f50;
const ENCRYPTED_PREFIX_PADDED_INTERMEDIATE: u32 = 0xdddd_dddd;
const ENCRYPTED_PREFIX_INTERMEDIATE: u32 = 0xeeee_eeee;
const ENCRYPTED_PREFIX_ABRIDGED: u32 = 0xefef_efef;

/// MTProto obfuscated2 transport family identifier.
///
/// Telegram's published transport selectors carry one of three repeated-
/// byte tags in bytes `[56..60]` of the decrypted init buffer. Wrapping
/// them in a typed enum lets diagnostics distinguish "wrong family" from
/// "not MTProto at all" and gives a hook point for future per-family
/// stats. Mirrors the `ProtocolVersion` pattern in `ripdpi-vless` and
/// `ripdpi-tuic` (see
/// `docs/tasks/issues/introduce-protocol-version-enum-and-version-probe-diagnostic.md`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MtprotoTransportFamily {
    /// `0xdddddddd` — padded intermediate.
    PaddedIntermediate,
    /// `0xeeeeeeee` — intermediate.
    Intermediate,
    /// `0xefefefef` — abridged.
    Abridged,
}

impl MtprotoTransportFamily {
    /// Every transport family this client recognises.
    pub const SUPPORTED: &'static [Self] = &[Self::PaddedIntermediate, Self::Intermediate, Self::Abridged];

    /// 4-byte tag pattern as it appears in `decrypted[56..60]`.
    pub const fn tag_bytes(self) -> [u8; 4] {
        match self {
            Self::PaddedIntermediate => [0xdd; 4],
            Self::Intermediate => [0xee; 4],
            Self::Abridged => [0xef; 4],
        }
    }

    /// Decode a 4-byte tag from the decrypted init into a known
    /// transport family, or `None` for unrecognised.
    pub fn from_tag_bytes(tag: &[u8; 4]) -> Option<Self> {
        Self::SUPPORTED.iter().find(|&&family| family.tag_bytes() == *tag).copied()
    }
}

const ALLOWED_PROTOCOL_TAGS: [[u8; 4]; 3] = [
    MtprotoTransportFamily::PaddedIntermediate.tag_bytes(),
    MtprotoTransportFamily::Intermediate.tag_bytes(),
    MtprotoTransportFamily::Abridged.tag_bytes(),
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MtprotoSeedClassification {
    ValidatedMtproto { dc: TelegramDc },
    NotMtproto,
    UnmappableDc { raw_dc: i32, dc: Option<TelegramDc> },
}

pub fn decrypt_init_packet(init: &[u8; 64]) -> [u8; 64] {
    let key = &init[8..40];
    let iv = &init[40..56];

    let mut decrypted = *init;
    let mut stream_cipher = Aes256Ctr::new_from_slices(key, iv).expect("AES-256-CTR key/iv length mismatch");
    stream_cipher.apply_keystream(&mut decrypted);
    decrypted
}

pub fn extract_dc_from_init(init: &[u8; 64]) -> Option<TelegramDc> {
    let decrypted = decrypt_init_packet(init);
    let raw_dc = i32::from_le_bytes([decrypted[60], decrypted[61], decrypted[62], decrypted[63]]);
    TelegramDc::from_raw(raw_dc)
}

pub fn classify_mtproto_seed(seed_request: &[u8]) -> MtprotoSeedClassification {
    let Some(init) = seed_request.get(..64) else {
        return MtprotoSeedClassification::NotMtproto;
    };

    let mut init_packet = [0u8; 64];
    init_packet.copy_from_slice(init);
    classify_mtproto_init(&init_packet)
}

fn classify_mtproto_init(init: &[u8; 64]) -> MtprotoSeedClassification {
    if !has_valid_encrypted_prefix(init) {
        return MtprotoSeedClassification::NotMtproto;
    }

    let decrypted = decrypt_init_packet(init);
    if !has_allowed_protocol_tag(&decrypted) {
        return MtprotoSeedClassification::NotMtproto;
    }

    let raw_dc = i32::from_le_bytes([decrypted[60], decrypted[61], decrypted[62], decrypted[63]]);
    let dc = TelegramDc::from_raw(raw_dc);
    match dc {
        Some(dc) if dc.is_tunnelable() => MtprotoSeedClassification::ValidatedMtproto { dc },
        _ => MtprotoSeedClassification::UnmappableDc { raw_dc, dc },
    }
}

fn has_valid_encrypted_prefix(init: &[u8; 64]) -> bool {
    if init[0] == 0xef {
        return false;
    }

    let prefix = u32::from_le_bytes([init[0], init[1], init[2], init[3]]);
    !matches!(
        prefix,
        ENCRYPTED_PREFIX_HEAD
            | ENCRYPTED_PREFIX_POST
            | ENCRYPTED_PREFIX_GET
            | ENCRYPTED_PREFIX_OPTIONS
            | ENCRYPTED_PREFIX_TLS
            | ENCRYPTED_PREFIX_PADDED_INTERMEDIATE
            | ENCRYPTED_PREFIX_INTERMEDIATE
            | ENCRYPTED_PREFIX_ABRIDGED
    )
}

fn has_allowed_protocol_tag(decrypted: &[u8; 64]) -> bool {
    ALLOWED_PROTOCOL_TAGS.iter().any(|tag| decrypted[56..60] == tag[..])
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::dc::TelegramDc;

    fn build_test_init_packet(raw_dc: i32) -> [u8; 64] {
        build_test_init_packet_with_tag(raw_dc, [0xee; 4])
    }

    fn build_test_init_packet_with_tag(raw_dc: i32, tag: [u8; 4]) -> [u8; 64] {
        let mut plaintext = [0u8; 64];
        plaintext[56..60].copy_from_slice(&tag);
        plaintext[60..64].copy_from_slice(&raw_dc.to_le_bytes());

        let key: [u8; 32] = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
        ];
        let iv: [u8; 16] =
            [0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0];

        let mut ciphertext = plaintext;
        let mut cipher = Aes256Ctr::new((&key).into(), (&iv).into());
        cipher.apply_keystream(&mut ciphertext);
        ciphertext[8..40].copy_from_slice(&key);
        ciphertext[40..56].copy_from_slice(&iv);
        ciphertext
    }

    #[test]
    fn extract_dc_from_init_normalizes_supported_encodings() {
        assert_eq!(extract_dc_from_init(&build_test_init_packet(3)), Some(TelegramDc::production(3)));
        assert_eq!(
            extract_dc_from_init(&build_test_init_packet(10_002)),
            Some(TelegramDc::from_raw(10_002).expect("test dc")),
        );
        assert_eq!(
            extract_dc_from_init(&build_test_init_packet(-4)),
            Some(TelegramDc::from_raw(-4).expect("media dc")),
        );
    }

    #[test]
    fn extract_dc_from_init_returns_none_for_invalid_dc() {
        assert_eq!(extract_dc_from_init(&build_test_init_packet(0)), None);
        assert_eq!(extract_dc_from_init(&build_test_init_packet(6)), None);
    }

    #[test]
    fn classify_mtproto_seed_accepts_production_and_test_dcs() {
        assert_eq!(
            classify_mtproto_seed(&build_test_init_packet(2)),
            MtprotoSeedClassification::ValidatedMtproto { dc: TelegramDc::production(2) },
        );
        assert_eq!(
            classify_mtproto_seed(&build_test_init_packet(10_003)),
            MtprotoSeedClassification::ValidatedMtproto { dc: TelegramDc::from_raw(10_003).expect("test dc") },
        );
        assert_eq!(
            classify_mtproto_seed(&build_test_init_packet_with_tag(4, [0xef; 4])),
            MtprotoSeedClassification::ValidatedMtproto { dc: TelegramDc::production(4) },
        );
    }

    #[test]
    fn classify_mtproto_seed_flags_media_and_unknown_dcs_as_unmappable() {
        assert_eq!(
            classify_mtproto_seed(&build_test_init_packet(-5)),
            MtprotoSeedClassification::UnmappableDc {
                raw_dc: -5,
                dc: Some(TelegramDc::from_raw(-5).expect("media dc")),
            },
        );
        assert_eq!(
            classify_mtproto_seed(&build_test_init_packet(42)),
            MtprotoSeedClassification::UnmappableDc { raw_dc: 42, dc: None },
        );
    }

    #[test]
    fn classify_mtproto_seed_rejects_short_requests() {
        assert_eq!(classify_mtproto_seed(&[0u8; 12]), MtprotoSeedClassification::NotMtproto);
    }

    #[test]
    fn classify_mtproto_seed_rejects_blocked_encrypted_prefixes() {
        let mut init = build_test_init_packet(1);
        init[..4].copy_from_slice(b"POST");
        assert_eq!(classify_mtproto_seed(&init), MtprotoSeedClassification::NotMtproto);

        let mut init = build_test_init_packet(1);
        init[0] = 0xef;
        assert_eq!(classify_mtproto_seed(&init), MtprotoSeedClassification::NotMtproto);
    }

    #[test]
    fn classify_mtproto_seed_rejects_invalid_protocol_tag() {
        let init = build_test_init_packet_with_tag(1, [0xaa; 4]);

        assert_eq!(classify_mtproto_seed(&init), MtprotoSeedClassification::NotMtproto);
    }

    #[test]
    fn decrypt_init_packet_restores_tag_and_raw_dc() {
        let init = build_test_init_packet_with_tag(10_004, [0xdd; 4]);
        let decrypted = decrypt_init_packet(&init);

        assert_eq!(&decrypted[56..60], &[0xdd; 4]);
        assert_eq!(i32::from_le_bytes(decrypted[60..64].try_into().expect("raw dc")), 10_004);
    }

    #[test]
    fn transport_family_tag_bytes_match_known_canonical_tags() {
        assert_eq!(MtprotoTransportFamily::PaddedIntermediate.tag_bytes(), [0xdd; 4]);
        assert_eq!(MtprotoTransportFamily::Intermediate.tag_bytes(), [0xee; 4]);
        assert_eq!(MtprotoTransportFamily::Abridged.tag_bytes(), [0xef; 4]);
    }

    #[test]
    fn transport_family_from_tag_bytes_roundtrips_supported_variants() {
        for &family in MtprotoTransportFamily::SUPPORTED {
            assert_eq!(MtprotoTransportFamily::from_tag_bytes(&family.tag_bytes()), Some(family));
        }
    }

    #[test]
    fn transport_family_from_tag_bytes_rejects_unknown() {
        // Any tag that isn't a repeated dd/ee/ef must not classify.
        for tag in [[0xaa; 4], [0x00; 4], [0xff; 4], [0xde, 0xad, 0xbe, 0xef]] {
            assert_eq!(MtprotoTransportFamily::from_tag_bytes(&tag), None, "unexpected accept for {tag:?}");
        }
    }

    #[test]
    fn allowed_protocol_tags_const_matches_enum_supported() {
        // The legacy ALLOWED_PROTOCOL_TAGS const is now derived from
        // MtprotoTransportFamily::SUPPORTED; the array order must match
        // the SUPPORTED slice order so anywhere that indexes into the
        // const stays aligned with the enum.
        let derived: Vec<[u8; 4]> = MtprotoTransportFamily::SUPPORTED.iter().map(|f| f.tag_bytes()).collect();
        assert_eq!(ALLOWED_PROTOCOL_TAGS.to_vec(), derived);
    }
}
