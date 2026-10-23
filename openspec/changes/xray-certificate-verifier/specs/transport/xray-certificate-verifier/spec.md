## Purpose

Build and accept native Xray libraries only with the reviewed patched certificate verifier.

## ADDED Requirements

### Requirement: REQ-XCV-COMPILER — Patched compiler

The producer MUST build the pinned stable Xray sources with exactly Go 1.27.3 and MUST reject other toolchains before compilation.

#### Scenario: Old compiler

- **WHEN** the active compiler is Go 1.27.2
- **THEN** the build fails before producing an artifact.

#### Scenario: Reviewed compiler

- **WHEN** Go 1.27.3 and the pinned mobile tools are present
- **THEN** the producer can build the real stable AAR and run native protection and lifecycle tests.

### Requirement: REQ-XCV-ARTIFACT — Embedded compiler evidence

The verifier MUST require the reviewed compiler in both metadata and the embedded Go build information of every packaged ABI. It MUST reject malformed, absent, old, or conflicting build information.

#### Scenario: Metadata hides an old compiler

- **WHEN** metadata claims Go 1.27.3 but one native library records Go 1.27.2
- **THEN** artifact verification fails even if archive digests match.

#### Scenario: Complete rebuilt runtime

- **WHEN** all four ABI libraries record Go 1.27.3 and the existing provenance, API, alignment, and size gates pass
- **THEN** release artifact verification succeeds.
