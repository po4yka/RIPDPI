# Correct diagnostics recommendation and regression selection

Task ID: `DGN-1790433260349809`

## Why

The recommendation engine ignores a produced TCP freeze outcome. A suppressed DNS trigger can hide a later valid trigger. Home analysis chooses a prior run by session count, which can make the regression delta compare with an older run.

## What Changes

- Classify TCP freeze as threshold blocking.
- Inspect DNS triggers until one has independent evidence.
- Track the last completed home run in memory for regression comparison.

## Capabilities

### Modified Capabilities

- `diagnostics/recommendations`: Recommendations use relevant probe outcomes and valid DNS triggers.
- `diagnostics/home-analysis`: Regression comparison uses the last completed run.

## Impact

This change affects only Kotlin recommendation and home analysis logic in `:core:diagnostics`. It does not change storage, JNI, wire contracts, or external services.
