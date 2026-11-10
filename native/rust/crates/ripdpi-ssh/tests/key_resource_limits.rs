//! Metadata-only boundary cases derived from OpenSSH, PBES2 DER, and PPK fields.
//! No test below executes a KDF. Ciphertext and salts are disposable test data.
use base64::{Engine, engine::general_purpose::STANDARD};
use ripdpi_ssh::{SshAuth, SshConfig, SshHostKeyPolicy, connect};
use russh::keys::{Error, MAX_SECRET_KEY_BYTES, validate_secret_key_resources};

fn pem(label: &str, bytes: &[u8]) -> String {
    format!("-----BEGIN {label}-----\n{}\n-----END {label}-----\n", STANDARD.encode(bytes))
}

fn bcrypt(rounds: u32) -> String {
    let text = include_str!("fixtures/kdf64.pem");
    let body: String = text.lines().filter(|line| !line.starts_with("-----")).collect();
    let mut bytes = STANDARD.decode(body).expect("OpenSSH vector");
    // openssh-key-v1 NUL, string cipher, string kdf, string(salt string, rounds).
    let mut offset = 15;
    for _ in 0..2 {
        let len = u32::from_be_bytes(bytes[offset..offset + 4].try_into().expect("length")) as usize;
        offset += 4 + len;
    }
    let options_len = u32::from_be_bytes(bytes[offset..offset + 4].try_into().expect("options length")) as usize;
    let position = offset + options_len;
    bytes[position..position + 4].copy_from_slice(&rounds.to_be_bytes());
    pem("OPENSSH PRIVATE KEY", &bytes)
}

fn tlv(tag: u8, content: &[u8]) -> Vec<u8> {
    let mut result = vec![tag];
    if content.len() < 128 {
        result.push(content.len() as u8);
    } else {
        result.extend([0x81, u8::try_from(content.len()).expect("small DER vector")]);
    }
    result.extend(content);
    result
}

fn sequence(parts: &[Vec<u8>]) -> Vec<u8> {
    tlv(0x30, &parts.concat())
}
fn integer(value: u64) -> Vec<u8> {
    let bytes = value.to_be_bytes();
    let start = bytes.iter().position(|byte| *byte != 0).unwrap_or(7);
    let mut bytes = bytes[start..].to_vec();
    if bytes[0] & 0x80 != 0 {
        bytes.insert(0, 0);
    }
    tlv(2, &bytes)
}

fn pbes2(kdf: Vec<u8>, label: &str) -> String {
    let cipher = sequence(&[tlv(6, &[0x60, 0x86, 0x48, 1, 0x65, 3, 4, 1, 0x2a]), tlv(4, &[0; 16])]);
    let algorithm = sequence(&[tlv(6, &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 1, 5, 0x0d]), sequence(&[kdf, cipher])]);
    pem(label, &sequence(&[algorithm, tlv(4, &[0; 16])]))
}

fn pbkdf2(iterations: u64, label: &str) -> String {
    let prf = sequence(&[tlv(6, &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 2, 9]), tlv(5, &[])]);
    let params = sequence(&[tlv(4, &[1; 8]), integer(iterations), prf]);
    pbes2(sequence(&[tlv(6, &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 1, 5, 0x0c]), params]), label)
}

fn scrypt(n: u64, r: u64, p: u64) -> String {
    let params = sequence(&[tlv(4, &[1; 8]), integer(n), integer(r), integer(p)]);
    pbes2(sequence(&[tlv(6, &[0x2b, 6, 1, 4, 1, 0xda, 0x47, 4, 0x0b]), params]), "ENCRYPTED PRIVATE KEY")
}

fn ppk(memory: u64, passes: u64, lanes: u64) -> String {
    format!(
        "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes256-cbc\nKey-Derivation: Argon2id\nArgon2-Memory: {memory}\nArgon2-Passes: {passes}\nArgon2-Parallelism: {lanes}\n"
    )
}

fn rejected(text: &str) {
    assert!(matches!(validate_secret_key_resources(text), Err(Error::KeyResourceLimit)), "must reject cost metadata");
}

