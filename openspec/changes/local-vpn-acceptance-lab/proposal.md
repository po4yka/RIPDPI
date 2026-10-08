# Change: Build reproducible local VPN acceptance

Task ID: `TST-1791477382746187`

## Why

Local network, native interop, and Android TUN tests exist but use separate
commands and different success criteria. Import markers and connected state do
not prove that a request crossed the selected protocol or fault path.

## What Changes

- Add one local acceptance manifest, runner, and strict evidence report.
- Add a disposable Linux VM/router path with TCP and UDP fault controls.
- Reuse independent peers and real Android TUN tests with configurable endpoints.
- Add shared local/CI commands and separate external-provider evidence.
- No breaking production API, JNI, protobuf, or storage change is planned.

## Capabilities

### New Capabilities

- `local-vpn-acceptance`: reproducible local protocol, Android, and routed fault acceptance.

### Modified Capabilities

- None.

## Impact

Test-lab tooling, Android instrumentation and fixture endpoints, native interop
tests, CI, and contributor documentation. No required production dependency.
