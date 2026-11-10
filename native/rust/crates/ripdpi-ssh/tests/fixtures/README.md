# SSH key resource regression fixtures

These are public or disposable test keys. Never use them for authentication outside tests.

- `kdf64.pem`: ephemeral ssh-keygen Ed25519 encrypted key, originally bcrypt 1. Its metadata was changed to 64 rounds. Decryption must fail after real KDF work.
- `plain.b64`, `pbkdf2.pem`, `scrypt.pem`: ephemeral OpenSSL 4.1.0 Ed25519 key; PBES2 uses AES256CBC, PBKDF2 SHA256 with 2048 iterations or scrypt N=16384/r=8/p=1. Test passphrase: `local-test`.
- `legacy.b64`: ephemeral OpenSSL 4.1.0 RSA 2048 key, traditional AES128CBC PEM. Test passphrase: `local-test`.
- `argon2.ppk`, `ppk2.ppk`: unchanged public RustCrypto SSH 0.7.0-rc.11 examples `id_ed25519_enc.ppk` and `id_rsa_3072_enc.ppk2`. Test passphrase: `123`. Source: https://github.com/RustCrypto/SSH/tree/7a1c757d2e2fcde32680e373d5226481de6d434e/ssh-key/tests/examples . Used under the MIT option; see `SSH-KEY-LICENSE-MIT`.

The expected SSH SHA256 digests in key_compatibility.rs come from OpenSSL public DER (Ed25519) or original OpenSSH public blobs (RSA/PPK), hashed with Python hashlib. They are independent of the decoder under test.
