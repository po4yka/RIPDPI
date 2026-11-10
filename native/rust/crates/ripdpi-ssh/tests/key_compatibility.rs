//! Public/disposable fixtures. Expected SSH public-key digests were computed
//! independently from OpenSSL DER or upstream OpenSSH public-key blobs.
use russh::keys::{HashAlg, decode_secret_key, validate_secret_key_resources};

#[test]
fn permitted_containers_keep_their_public_key() {
    // Public fixtures: armor is assembled around the stored test payloads.
    let plain =
        format!("-----BEGIN PRIVATE KEY-----\n{}-----END PRIVATE KEY-----\n", include_str!("fixtures/plain.b64")); // Public fixture.
    let legacy = format!(
        "-----BEGIN RSA PRIVATE KEY-----\n{}-----END RSA PRIVATE KEY-----\n", // Public fixture armor.
        include_str!("fixtures/legacy.b64")
    ); // Public fixture.
    let cases = [
        (plain.as_str(), None, "SHA256:cvwBMWWr1xED+kBZ2o4KlYphV+RB65+VOFqw871HIBY"),
        (include_str!("fixtures/pbkdf2.pem"), Some("local-test"), "SHA256:cvwBMWWr1xED+kBZ2o4KlYphV+RB65+VOFqw871HIBY"),
        (include_str!("fixtures/scrypt.pem"), Some("local-test"), "SHA256:cvwBMWWr1xED+kBZ2o4KlYphV+RB65+VOFqw871HIBY"),
        (legacy.as_str(), Some("local-test"), "SHA256:7P2LYWZzpVtp1FqLG0ho24iovAtE/Gog39zsrMZkEOs"),
        (include_str!("fixtures/argon2.ppk"), Some("123"), "SHA256:UCUiLr7Pjs9wFFJMDByLgc3NrtdU344OgUM45wZPcIQ"),
        (include_str!("fixtures/ppk2.ppk"), Some("123"), "SHA256:Fmxts/GcV77PakFnf1Ueki5mpU4ZjUQWGRjZGAo3n/I"),
    ];
    for (text, password, expected) in cases {
        validate_secret_key_resources(text).expect("normal container resource budget");
        let key = decode_secret_key(text, password).expect("permitted container remains decryptable");
        assert_eq!(key.public_key().fingerprint(HashAlg::Sha256).to_string(), expected);
    }
}
