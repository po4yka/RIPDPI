# play-listing Specification

## Purpose
Show supported app capabilities through current, readable and accurate localized Google Play artwork.

## Requirements

### Requirement: REQ-PLAY-FIDELITY — Real feature evidence

The listing MUST show the actual advertised feature on each poster and use measured data only from a real run. It MUST preserve captured warnings and state. A connection or scan MUST NOT be simulated as measured acceptance.

#### Scenario: Completed feature capture

- **WHEN** the capture tool records an app screen
- **THEN** its route, state, dimensions, build and raw image hashes are recorded and the image shows the advertised function.

#### Scenario: Failed or stale capture

- **WHEN** a run fails, a frame is missing or Android inputs change
- **THEN** capture validation rejects the listing instead of inventing a result or reusing a stale frame.

### Requirement: REQ-PLAY-READABILITY — Readable distinct composition

The listing MUST use clear feature headings, visible real UI and distinct layouts. The banner MUST explain the core purpose without relying on a tiny UI image. README cards MUST remain readable at their displayed width.

#### Scenario: Small preview

- **WHEN** posters are reviewed at 260 pixels and banners at 360 pixels wide
- **THEN** the key feature and its explanation are legible and do not overlap or clip.

### Requirement: REQ-PLAY-LOCALES — Localized consistent presentation

The listing MUST use localized general UI section labels, complete locale resources and a pinned font for Persian. RTL branding MUST stay grouped. Protocol names and user names MUST remain accurate. Credentials and private network data MUST NOT appear in exports.

#### Scenario: Localized capture

- **WHEN** a supported marketing locale is rendered
- **THEN** generic section text uses that locale and the brand, headings and glyphs are complete.

### Requirement: REQ-PLAY-EXPORT — Compatible checked outputs

The tool MUST retain six 1080x1920 phone assets and one 1024x500 banner in seven existing marketing locales plus English aliases. PNG output MUST be RGB without alpha. Browser JPEG export MUST restore preview styles on success or failure.

#### Scenario: Full export

- **WHEN** all source and layout checks pass
- **THEN** all 56 assets are published atomically with at most 20 percent added text and nine valid README galleries.
