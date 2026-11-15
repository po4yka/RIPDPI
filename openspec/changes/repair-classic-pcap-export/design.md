## Context

Task DGN-1794755342253604 repairs the nine AUX-01 findings with independent commits. The user authorized implementation, integration and push.

## Goals / Non-Goals

- Deliver redacted export, explicit consent, trustworthy capture outcomes and bounded private storage.
- The selected file profile is PCAP 2.4 little-endian, microseconds and LINKTYPE_RAW. It has no public import or raw share action.

## Decisions

- Enforce a 262144-byte per-record allocation bound. Decode lengths as unsigned. Reject invalid header fields and distinguish complete EOF from a partial record.
- Keep original and captured lengths separate in the writer record API. Stage a redacted file in private cache, copy it only to a document the user creates, and delete that document on copy failure when the provider permits deletion.
- Read the existing capture setting at the capture controller boundary. Stop recording on withdrawal, including withdrawal during a diagnostic run. Enabling the setting permits a later explicit start.
- Report asynchronous writer completion in the existing capture lease. Close admission before counting discarded queue records. Keep drop counts with the finalized capture and include active slots in storage accounting.

## Contracts and ownership

- Format writer owns native/rust/crates/ripdpi-pcap.
- Capture writer owns native tunnel capture and list logic, capture leases, retention, and capture-state presentation.
- Export writer owns native redact entry and Kotlin export sections of shared files, document picker, viewer and locale updates for export.
- Integration owns Android reader, capture consent, locale serialization after export, task state, combined validation and Git integration. Shared JNI files have separate capture/list and redaction sections. All writers use separate worktrees.

## Risks / Trade-offs

- Document providers control deletion. Surface incomplete cleanup and close the descriptor.
- Strict PCAP validation changes malformed-file behavior. Existing valid captures remain readable.
- Storage cleanup is periodic for expired inactive captures. Captures beyond the managed boundary need no device or production access for host validation.

## Migration Plan

Retain the existing setting, format and filename pattern. Enforce retention on start, rotation and periodic cleanup. Run focused native and JVM regressions, representative Android app compilation, source gates and an independent review before integration.
