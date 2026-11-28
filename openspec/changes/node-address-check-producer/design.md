## Context

Portfolio task `DGN-1795868945773730` owns this change. An operator runs a diagnostics address check from a device in the filtered network and exports one signed attestation in the deploy contract node-address-check version 1, so the deployer can admit a new node address into RIPDPI bundles.

## Goals / Non-Goals

- Goal: deliver `Sign filtered-side node address checks for deploy admission` with observable evidence.
- Non-goal: automatic or background address checks, and any change to the deploy admission rule.

## Decisions

- Filed at proposal level. The deploy contract is the source of truth; this repository vendors it and does not change it.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
