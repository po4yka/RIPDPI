## Context

Halted/VPN is both the initial state and a normal stopped state. It cannot prove a failed tunnel.

## Goals / Non-Goals

Prevent false tunnel failure reports. Do not add service history, change native classification, or change network probes.

## Decisions

Remove the synthetic flag override. Preserve explicit vpnServiceWasActive evidence supplied by the snapshot provider. Neither a configured mode nor historical start count proves why a current network is unavailable.

## Risks / Trade-offs

Without explicit evidence, reports use network_unavailable. A more specific diagnosis requires new measured evidence.

## Migration Plan

No migration. Root owns collector and its tests. Add a failing collector regression, remove inference, test preserved evidence, then run diagnostics tests and staticAnalysis. Revert this scoped commit to roll back.
