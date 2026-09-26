# DGN-1790430871310010: Remove stable relay profile identifiers from diagnostics archives

## Objective

Keep relay health evidence groupable within one archive without exposing stable profile-derived identifiers.

## Ownership

Own only `DiagnosticsArchiveRedactor.kt`, `DiagnosticsArchiveCsvEntryBuilder.kt`, and `DiagnosticsArchiveRelayTraceExporterTest.kt` in `:core:diagnostics`, plus this task's portfolio and OpenSpec artifacts. Other agents own all remaining diagnostics paths. No serialized shared files change.

## Execution

## Verification

Run focused `:core:diagnostics:testDebugUnitTest` for `DiagnosticsArchiveRelayTraceExporterTest`, strict OpenSpec validation, and `./taskctl validate`. Native build skip is permitted only for this JVM unit gate.
- [x] DGN-1790430977843442 Redact stable relay identifiers and verify archive-local aliases #bug !high @item:DGN-1790430871310010
