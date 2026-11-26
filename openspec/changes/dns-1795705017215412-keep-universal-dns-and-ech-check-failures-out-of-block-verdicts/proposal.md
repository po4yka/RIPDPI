# Change: Keep universal DNS and ECH check failures out of block verdicts

Task ID: `DNS-1795705017215412`

## Why

The ECH and DNS survey and the CDN ECH updater do not treat a failure of every measurement as a signal of blocking.

## What Changes

- A survey outcome where every resolver or measurement fails is classified as universal failure, separate from a block signal.
- The ECH updater does not mark a config as blocked, downgrade it or suppress it when every measurement fails.
- Regression tests cover a universal failure and a partial failure, and only the partial failure can produce a block signal.
- History and redacted exports show the universal-failure class.

## Capabilities

### New Capabilities

- `dns-1795705017215412-keep-universal-dns-and-ech-check-failures-out-of-block-verdicts`: Keep universal DNS and ECH check failures out of block verdicts

### Modified Capabilities

- None.

## Impact

- Portfolio area: `dns`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
