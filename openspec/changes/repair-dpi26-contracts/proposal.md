# Change: Repair UDP fragmentation contracts

Task ID: `RST-1794677647125288`

## Why

UDP fragmentation loses safe error information across the helper, drops the selected IPv4 ID policy, and accepts non-root UI profiles that native startup cannot run.

## What Changes

- Preserve pre-send error recovery without replay after uncertain emission.
- Preserve selected ID policy in synthetic UDP groups.
- Reject IP fragmentation drafts when root mode is off.

## Capabilities

### New Capabilities

- `udp-fragmentation-contracts`: safe failures, ID projection and root admission.

### Modified Capabilities

- None.

## Impact

Rust privileged operations, helper, platform client, UDP adapter and config; Kotlin data model and tests. No dependency or persistent schema change. Reuse the existing error metadata; legacy or malformed replies stay terminal.
