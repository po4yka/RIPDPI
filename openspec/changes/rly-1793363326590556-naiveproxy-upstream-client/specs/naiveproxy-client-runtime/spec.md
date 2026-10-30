## Purpose

Define which NaïveProxy client runs for a profile, how the pinned upstream Chromium-based client is obtained and launched, and the credential, DNS, readiness, failure and cleanup guarantees that the upstream runtime keeps.

## ADDED Requirements

### Requirement: REQ-RLY-1793363326590556-001 — Client selection follows the certificate pin

The implementation MUST run the pinned upstream client for a NaïveProxy profile that has no server certificate pin. It MUST run the RIPDPI Rust helper for a profile that has a pin. Any other profile field MUST NOT change the selection.

#### Scenario: Profile without a pin

- **WHEN** a NaïveProxy relay starts for a profile whose `naiveServerPinSha256` is empty
- **THEN** the launched subprocess is the upstream client executable, not `ripdpi-naiveproxy`

#### Scenario: Profile with a pin

- **WHEN** a NaïveProxy relay starts for a profile that has a valid pin
- **THEN** the launched subprocess is `ripdpi-naiveproxy` with `--server-pin-sha256`, and its behavior is unchanged

### Requirement: REQ-RLY-1793363326590556-002 — The upstream artifact is pinned and verified

The build MUST stage the upstream client only from the release recorded in the repository pin manifest. It MUST verify every per-ABI artifact against its recorded sha256 before staging. A missing artifact or a digest mismatch MUST fail the build without staging any executable.

#### Scenario: Digest mismatch

- **WHEN** a downloaded upstream artifact's sha256 differs from the manifest value
- **THEN** the build fails with an error that names the ABI and the expected digest, and no upstream executable is staged

#### Scenario: Every supported ABI is staged

- **WHEN** a build packages the ABIs arm64-v8a, armeabi-v7a, x86 and x86_64
- **THEN** each packaged ABI contains the upstream executable extracted from the matching pinned artifact

### Requirement: REQ-RLY-1793363326590556-003 — Credentials never reach argv or logs

The implementation MUST pass the upstream proxy credentials, the local listener credentials and any extra headers to the upstream client only through a config file that only the app's UID can read. The process argv, logcat and diagnostics exports MUST NOT contain them. The config file MUST be removed after the runtime stops or fails to start.

#### Scenario: Launch argv

- **WHEN** the upstream client is launched
- **THEN** its argv holds only the config file path and contains no username, password or header value

#### Scenario: Cleanup after a failed start

- **WHEN** the upstream client exits before readiness
- **THEN** the config file no longer exists, and the reported failure message contains no credential

### Requirement: REQ-RLY-1793363326590556-004 — The local listener stays authenticated on loopback

The upstream client's local listener MUST bind only a loopback address and MUST require the generated local listener credentials. This is the same contract as the Rust helper.

#### Scenario: Unauthenticated local client

- **WHEN** a local SOCKS client connects to the upstream listener without the generated credentials
- **THEN** the listener refuses the session and makes no upstream request

### Requirement: REQ-RLY-1793363326590556-005 — The proxy host resolves through protected DoH

The implementation MUST resolve a hostname proxy server through RIPDPI's protected encrypted resolver, and MUST pass the result to the upstream client as a static host mapping. The upstream client MUST then use that address while keeping the hostname for TLS SNI and certificate verification. If resolution fails, the start MUST fail closed with the `dns` failure class instead of using the system resolver. An IP-literal server MUST be used as given.

#### Scenario: Hostname server

- **WHEN** the profile server is a hostname and protected DoH returns an address
- **THEN** the upstream client connects to that address and presents the hostname as SNI

#### Scenario: Protected resolution fails

- **WHEN** protected DoH cannot resolve the proxy hostname
- **THEN** the relay start fails with the `dns` failure class and no upstream client process remains

### Requirement: REQ-RLY-1793363326590556-006 — Readiness, failure and exit follow the relay contract

The implementation MUST report the upstream runtime as ready only after the authenticated readiness probe succeeds on its local listener. It MUST classify an early exit or a readiness timeout through the existing relay failure classes, and MUST NOT report the relay "healthy" without positive upstream evidence. The VPN start MUST NOT block on the upstream process's exit.

#### Scenario: Upstream client ready

- **WHEN** the upstream client accepts an authenticated SOCKS handshake on its listener
- **THEN** the relay start completes and the VPN service reaches Running

#### Scenario: Upstream client exits early

- **WHEN** the upstream client exits before readiness, for example on an invalid config
- **THEN** the start fails with a classified failure, the exit code is recorded, and no stale listener or config file remains

### Requirement: REQ-RLY-1793363326590556-007 — Pin-free profiles present Chromium's wire behavior

A NaïveProxy session for a pin-free profile MUST NOT trigger a TLS HelloRetryRequest from a Go forwardproxy server that prefers X25519MLKEM768. Its ClientHello JA4 MUST equal that of the pinned upstream release, as measured in the repository's wire-comparison procedure.

#### Scenario: Wire comparison against the pinned server

- **WHEN** the acceptance wire comparison runs a pin-free profile against the pinned Caddy forwardproxy server
- **THEN** the capture shows one ClientHello with no HelloRetryRequest, and the JA4 equals the upstream reference value recorded for the pinned release
