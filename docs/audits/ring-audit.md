# ring Crypto Consolidation Audit

Audited: 2026-03-24 against ring 0.17.14, workspace crypto crates sha2 0.10,
hkdf 0.12, aes 0.8, aes-gcm 0.10, crypto_box 0.9, ed25519-dalek 2, rand 0.8.

## Summary

The workspace uses 8+ separate crypto crates for hashing, AEAD, key derivation,
signatures, and randomness. `ring` is already in the dependency tree as the
backend for rustls 0.23 and quinn 0.11. Consolidating on ring eliminates 5
direct crypto dependencies and their transitive trees, reduces binary size and
audit surface, and aligns with the rustls migration path.

Three operations cannot move to ring: AES-128 ECB (QUIC header protection),
AES-256-CTR (Telegram DC extraction), and NaCl crypto_box (DNSCrypt XChaCha20).

## Current Crypto Dependency Map

### Direct Dependencies by Crate

| Module | Crypto Operation | Library | File |
|--------|-----------------|---------|------|
| ripdpi-runtime | SHA-256 config fingerprint | `sha2` 0.10 | `runtime_policy/autolearn.rs:199` |
| ripdpi-packets | HKDF-SHA256 key derivation | `hkdf` 0.12 + `sha2` 0.10 | `quic.rs:59-76` |
| ripdpi-packets | AES-128-GCM encrypt/decrypt | `aes-gcm` 0.10 | `quic.rs:163-299` |
| ripdpi-packets | AES-128 ECB block (HP sample) | `aes` 0.8 | `quic.rs` (BlockEncrypt) |
| ripdpi-dns-resolver | Ed25519 cert verification | `ed25519-dalek` 2 | `dnscrypt.rs:25-56` |
| ripdpi-dns-resolver | XChaCha20-Poly1305 (NaCl box) | `crypto_box` 0.9 | `dnscrypt.rs:68-88` |
| ripdpi-dns-resolver | X25519 key agreement | `crypto_box` 0.9 | `resolver.rs:265` |
| ripdpi-dns-resolver | CSPRNG (OsRng) | `rand` 0.8 | `resolver.rs:267-268` |
| ripdpi-ws-tunnel | AES-256-CTR stream cipher | `aes` 0.8 + `ctr` 0.9 | `dc.rs:3-20` |
| ripdpi-runtime | TCP MD5SIG socket option | `libc` (kernel) | `platform/linux.rs:124` |
| ripdpi-runtime | FNV-1a jitter hash | inline (non-crypto) | `retry_stealth.rs:258` |

### ring Transitive Presence

ring 0.17.14 is already pulled in via:
- `rustls` 0.23.37 (features: `ring`, `std`, `tls12`)
- `quinn` 0.11 (features: `rustls`, `ring`, `runtime-tokio`)
- `hickory-resolver` 0.25 (features: `tls-ring`, `https-ring`)

No additional binary cost for adding ring as a direct dependency.

## ring Coverage Analysis

### ring 0.17 API surface

| Module | Primitives |
|--------|-----------|
| `ring::digest` | SHA-256, SHA-384, SHA-512, SHA-1 (legacy) |
| `ring::hmac` | HMAC with any supported digest |
| `ring::hkdf` | Full HKDF extract + expand (TLS 1.3 compatible) |
| `ring::aead` | AES-128-GCM, AES-256-GCM, CHACHA20_POLY1305 (12-byte nonce) |
| `ring::signature` | Ed25519, ECDSA (P-256, P-384), RSA (PKCS1, PSS) |
| `ring::agreement` | X25519, ECDH (P-256, P-384) |
| `ring::rand` | SystemRandom (CSPRNG from OS) |

### Consolidation Matrix

**Replaceable:**

| Operation | Current | ring Replacement | Effort |
|-----------|---------|-----------------|--------|
| SHA-256 fingerprint | `sha2::Sha256` | `ring::digest::digest(&SHA256, input)` | Low (5 lines) |
| HKDF-SHA256 | `hkdf::Hkdf<Sha256>` | `ring::hkdf::{Salt, Prk, HKDF_SHA256}` | Medium (API restructure) |
| AES-128-GCM | `aes_gcm::Aes128Gcm` | `ring::aead::{LessSafeKey, AES_128_GCM}` | Medium (different API shape) |
| Ed25519 verify | `ed25519_dalek::VerifyingKey` | `ring::signature::UnparsedPublicKey` + `ED25519` | Low (simpler API) |
| CSPRNG | `rand::rngs::OsRng` | `ring::rand::SystemRandom` | Low (2 call sites) |

