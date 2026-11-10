## Purpose

Keep imported SSH private keys within a fixed resource budget and preserve runtime responsiveness and cleanup ownership during decryption.

## ADDED Requirements

### Requirement: REQ-SSH-KEY-BUDGET — Reject excessive key work

The SSH configuration MUST reject excessive key text, passphrases, or KDF cost before network or KDF work. It MUST apply the limits in the following scenarios to every accepted container, including relabeled encrypted DER. Limits bound admitted work, not elapsed time.

#### Scenario: Size and CPU limits

- **WHEN** key text or passphrase exceeds 65536 bytes, OpenSSH bcrypt rounds are outside 1..64, or PKCS8 PBKDF2 iterations are outside 1..100000
- **THEN** validation rejects it with a secret-free resource-limit error.

#### Scenario: Scrypt limits

- **WHEN** N is not a positive power of two, r or p is zero, checked 128*r*(N+p+1) exceeds 32 MiB, or N*r*p exceeds 1048576
- **THEN** validation rejects it before upstream parameter conversion or allocation.

#### Scenario: PPK v3 Argon2 limits

- **WHEN** memory, passes, or parallelism is zero; memory exceeds 32768 KiB; passes exceeds 64; parallelism exceeds 8; checked memory*passes exceeds 524288 KiB; or cost headers repeat
- **THEN** validation rejects it before either Argon2 invocation.

#### Scenario: Excessive or malformed cost

- **WHEN** a key exceeds any limit, has an invalid zero scrypt N, or has ambiguous duplicate PPK cost metadata
- **THEN** validation fails before network connection or KDF work and the diagnostic contains no secret input.

#### Scenario: Supported default keys

- **WHEN** a plain key or an encrypted OpenSSH, PKCS8, legacy PEM, or PPK key meets the budget
- **THEN** resource validation preserves the decoder's existing format and authentication rules.

### Requirement: REQ-SSH-KEY-OWNER — Keep admitted decoding responsive and owned

Private-key decoding MUST execute outside Tokio executor workers. At most one admitted decode job MUST run across SSH clients. Queued jobs MUST observe cancellation and the connection deadline before starting. Once a decode starts, the connection constructor MUST join it before returning; successful close MUST leave no decode work owned by that connection. After decoding, an expired or cancelled constructor MUST NOT send authentication.

#### Scenario: Cancel during admitted decryption

- **WHEN** cancellation arrives while an admitted synchronous KDF runs
- **THEN** unrelated Tokio timers remain responsive and close waits for the owned bounded job before completing.

#### Scenario: Cancel while waiting for the slot

- **WHEN** another client holds the decode slot and a waiting client is cancelled
- **THEN** the waiting client closes without starting its KDF.
