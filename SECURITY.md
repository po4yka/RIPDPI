# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Older releases | No |

Only the latest release receives security updates. Users should always run the
most recent version.

## Reporting a Vulnerability

**Please do not open public issues for security vulnerabilities.**

Use [GitHub Private Vulnerability Reporting](https://github.com/po4yka/RIPDPI/security/advisories/new)
to submit a report. This ensures the issue stays confidential until a fix is
available.

### What to include

- Description of the vulnerability and its potential impact
- Steps to reproduce or a proof of concept
- Affected versions or components (Android app, native proxy, VPN tunnel, etc.)
- Any suggested mitigations

### Response timeline

- **Acknowledgment**: within 3 business days
- **Initial assessment**: within 7 business days
- **Fix or mitigation**: depends on severity, but we aim for 30 days for
  critical issues

### Scope

The following are in scope:

- The RIPDPI Android application
- Native Rust proxy and VPN tunnel components
- JNI bridge and inter-process communication
- Build and release pipeline security (supply chain)

The following are out of scope:

- Vulnerabilities in upstream dependencies (report these to the upstream
  project, but feel free to let us know)
- Social engineering attacks
- Denial of service attacks against user devices

## Disclosure Policy

We follow coordinated disclosure. Once a fix is available, we will:

1. Release a patched version
2. Publish a GitHub Security Advisory with details
3. Credit the reporter (unless they prefer anonymity)