**NOT replaceable:**

| Operation | Current | Why ring cannot replace |
|-----------|---------|----------------------|
| AES-128 ECB block | `aes::Aes128` + `BlockEncrypt` | ring exposes no raw block cipher, only AEAD |
| XChaCha20-Poly1305 | `crypto_box::ChaChaBox` | ring's CHACHA20_POLY1305 uses 12-byte nonces; NaCl box requires 24-byte XChaCha20 + X25519 combined construction |
| AES-256-CTR stream | `aes` + `ctr` + `cipher` | ring has no CTR mode, only AEAD |
| TCP MD5SIG | `libc::setsockopt` | Kernel socket option, not user-space crypto |

### Dependency Elimination

**Can remove (5 crates):**
- `sha2` -- replaced by `ring::digest`
- `hkdf` -- replaced by `ring::hkdf`
- `aes-gcm` -- replaced by `ring::aead`
- `ed25519-dalek` -- replaced by `ring::signature`
- `rand` -- replaced by `ring::rand` (if no non-crypto uses remain)

**Transitive crates that drop out:**
`digest`, `crypto-common`, `block-buffer`, `ghash`, `polyval`, `curve25519-dalek`,
`ed25519` (trait crate), `rand_chacha`, `rand_core` (partial -- `crypto_box` may
still pull `rand_core`), `cpufeatures` (partial -- `aes` still needs it).

**Must stay (3 crates):**
- `aes` 0.8 -- QUIC header protection (1 site) + AES-256-CTR in dc.rs
- `ctr` 0.9, `cipher` 0.4 -- AES-256-CTR for Telegram DC extraction
- `crypto_box` 0.9 -- NaCl box for DNSCrypt (XChaCha20 + X25519)

## Implementation Plan

### Phase 1: Consolidate hashing on ring::digest

Scope: `ripdpi-runtime` only. Smallest change, validates the pattern.

**autolearn.rs** -- replace `sha2::{Digest, Sha256}` with `ring::digest`:
```rust
// Before:
use sha2::{Digest, Sha256};
let mut hasher = Sha256::new();
hasher.update(input);
format!("{:x}", hasher.finalize())

// After:
use ring::digest;
let d = digest::digest(&digest::SHA256, &input);
hex::encode(d.as_ref())
```

Remove `sha2` from `ripdpi-runtime/Cargo.toml`. Add `ring` (workspace dep).

### Phase 2: Consolidate QUIC crypto on ring

Scope: `ripdpi-packets/src/quic.rs`. Medium effort -- different API shape.

**HKDF** -- replace `hkdf::Hkdf<Sha256>` with `ring::hkdf`:
```rust
// Extract + expand via ring::hkdf:
use ring::hkdf::{Salt, HKDF_SHA256, KeyType};

struct HkdfLen(usize);
impl KeyType for HkdfLen {
    fn len(&self) -> usize { self.0 }
}

let salt = Salt::new(HKDF_SHA256, salt_bytes);
let prk = salt.extract(ikm);
let okm = prk.expand(&[&info], HkdfLen(out_len))?.fill(out)?;
```

**AES-128-GCM** -- replace `aes_gcm::Aes128Gcm` with `ring::aead`:
```rust
use ring::aead::{UnboundKey, LessSafeKey, AES_128_GCM, Nonce, Aad};

let key = LessSafeKey::new(UnboundKey::new(&AES_128_GCM, &key_bytes)?);
// Encrypt:
let tag = key.seal_in_place_separate_tag(nonce, aad, &mut buf)?;
// Decrypt:
key.open_in_place(nonce, aad, &mut buf)?;
```

**Keep** `aes::Aes128` + `BlockEncrypt` for QUIC header protection sample
encryption (single AES-ECB block -- ring cannot replace this).

Remove `sha2`, `hkdf`, `aes-gcm` from `ripdpi-packets/Cargo.toml`.

### Phase 3: Consolidate DNS resolver crypto on ring

