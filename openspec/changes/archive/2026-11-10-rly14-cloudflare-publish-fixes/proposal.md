# Change: Correct Cloudflare publish relay defects

Task ID: `RLY-1794300683762457`

## Why

The RLY-14 audit reproduced early upload closure, secret-bearing parser errors, query-path mismatch, and incorrect UDP dispatch. It also found unreachable IPv6 loopback and missing editor validation.

## What Changes

- Keep streaming upload responses open for their request lifetime.
- Report credential errors without input material or unsafe causes.
- Match the path component of configured xHTTP URLs.
- Reject non-TCP VLESS commands before side effects.
- Accept bracketed IPv6 loopback and reject invalid ports.
- Require a VLESS identity for both Cloudflare modes.
- No JNI, protobuf, storage, or configuration schema change is required.

## Capabilities

### New Capabilities

- `relay/cloudflare-publish-safety`: Correct Cloudflare origin and configuration boundary behavior.

### Modified Capabilities

- None.

## Impact

- Rust `ripdpi-cloudflare-origin`, Kotlin `:core:service`, and `:app` validation.
- Existing unsupported VPN, UDP payload, and alternate xHTTP modes remain unsupported.
