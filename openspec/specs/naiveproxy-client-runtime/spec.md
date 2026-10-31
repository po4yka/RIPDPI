# naiveproxy-client-runtime Specification

## Purpose
Define which NaïveProxy client runs for a profile, how the pinned upstream Chromium-based client is obtained and launched, and the credential, DNS, readiness, failure and cleanup guarantees that the upstream runtime keeps.

## Requirements

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

### Requirement: REQ-RLY-1793363326590556-005 — The proxy host resolves through encrypted DNS to a reachable address

The implementation MUST resolve a hostname proxy server through RIPDPI's encrypted resolver: protected DoH when socket protection is active, plain DoH in proxy-only mode. It MUST order the answers so that addresses accepting a TCP connection come first, and MUST map the hostname to the first one while keeping it for SNI and certificate checks. A failed resolution MUST fail the start with class `dns`.

#### Scenario: Hostname server in VPN mode

- **WHEN** socket protection is active and protected DoH returns addresses for the proxy hostname
- **THEN** the upstream client connects to a returned address and presents the hostname as SNI

#### Scenario: Hostname server in proxy-only mode

- **WHEN** socket protection is inactive and the profile server is a hostname
- **THEN** DoH, not the system resolver, supplies the address

#### Scenario: First address unreachable

- **WHEN** the first resolved address refuses TCP connections and a later one accepts them
- **THEN** the static host mapping uses the reachable address

#### Scenario: Resolution fails

- **WHEN** the resolver cannot resolve the proxy hostname
- **THEN** the relay start fails with the `dns` failure class and no upstream client process remains

### Requirement: REQ-RLY-1793363326590556-006 — Readiness, failure and exit follow the relay contract

The implementation MUST report the upstream runtime as ready only after the authenticated readiness probe succeeds on its local listener. It MUST classify an early exit or a readiness timeout through the existing relay failure classes, and MUST NOT report the relay "healthy" without positive upstream evidence. The upstream client's report of a negotiated padding type, or of a tunnel closed without error, MUST count as that evidence. The VPN start MUST NOT block on the upstream process's exit.

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

#### Scenario: Upstream evidence makes the relay healthy

- **WHEN** the upstream client reports a negotiated padding type after a successful CONNECT
- **THEN** relay health becomes "healthy"; with a wrong password it does not

### Requirement: REQ-RLY-1793363326590556-008 — Upstream output never exposes destinations or credentials

The implementation MUST NOT copy raw upstream client output into logs, telemetry or diagnostics exports. It MUST reduce each upstream line to a fixed event or a sanitized message that contains no destination host or port, no URL and no credential in raw, percent-encoded or base64 Basic form.

#### Scenario: Upstream error line names a destination

- **WHEN** the upstream client prints a line that names a destination host and an error
- **THEN** the recorded last error carries only the error code, without the destination

### Requirement: REQ-RLY-1793363326590556-009 — The editor explains that the path needs a pin

The editor MUST tell the user that the NaïveProxy path value is not sent when the profile has no certificate pin, because the upstream client does not support it.

#### Scenario: Path set without a pin

- **WHEN** a NaïveProxy profile has a path value and an empty pin field
- **THEN** the path field shows a notice that the path is ignored without a pin

### Requirement: REQ-RLY-1793363326590556-010 — Credential rejection is an auth failure for both clients

The implementation MUST report an upstream-client tunnel that the proxy closes with an `ERR_PROXY_AUTH_*` code as failure class `auth`, as the Rust helper reports a 407.

#### Scenario: Wrong password on a pin-free profile

- **WHEN** the upstream client runs with a wrong password and a client opens a tunnel
- **THEN** the relay failure class is `auth` and relay health is `degraded`

### Requirement: REQ-RLY-1793363326590556-011 — The editor can check a profile without starting a relay

The editor MUST offer a check that opens one CONNECT to a reserved `.invalid` name through the configured server, with the credentials on stdin, and reports `ok`, `auth`, `tls`, `dns`, `connect`, `timeout` or a failure. No failure path MAY report `ok`.

#### Scenario: Wrong password

- **WHEN** the check runs against a server that answers the CONNECT with 407
- **THEN** the editor reports that the server rejected the user name or password

### Requirement: REQ-RLY-1793363326590556-012 — HTTP/3 and concurrency run only through the upstream client

The implementation MUST pass HTTP/3 (`quic://`) and a connection count of 1 to 4 to the upstream client. It MUST refuse either option on a pinned profile in the editor, the link import and the service, and MUST NOT drop it silently.

#### Scenario: HTTP/3 profile

- **WHEN** a pin-free profile with HTTP/3 starts
- **THEN** the upstream client config has a `quic://` proxy and a routed transfer succeeds

#### Scenario: Pinned profile with HTTP/3

- **WHEN** a pinned profile with HTTP/3 or a count above 1 starts or is imported
- **THEN** it is refused with class `config`, or the link is rejected

### Requirement: REQ-RLY-1793363326590556-013 — The pinned profile helper needs no HelloRetryRequest

The Rust helper MUST offer X25519MLKEM768 first with its X25519 component share and Chrome's cipher suite, signature algorithm and ALPN lists, so that a Go server that prefers the hybrid group answers without a HelloRetryRequest.

#### Scenario: Handshake with Caddy forwardproxy

- **WHEN** the pinned-profile helper connects to the lab Caddy forwardproxy
- **THEN** the capture shows one ClientHello and a ServerHello that selects X25519MLKEM768
