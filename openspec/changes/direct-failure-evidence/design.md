## Context

The final fallback currently labels all failed attempts as IP blocking.

## Goals / Non-Goals

- Goal: remove unsupported IP attribution.
- Non-goal: new IP probes or changes to protocol-specific policies.

## Decisions

Use unknown cause and null transport class when no specific evidence exists. Preserve outcome and cooldown.

## Contracts and ownership

Controls worker owns DirectModePolicySupport.kt and DirectModePolicySupportTest.kt. No shared schema, lockfile, golden or locale files change.

## Risks / Trade-offs

The UI must accept a null transport class; it already does.

## Migration Plan

No migration; existing records remain readable. Revert to roll back. Run the core diagnostics unit suite and staticAnalysis on the combined tree.
