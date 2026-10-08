# DGN-1791484491975137: Keep network metadata in one capture scope

## Objective

Reject mixed network metadata.

## Ownership

metadata-worker: NetworkMetadataProvider.kt, NetworkSnapshotFactory.kt and tests. Root owns resources and integration; other writers own Home and classification.

## Execution

## Verification

Targeted Kotlin tests and combined staticAnalysis.
- [ ] DGN-1791484590804921 Guard network metadata capture and add regression tests #feature @item:DGN-1791484491975137
