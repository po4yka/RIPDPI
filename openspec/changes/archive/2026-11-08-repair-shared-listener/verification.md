---
task_id: PRX-1794118808814556
change: repair-shared-listener
commit_sha: b8818437811e16efdb56988270008ce4ef7e2ea8
local: passed
local_evidence: Native regression tests, independent loopback peers, the Android settings tests and lint, and the repository source gates passed. See the evidence below.
remote_ci: not_applicable
remote_ci_evidence: Local source and package gates are the acceptance target.
device: not_applicable
device_evidence: No change to OS redirection, JNI, VPN or physical routing. Host loopback and Android settings mapping cover the requested scope.
artifact: not_applicable
artifact_evidence: No release artifact requested.
deployment: not_applicable
deployment_evidence: No deployment requested.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC06-F1 | PRX-1794118871501209 | Owner stop closed a stalled local DNS connection in Mixed HTTP, BytePrefixed SOCKS5, address mode and Mixed SOCKS4a; no forced abort. A late DNS reply did not reach the origin. | Passed |
| REQ-LOC06-F2 | PRX-1794118871501209 | A complete address followed by NXDOMAIN and extra bytes made one DNS query and no upstream connection. Invalid ATYP released the only slot. Fragmented IPv6 and domain inputs preserved the payload. | Passed |
| REQ-LOC06-F3 | PRX-1794118871501209 | Neither HTTP ingress accepted credentials placed after an empty LF. A valid Basic header still established the tunnel and preserved its first bytes. | Passed |
| REQ-LOC06-F4 | PRX-1794118871501209 | Both HTTP listeners returned 400 for whitespace before the colon. The session parser rejected invalid field names. | Passed |
| REQ-LOC06-F5 | PRX-1794118871501209 | Both HTTP listeners accepted 65,536 bytes with early tunnel data; at 65,537 bytes the client read 431 and the upstream received no connection. | Passed |
| REQ-LOC06-F6 | PRX-1794118871501209 | Self-connect and local route denial returned 403 on both HTTP listeners. A refused upstream still returned 502; SOCKS error replies retained their protocol codes. | Passed |
| REQ-LOC06-F7 | PRX-1794118871501209 | ListenConfigSectionTest exercised stock settings (1080) and an explicit port (8181) after the Mixed toggle. Application and service lint passed with all ten locale resources. | Passed |
| REQ-LOC06-F8 | PRX-1794118871501209 | The process test invoked the real ripdpi --help and found all three listener switches. The real CLI accepted SOCKS4a and SOCKS5h curl clients in Mixed mode. | Passed |

## Local checks

- Cargo used the lockfile. Session: 75 tests; adapter: 71 tests; runtime handshake: 50 tests; runtime state: 7 tests; listener: 13 tests. The Mixed network test, the CLI process test, two TCP DNS cancellation tests and five UDP DNS cancellation tests passed.
- Independent clients used local TCP echo and DNS endpoints. All 12 simultaneous clients received their own payload. A split IPv6 address worked in both address modes. The DNS deadline probe rejected the request at 5.102 s with no origin accept. All four stop probes closed the DNS socket in 90–97 ms. Six curl transfers through the Mixed listener and the real CLI returned the expected body.
- At 65,537 bytes the client received the complete 431 response before socket close; the OS can still report reset after the response. No application credentials appeared in the echo payload.
- The 7 Android ListenConfigSectionTest cases and both app and service lint tasks passed. No Android device, privileged REDIRECT rule or external proxy was used. The Darwin Transparent checks only establish rejection without parser fallback.
- The repository source gates passed with unchanged baselines. The Linux-owned API snapshot is not part of the Darwin gate.
