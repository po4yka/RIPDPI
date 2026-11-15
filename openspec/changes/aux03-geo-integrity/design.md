## Context

The supported formats are MMDB Country/ASN and sing-geoip String records, sing-box Geosite v0, and the protobuf Geosite subset. Confirmed findings are F1-F6. Existing immutable snapshots and per-file rename remain the base.

## Goals / Non-Goals

- Goal: fix the six confirmed failures with one fix commit per finding.
- Non-goal: add SRS, arbitrary GeoIP schemas, attribute filtering, live reload, a backend, or a signature service.

## Decisions

- Native matching owns Go literal quoting, explicit regex errors, candidate case normalization, and subdomain-only suffixes.
- MMDB admission checks the referenced tree, separator, complete referenced value graph, and supported Country/ASN/String fields within input-derived limits. Use Reader.verify when optional description permits it. The value visitor also checks unknown fields when description is empty, as required for sing-geoip. Arbitrary MMDB schemas remain outside the supported profile.
- Add a single-kind native validation API and a separate NativeGeoDatabaseValidator JNI class. The private native method returns Boolean, with false as the panic/error sentinel. The public Kotlin wrapper requires success. Kotlin validates the complete temporary file and checks cancellation before rename.
- Store per-kind source/tag/content provenance in private sidecars. Missing or stale provenance forces a download. Local import clears remote provenance and its tag. Serialize repository publication.
- Use installed dependencies only. Keep protobuf/settings schemas and restart-to-apply behavior. Preserve last-good files and cleanup on validation failure or cancellation.

## Contracts and ownership

- Task ENG-1794755247754263 records isolated writers: native owns ripdpi-geo; assets owns delivery and the additive bridge; integration owns planning, generated API snapshots, combined validation and main integration.
- The native writer owns the approved direct serde workspace dependency edge and its lockfile edge. No new package or version is added. The native verifier generates the JNI export inventory from the actual Android ELF; assets includes that inventory in the admission commit. Other manifests, schemas, locales, baselines and snapshots remain serialized under integration.
- Validation accepts a path and explicit kind, rejects missing/malformed input, and does not activate a live matcher.
- Existing supported lookups retain results. Invalid supported data is rejected before it can become a decisive negative match.

## Risks / Trade-offs

- Full admission costs memory and CPU. Keep size limits and bounded traversal; run real fixtures and failure cases.
- Go and Rust regex differ. Support literal quoting and flags, and explicitly reject unsupported patterns.
- JNI compilation does not prove runtime binding. Test a real JVM invocation and exports where practical.
- Sidecars bind a file to its recorded source; they are not publisher signatures.

## Migration Plan

No settings/protobuf migration. Missing or mismatched sidecars require a verified refresh. Old versions ignore sidecars. Integrate after owning tests, independent review, cargo metadata --locked, architecture health and just ci-gates. Kotlin gates cover service and engine; device and hosted CI evidence remain separate.
