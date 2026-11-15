use super::*;
use ripdpi_config::FakePacketSource;
use ripdpi_packets::{change_tls_sni_seeded_like_c, tls_fake_profile_bytes};

const REAL_SNI: &str = "real-secret.example.com";
const DECOY_SNI: &str = "decoy.example.net";

fn real_client_hello() -> Vec<u8> {
    let base = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
    let out = change_tls_sni_seeded_like_c(base, REAL_SNI.as_bytes(), base.len() + 64, 1);
    assert_eq!(out.rc, 0);
    out.bytes
}

/// Independent SNI reader: record, handshake, random, session id, ciphers,
/// compression, then the extension list.
fn sni_of(bytes: &[u8]) -> Option<Vec<u8>> {
    let u16_at = |at: usize| Some(usize::from(u16::from_be_bytes([*bytes.get(at)?, *bytes.get(at + 1)?])));
    let mut at = 5 + 4 + 2 + 32;
    at += 1 + usize::from(*bytes.get(at)?);
    at += 2 + u16_at(at)?;
    at += 1 + usize::from(*bytes.get(at)?);
    let end = (at + 2 + u16_at(at)?).min(bytes.len());
    at += 2;
    while at + 4 <= end {
        let (kind, len) = (u16_at(at)?, u16_at(at + 2)?);
        if kind == 0 {
            let name_len = u16_at(at + 4 + 3)?;
            return bytes.get(at + 4 + 5..at + 4 + 5 + name_len).map(<[u8]>::to_vec);
        }
        at += 4 + len;
    }
    None
}

fn group(source: FakePacketSource, orig: bool, fixed: bool) -> DesyncGroup {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_tls_source = source;
    group.actions.tls_fake_profile = TlsFakeProfile::GoogleChrome;
    if orig {
        group.actions.fake_mod |= FM_ORIG;
    }
    if fixed {
        group.actions.fake_sni_list = vec![DECOY_SNI.to_owned()];
    } else {
        group.actions.fake_mod |= FM_RNDSNI;
    }
    group
}

#[test]
fn fake_sni_change_fails_closed_for_unusable_client_hello_inputs() {
    let real = real_client_hello();
    assert_eq!(sni_of(&real).as_deref(), Some(REAL_SNI.as_bytes()));
    let mut trailing = real.clone();
    trailing.extend_from_slice(&[0x17, 3, 3, 0, 5, 1, 2, 3, 4, 5]);
    let truncated = real[..real.len() - 40].to_vec();

    for input in [&trailing, &truncated] {
        for (source, orig) in [(FakePacketSource::CapturedClientHello, false), (FakePacketSource::Profile, true)] {
            for fixed in [true, false] {
                let fake = build_fake_packet(&group(source, orig, fixed), input, 7).expect("fake");
                let sni = sni_of(&fake.bytes).expect("fake keeps a parsable SNI");
                assert_ne!(sni, REAL_SNI.as_bytes(), "real SNI leaked (orig={orig}, fixed={fixed})");
                if fixed {
                    assert_eq!(sni, DECOY_SNI.as_bytes(), "orig={orig}");
                }
            }
        }
    }
}

#[test]
fn fake_sni_change_still_applies_to_valid_captured_client_hello() {
    let real = real_client_hello();
    let fake = build_fake_packet(&group(FakePacketSource::CapturedClientHello, false, true), &real, 7).expect("fake");
    assert_eq!(sni_of(&fake.bytes).as_deref(), Some(DECOY_SNI.as_bytes()));
}

#[test]
fn fixed_fake_sni_applies_to_default_compat_profile() {
    let real = real_client_hello();
    let mut group = group(FakePacketSource::Profile, false, true);
    group.actions.tls_fake_profile = TlsFakeProfile::CompatDefault;
    let fake = build_fake_packet(&group, &real, 7).expect("fake");
    assert_eq!(sni_of(&fake.bytes).as_deref(), Some(DECOY_SNI.as_bytes()));
}

#[test]
fn hostfake_longer_template_keeps_a_valid_host_shape() {
    for (host, template) in [("a.ru", "googlevideo.com"), ("x.io", "a-b.example")] {
        let out = build_hostfake_bytes(host.as_bytes(), Some(template), 5, false);
        assert_eq!(out.len(), host.len());
        assert!(out[0].is_ascii_lowercase(), "{out:?}");
        assert_ne!(out.last(), Some(&b'.'));
        assert!(out.split(|byte| *byte == b'.').all(|label| !label.is_empty()), "{out:?}");
        // The old code copied the template tail ("x.io" + "a-b.example" -> "mple").
        assert_ne!(out.as_slice(), &template.as_bytes()[template.len() - host.len()..], "{out:?}");
    }
}
