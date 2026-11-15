# Change: Correct DPI-38 failure decisions and feedback

Task ID: `ENG-1794722736729815`

## Why

The DPI-38 audit reproduced nine defects. Cross-flow feedback can train the wrong strategy. Retry limits do not cover response failures. Response parsing can misclassify valid or incomplete traffic. Closure alerts can count as successful handshakes. Network reprobes can mistake their own TLS incompatibility for network failure.

Focused review also found a reachable local-cancellation path: a client read error causes local upstream shutdown, whose induced EOF can count as a remote reset for an active rotation round. This path requires a real loopback regression and a separate fix.

## What Changes

- Bind each evolution result to one emitted attempt and expire abandoned attempts.
- Apply the configured route retry limit across connection phases.
- Inspect bounded HTTP and TLS response evidence across read boundaries.
- Keep cancellation and closure neutral for success and failure learning.
- Use a valid reprobe ClientHello and distinguish protocol rejection and bounded timeout.
- BREAKING: adjust internal Rust adaptive port results and feedback arguments. No JNI, JSON, protobuf, or stored-data schema changes are planned.

## Capabilities

### New Capabilities

- `dpi38-failure-decisions`: bounded failure classification, retry decisions, and attempt-owned adaptive feedback.

### Modified Capabilities

- None. The new delta captures the existing internal behavior and its corrections.

## Impact

- Rust classifier, policy, decision ports, adaptive/strategy services, desync adapter, and proxy runtime.
- No production dependency, toolchain, lockfile, baseline, or external endpoint changes.
- Local regression tests and independent loopback protocol checks establish acceptance. External-network and physical-device tests remain separate scopes.
