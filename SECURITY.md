# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Older releases | No |

Only the latest release receives security updates. Users should always run the most recent version.

## Reporting a Vulnerability

**Please do not open public issues for security vulnerabilities.**

Use [GitHub Private Vulnerability Reporting](https://github.com/po4yka/RIPDPI/security/advisories/new) to submit a report. This ensures the issue stays confidential until a fix is available.

### What to include

- Description of the vulnerability and its potential impact
- Steps to reproduce or a proof of concept
- Affected versions or components (Android app, native proxy, VPN tunnel, etc.)
- Any suggested mitigations

### Response timeline

- **Acknowledgment**: within 3 business days
- **Initial assessment**: within 7 business days
- **Fix or mitigation**: depends on severity, but we aim for 30 days for critical issues

### Scope

The following are in scope:

- The RIPDPI Android application
- Native Rust proxy and VPN tunnel components
- JNI bridge and inter-process communication
- Build and release pipeline security (supply chain)
- Relay configuration parsing, relay native config generation, pluggable-transport launch boundaries, and DNS/TLS privacy controls implemented in this repository

The following are out of scope:

- Vulnerabilities in upstream dependencies (report these to the upstream project, but feel free to let us know)
- Social engineering attacks
- Denial of service attacks against user devices

## Threat-Model Notes

RIPDPI is a client-side Android VPN/proxy and relay client. It can reduce some network-interference failure modes, but it is not a turnkey anonymity guarantee and cannot make an untrusted relay, resolver, proxy, device, or app trustworthy.

### DNS and ECH

- Real outbound ECH is owned by `ripdpi-tls-profiles` and requires encrypted-DNS HTTPS/SVCB resolution for ECHConfig discovery. Plaintext DNS must not be used as an ECH bootstrap path.
- GREASE-only ECH is the default posture when no real ECHConfig is available. GREASE can improve fingerprint parity, but it does not hide SNI from a server that lacks real ECH support.
- VLESS Reality does not use real ECH. Reality keeps its visible cover `serverName` and SessionID authentication model; only conditional GREASE parity is allowed by the Reality ECH decision. See [ADR 0001](docs/adr/0001-reality-ech.md).
- DNS-over-relay is fail-closed once a relay is active: DNS must not silently fall back to the system or direct path. A one-shot bootstrap may resolve the relay path itself before the relay is usable.
- ODoH protects query privacy from the target resolver when the proxy and target do not collude. It is not a DPI-evasion transport by itself and does not hide DNS metadata from a colluding proxy+target pair.

### Relays and Chains

- Relay credentials, subscription URLs, bootstrap tokens, and endpoint URLs are secrets. Reports and logs should redact them before sharing.
- Multi-hop chains help only when hops are controlled by different trust domains. Chaining through the same operator, jurisdiction, host, or billing account usually adds latency without providing meaningful trust separation.
- MASQUE, Hysteria2, TUIC, Trojan, AnyTLS, Shadowsocks, VLESS Reality/xHTTP, ShadowTLS, and Cloudflare Tunnel are relay transports with different fingerprint and metadata properties; selecting one does not automatically solve all DPI, traffic-correlation, or relay-trust risks.
- Snowflake remains an external Go pluggable-transport binary, not a native Rust backend. See the [Snowflake native Rust no-go decision](docs/architecture/snowflake-native-rust-decision.md).
- Tor is an opt-in Arti-backed anonymity relay backend with a different latency and threat model from ordinary proxy relays. In censored networks it is expected to bootstrap through bridges and pluggable transports rather than direct public Tor entry discovery.

### Device Boundary

- Android VPN mode routes device traffic through a local TUN-to-SOCKS bridge, but apps can still leak data through their own account sync, push services, telemetry, or local storage.
- Root helper functionality is opt-in and expands the trusted computing base. Treat root-helper IPC and privileged packet operations as higher-risk surfaces.

## Disclosure Policy

We follow coordinated disclosure. Once a fix is available, we will:

1. Release a patched version
2. Publish a GitHub Security Advisory with details
3. Credit the reporter (unless they prefer anonymity)
