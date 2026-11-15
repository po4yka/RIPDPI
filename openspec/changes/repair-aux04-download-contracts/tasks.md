# SVC-1794755640074922

## Objective

Repair the four confirmed download defects with one atomic code commit each.

## Ownership

See docs/tasks/issues/repair-aux04-download-contracts.md for exact writer lanes. Integration owns task/spec records, combined gates, snapshots, and publication.

## Execution

- [x] SVC-1794755720003749 Repair F1 response bounds before Retrofit buffering #bug !high @item:SVC-1794755640074922
- [x] SVC-1794755720588786 Repair F2 incomplete TLS response handling #bug !high @item:SVC-1794755640074922
- [x] SVC-1794755721277092 Repair F3 owned HTTP cancellation lifecycle #bug !high @item:SVC-1794755640074922
- [x] SVC-1794755722049880 Repair F4 native response media type propagation #bug !high @item:SVC-1794755640074922
- [ ] SVC-1794755722719619 Validate review integrate and publish the four repairs #bug !high @item:SVC-1794755640074922

## Verification

Run owning loopback regressions, Kotlin tests, Rust tests with --locked, JNI checks, just ci-gates, staticAnalysis, and independent review. Preserve explicit device and external gaps.
