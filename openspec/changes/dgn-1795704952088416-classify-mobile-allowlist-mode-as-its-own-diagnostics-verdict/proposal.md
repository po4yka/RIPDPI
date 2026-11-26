# Change: Classify mobile allowlist mode as its own diagnostics verdict

Task ID: `DGN-1795704952088416`

## Why

Diagnostics reports mobile allowlist mode as its own verdict, separate from a generic outage and from DPI blocking, and narrows the candidate transports for that regime.

## What Changes

- A network where permitted-domain targets are reachable while foreign-address targets and UDP targets fail is classified as allowlist mode, distinct from a generic outage and from a DPI block.
- A network where every target fails is not classified as allowlist mode.
- The allowlist verdict narrows the candidate transports to those that can complete standard HTTPS toward a permitted destination, and the narrowing is visible in the diagnostics result.
- The verdict, its probe evidence and the narrowing reason appear in history and redacted exports without addresses of user networks.

## Capabilities

### New Capabilities

- `dgn-1795704952088416-classify-mobile-allowlist-mode-as-its-own-diagnostics-verdict`: Classify mobile allowlist mode as its own diagnostics verdict

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
