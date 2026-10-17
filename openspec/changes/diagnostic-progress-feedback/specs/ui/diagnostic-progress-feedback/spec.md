## Purpose

Keep scan progress truthful, make phase changes visible, and preserve accessible interaction during motion.

## ADDED Requirements

### Requirement: REQ-DPF-MEASURED — Smooth only measured progress

The diagnostic UI MUST animate only forward measured updates within the same scan. It MUST show the first value, new scan, backward correction, and newly known value directly. It MUST keep unknown progress indeterminate and MUST NOT infer completion from time.

#### Scenario: Forward measured update

- **WHEN** a known value increases within the same scan under normal motion
- **THEN** the fill moves through intermediate values and settles at the latest measured value

#### Scenario: Reset or uncertainty

- **WHEN** the scan changes, the measured value decreases, or an unknown value becomes known
- **THEN** the fill shows the current value directly without crossing values from an earlier state

### Requirement: REQ-DPF-SEMANTICS — Expose current measurement without frame speech

Accessibility MUST expose the latest actual progress immediately. Animation frames MUST NOT change accessible progress or trigger repeated announcements. Unknown values MUST retain indeterminate semantics.

#### Scenario: Visual interpolation

- **WHEN** the fill is moving toward a new measured value
- **THEN** accessibility already exposes that measurement and intermediate frames do not change its semantic value

### Requirement: REQ-DPF-PHASE — Give stable phase feedback

Each phase MUST expose its name and localized pending, active, or completed state. Finite visual feedback MUST depend on phase state changes, not probe or count updates. Decorative marks MUST NOT replace accessible state. The current phase MUST have one polite announcement source. Feedback MUST preserve focus, scrolling, and Cancel access.

#### Scenario: Phase change followed by counter ticks

- **WHEN** a phase becomes active and later receives progress-only updates
- **THEN** its current state stays accessible, the phase change is announced, and progress ticks do not replay phase feedback

### Requirement: REQ-DPF-MOTION — Respect disabled and reduced motion

Disabled or reduced motion MUST show the current measured value and phase state directly. Turning animations off during a transition MUST show the latest value immediately. Static previews MUST stay deterministic.

#### Scenario: Motion disabled during an update

- **WHEN** system animations are disabled during a running transition
- **THEN** no intermediate fill or stale phase label remains after the setting is observed

### Requirement: REQ-DPF-VALIDATION — Observe local device and hosted proof

Acceptance MUST separate host tests, actual emulator rendering, real TalkBack, APK provenance, and hosted CI. Normal and system-off TalkBack checks MUST use an active real service and retained speech/focus evidence. Tests MUST NOT treat component semantics alone as TalkBack proof.

#### Scenario: Publication

- **WHEN** the implementation is integrated and pushed with user authorization
- **THEN** its local checks, exact-source APK emulator results, and exact-revision hosted CI result are recorded separately
