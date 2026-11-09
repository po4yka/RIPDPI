# TRN-1794211839090466

## Objective

Repair confirmed HTTP carrier defects and validate the supported modes.

## Ownership

Use the parallel ownership lanes in the portfolio record. The coordinator owns Cargo manifests and lock.

## Execution

- [x] TRN-1794211922684114 Repair xHTTP cancellation and Naive documentation #bug !high @item:TRN-1794211839090466
- [x] TRN-1794211923197752 Repair MASQUE H2 and H3 carrier capability and success contracts #bug !high @item:TRN-1794211839090466
- [x] TRN-1794211923739038 Repair capsule framing and response header validation #bug !high @item:TRN-1794211839090466
- [x] TRN-1794211924292344 Review and validate the combined changes before integration #bug !high @item:TRN-1794211839090466

## Verification

Run affected cargo tests and clippy with --locked, independent pr-reviewer and async-cancel-safety reviews, just ci-gates and taskctl validation. Device and external deployment acceptance are outside this source repair.