#[test]
fn openssh_bcrypt_cost_boundaries() {
    for rounds in [1, 16, 64] {
        validate_secret_key_resources(&bcrypt(rounds)).expect("admitted bcrypt");
    }
    for rounds in [0, 65, u32::MAX] {
        rejected(&bcrypt(rounds));
    }
}

#[test]
fn pkcs8_pbkdf2_limits_include_relabelled_der() {
    for label in ["ENCRYPTED PRIVATE KEY", "PRIVATE KEY", "EC PRIVATE KEY"] {
        for count in [1, 2048, 100_000] {
            validate_secret_key_resources(&pbkdf2(count, label)).expect("admitted PBKDF2");
        }
        for count in [0, 100_001, u64::from(u32::MAX)] {
            rejected(&pbkdf2(count, label));
        }
    }
}

#[test]
fn scrypt_bounds_memory_work_and_invalid_parameters() {
    for (n, r, p) in [(2, 1, 1), (16_384, 8, 1), (16_384, 8, 8)] {
        validate_secret_key_resources(&scrypt(n, r, p)).expect("admitted scrypt");
    }
    for (n, r, p) in
        [(0, 8, 1), (3, 8, 1), (2, 0, 1), (2, 1, 0), (65_536, 8, 1), (16_384, 8, 9), (2, 8, 65_535), (1u64 << 63, 8, 1)]
    {
        let result = validate_secret_key_resources(&scrypt(n, r, p));
        assert!(matches!(result, Err(Error::KeyResourceLimit)), "scrypt {n}/{r}/{p}: {result:?}");
    }
}

#[test]
fn ppk_argon2_bounds_and_duplicate_headers() {
    for (m, t, p) in [(8192, 13, 1), (32_768, 16, 8), (8192, 64, 1)] {
        validate_secret_key_resources(&ppk(m, t, p)).expect("admitted PPK costs");
    }
    for (m, t, p) in [
        (0, 1, 1),
        (1, 0, 1),
        (1, 1, 0),
        (32_769, 1, 1),
        (8192, 65, 1),
        (8192, 1, 9),
        (16_384, 33, 1),
        (u64::MAX, 1, 1),
    ] {
        rejected(&ppk(m, t, p));
    }
    let normal = ppk(8192, 13, 1);
    for duplicate in [
        "Encryption: aes256-cbc",
        "Key-Derivation: Argon2id",
        "Argon2-Memory: 1",
        "Argon2-Passes: 1",
        "Argon2-Parallelism: 1",
    ] {
        rejected(&format!("{normal}{duplicate}\n"));
    }
    rejected(&normal.replace("Argon2-Passes: 13\n", ""));
    let counted = format!("{normal}Comment-Lines: 1\nArgon2-Memory: 4294967295\n");
    validate_secret_key_resources(&counted).expect("counted data is not a header");
}

#[test]
fn size_and_cost_rejection_precede_network_and_runtime() {
    let config = |pem, passphrase| SshConfig {
        host: "127.0.0.1".into(),
        port: 9,
        username: "test".into(),
        auth: SshAuth::PrivateKey { pem, passphrase },
        host_key_policy: SshHostKeyPolicy::Tofu { pinned_fingerprint: None },
    };
    let mut boundary = "PuTTY-User-Key-File-2: ssh-ed25519\nComment: ".to_string();
    boundary.push_str(&"x".repeat(MAX_SECRET_KEY_BYTES - boundary.len()));
    validate_secret_key_resources(&boundary).expect("exact size limit");
    boundary.push('x');
    rejected(&boundary);
    config(bcrypt(1), Some("s".repeat(MAX_SECRET_KEY_BYTES))).validate().expect("exact passphrase limit");
    for config in [
        config(bcrypt(65), None),
        config("x".repeat(MAX_SECRET_KEY_BYTES + 1), None),
        config(bcrypt(1), Some("s".repeat(MAX_SECRET_KEY_BYTES + 1))),
    ] {
        let error = connect(&config).expect_err("reject without a Tokio runtime").to_string();
        assert!(error.contains("resource limit"));
        assert!(!error.contains("ssssssss"), "error must not disclose a passphrase");
    }
}
