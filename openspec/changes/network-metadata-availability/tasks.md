# DGN-1791484646270191: Keep unavailable observations unknown

## Objective

Preserve unavailable facts.

## Ownership

metadata-worker owns context model, metadata provider, snapshot factory, snapshot UI mapper and tests. Root owns summary exporters/resources/integration.

## Execution

## Verification

Kotlin tests and staticAnalysis.
- [ ] DGN-1791484702528910 Preserve availability in network models capture and UI with regression tests #feature @item:DGN-1791484646270191