Scope: `ripdpi-dns-resolver`. Two operations.

**Ed25519 verification** -- replace `ed25519-dalek` with `ring::signature`:
```rust
use ring::signature::{UnparsedPublicKey, ED25519};

// Verification becomes a one-liner:
UnparsedPublicKey::new(&ED25519, &pk_bytes)
    .verify(signed_data, &signature_bytes)
    .map_err(|_| EncryptedDnsError::DnsCryptVerification(...))?;
```

`dnscrypt_verifying_key()` returns `[u8; 32]` instead of `VerifyingKey`.

**CSPRNG** -- replace `rand::rngs::OsRng` with `ring::rand::SystemRandom`:
```rust
use ring::rand::{SystemRandom, SecureRandom};

let rng = SystemRandom::new();
rng.fill(&mut client_secret)?;
rng.fill(&mut full_nonce[..DNSCRYPT_QUERY_NONCE_HALF])?;
```

Remove `rand`, `ed25519-dalek` from `ripdpi-dns-resolver/Cargo.toml`.

`crypto_box` stays -- NaCl box (XChaCha20 24-byte nonces) has no ring equivalent.

### Phase 4: Align with rustls migration (shared backend)

After Phases 1-3, ring is the shared crypto backend for:
- TLS (rustls) -- handshakes, certificate verification
- QUIC (quinn) -- protocol crypto
- QUIC packet inspection (ripdpi-packets) -- Initial packet decrypt
- DNS security (ripdpi-dns-resolver) -- Ed25519, CSPRNG
- Config integrity (ripdpi-runtime) -- SHA-256 fingerprinting

Workspace cleanup:
- Remove stale workspace dep entries: `sha2`, `hkdf`, `aes-gcm`, `ed25519-dalek`
- Audit `rand` usage across all crates; remove workspace entry if unused
- Add `ring` as explicit workspace dependency (currently implicit via features)
- Run `cargo tree -d` to verify deduplication

## Binary Size Impact

ring is already linked (via rustls). Removing 5 RustCrypto crates eliminates:
- Redundant SHA-256 implementation (sha2 uses software fallback + cpufeatures;
  ring uses asm on supported targets)
- Separate GCM implementation (ghash + polyval + aes-gcm vs ring's asm AES-GCM)
- curve25519-dalek (large -- field arithmetic, compressed Edwards points)

Expected net reduction: moderate. The `aes` crate stays (HP + CTR), so cpufeatures
persists, but the overall crypto code surface shrinks.

Measure: `cargo build --release -p ripdpi-cli && ls -la target/release/ripdpi`
before and after.

## no_std Compatibility

ring works without std (`default-features = false`). This is an advantage if
ripdpi-tunnel-core or packet processing ever targets embedded/no_std environments.
Current RustCrypto crates (sha2, aes, etc.) also support no_std, so this is parity
rather than improvement.

## What ring Does NOT Cover

| Gap | Current Solution | Notes |
|-----|-----------------|-------|
| MD5 hashing | Not used (TCP_MD5SIG is kernel-only) | No user-space MD5 needed |
| AES-CTR mode | `aes` + `ctr` crates | Telegram obfuscated2 init (dc.rs) |
| AES raw block | `aes` crate | QUIC header protection sample |
| NaCl crypto_box | `crypto_box` crate | DNSCrypt XChaCha20-Poly1305 + X25519 |
| Deterministic RNG | OracleRng (custom) | Desync fake packet seeding (non-crypto) |

These gaps are acceptable. The remaining crates (`aes`, `ctr`, `cipher`,
`crypto_box`) serve niche protocol requirements that ring deliberately does not
expose (raw block ciphers, CTR mode, NaCl box construction).

## Verification Checklist

1. `cargo build -p ripdpi-packets -p ripdpi-runtime -p ripdpi-dns-resolver`
2. `cargo test -p ripdpi-packets` -- QUIC encrypt/decrypt golden tests
3. `cargo test -p ripdpi-runtime` -- autolearn fingerprint tests
4. `cargo test -p ripdpi-dns-resolver` -- DNSCrypt cert + encrypt tests
5. `cargo test --workspace` -- full regression
6. Binary size comparison (release build of ripdpi-cli)
7. `cargo tree -d` -- confirm reduced duplicate crypto crates
