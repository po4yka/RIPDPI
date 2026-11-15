## ADDED Requirements

### Requirement: REQ-SP-BODY — Bound response bodies

The shared-priors downloader MUST enforce its existing manifest and payload byte limits before full response buffering, including HTTP error responses. It MUST close bodies after failure.

#### Scenario: Oversized response

- **WHEN** a success or error body exceeds the endpoint limit
- **THEN** the downloader stops reading within a bounded transport buffer and does not return the body for apply

### Requirement: REQ-SP-CONFIG — Resolve documented release URLs

Both release URLs MUST support first-nonblank Gradle properties, local.properties, then environment. Missing inputs MUST preserve the disabled default. The release public key and signing input MUST remain unchanged.

#### Scenario: Gradle URL properties

- **WHEN** the owner sets valid key and both URLs as Gradle properties
- **THEN** generated BuildConfig contains both URLs and enables the configured channel

### Requirement: REQ-SP-MEAN — Preserve finite prior ratios

Positive finite prior parameters MUST retain their mathematical mean when their sum exceeds the finite floating-point range. The prior weight cap and local feedback MUST remain effective.

#### Scenario: Equal large parameters

- **WHEN** alpha and beta both equal 1e308
- **THEN** the prior mean is 0.5 and selection uses the existing capped weight
