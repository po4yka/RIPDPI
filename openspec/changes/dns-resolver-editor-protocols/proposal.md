# Change: Edit DoQ and ODoH DNS resolvers

Task ID: `DNS-1790339241109329`

## Why

DNS settings renders saved DoQ and ODoH resolvers as a DoH editor. Saving that form silently replaces the selected protocol and discards ODoH settings.

## What Changes

- Show DoQ and ODoH as selectable encrypted DNS protocols.
- Show protocol-specific forms without changing the active resolver before Save.
- Save fresh, structurally valid ODoH settings; block DoQ activation while routed VPN DNS requires SOCKS5.
- Reject incomplete or malformed endpoint input before writing settings.

## Capabilities

### New Capabilities

- `dns-resolver-settings`: Edit supported encrypted resolver protocols without losing stored settings.

### Modified Capabilities

- None.

## Impact

App Compose DNS settings, DNS UI state mapper, settings mutations, localized strings, and tests. No protobuf or native wire schema change; no breaking change.
