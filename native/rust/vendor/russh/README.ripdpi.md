# russh vendor notes

This is the published russh 0.65.1 crate from
https://static.crates.io/crates/russh/russh-0.65.1.crate.
Archive SHA-256: 94be3d86c71b9ed220a675c405e35c7a02565a009bf8da2e9cc13a700320a5c5.
Source revision: b33092aad1e51c66258a12e8b83fc473a68d9d34 (russh directory).
The package is Apache-2.0 licensed. Cargo.toml.orig retains upstream metadata.

RIPDPI checks the server host key during every key exchange, including rekey.
The upstream API only exposed the key on the initial exchange, so the wrapper
could not enforce its existing strict fingerprint contract for later keys.
Remove these patches when a pinned upstream release provides both changes.

Comments identify upstream public test-key fixtures and PEM armor markers for
the existing secret checker. All fixture and marker string bytes are unchanged. Upstream README trailing
spaces were removed to pass the repository whitespace check.
The Apache-2.0 license text is supplied from https://www.apache.org/licenses/LICENSE-2.0.txt.

The client also exposes a cloneable cancellation signal. The owned session
future uses futures::Abortable so cancellation drops blocked channel delivery
and its transport. RIPDPI publishes the signal before authentication and can
cancel it without waiting for its session-state lock. The owner still joins
the worker, and a worker panic remains a cleanup failure.

RIPDPI adds `keys::validate_secret_key_resources` for admission before network
work. It uses the decoder's PEM reader and the pinned `ssh-key` 0.7.0-rc.11
and `pkcs8` 0.11.0 / `pkcs5` 0.8.0 metadata decoders. It does not derive keys.
Callers must still decode the key and check its password. The limits are:

- Input: 64 KiB.
- OpenSSH bcrypt: 1 to 64 rounds.
- PKCS#8 PBKDF2: 1 to 100,000 iterations.
- PKCS#8 scrypt: positive power-of-two N and positive r/p;
  `128*r*(N+p+1) <= 32 MiB`, and `N*r*p <= 1,048,576`.
- Encrypted PPK v3 Argon2: positive memory/passes/lanes;
  memory <= 32,768 KiB, passes <= 64, lanes <= 8, and
  memory*passes <= 524,288 KiB per call. The pinned PPK decoder derives
  Argon2 twice, for decryption and MAC verification.

The scrypt memory formula includes the `b`, `v` and `t` buffers in the pinned
sequential `scrypt` 0.12.0 implementation. All products use checked arithmetic.
PPK cost headers must be unique. Invalid or over-budget costs return a constant
resource-limit error with no key or password data. PPK v2 and legacy PEM AES
keep their fixed-cost decoder paths. Plain PEM keys are subject to the input
limit. Encrypted PKCS#8 DER is checked even under plain PEM armor, since the
existing decoder accepts that form. These are resource limits, not a hard
wall-clock limit on each device. The caller owns and joins admitted KDF work.
No crypto algorithm or dependency version changes in this patch.
