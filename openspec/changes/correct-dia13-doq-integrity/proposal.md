# Change — Correct DoQ integrity diagnostics

Task ID: `DGN-1794744781459086`

## Why

DIA-13 can report agreement from absent or conflicting DNS evidence. Its suite can omit DoQ findings. The bridge also loses causes and has inconsistent framing, bootstrap deadlines and cancellation.

## What Changes

- Separate usable DNS evidence from transport and integrity outcomes.
- Preserve each source of DoH comparison evidence and show incomplete coverage.
- Use bounded framing, typed failures, one deadline and scoped cancellation.

## Capabilities

### New Capabilities

- `diagnostics/doq-integrity` defines results for the existing IN/A Direct DoQ diagnostic.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostic runner and DPI tool presentation, Direct DoQ resolver and Android JNI adapter.
- Kotlin and native modules ship together. The existing JNI symbol and method signature stay in place. Local JSON control messages manage cancellation.
