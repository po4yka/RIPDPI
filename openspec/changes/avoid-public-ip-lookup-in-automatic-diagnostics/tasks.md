# DGN-1790434142197991: Avoid external public-IP lookup in automatic diagnostics

## Objective

Guard pre-scan and post-scan snapshot capture by scan origin.

## Ownership

Own request preparation, finalization, the recording provider tests, and this task/OpenSpec. Other diagnostics changes remain separate.

## Execution

## Verification

Run focused and full diagnostics unit tests, detekt, strict OpenSpec validation, and `./taskctl validate`.
- [x] DGN-1790434216591296 Guard automatic pre-scan and post-scan public-IP lookup with recording-provider tests #bug !high @item:DGN-1790434142197991
