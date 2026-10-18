## Purpose

Open trustworthy latency evidence from its measured value and verify reconnect through the real service path.

## ADDED Requirements

### Requirement: REQ-LDR-DETAIL — Open the selected latency evidence

Home MUST offer one accessible action on measured RTT when samples exist. It MUST open the actual p50/p95, jitter, sample count, source and known measurement window. Zero measured RTT MUST remain valid. Missing evidence MUST remain explicitly unavailable. It MUST NOT invent a time series, percentile or window.

#### Scenario: Open a measured zero

- **WHEN** the RTT sample count is positive and the measured p50 is zero
- **THEN** latency details open with the actual zero and available evidence

#### Scenario: No samples

- **WHEN** no RTT samples exist
- **THEN** no latency detail action or fabricated RTT is shown

### Requirement: REQ-LDR-SNAPSHOT — Preserve selected measurement context

Details MUST retain one immutable clicked measurement snapshot across telemetry updates. Reopening MUST use the latest available snapshot. Source, time and window MUST describe that snapshot rather than unrelated newer telemetry. Freshness MUST not imply a frozen sample is a live stream.

#### Scenario: Telemetry changes while reading

- **WHEN** new measurements arrive after details open
- **THEN** the selected values and source remain stable until dismissal
- **AND** reopening selects current available evidence

### Requirement: REQ-LDR-ACCESS — Preserve accessible interaction and motion policy

The latency action and detail surface MUST retain names, 48dp targets, Back, explicit dismissal, reading order and return focus. Normal mode MUST use a finite theme transition. Reduced or disabled animation MUST render the final content directly. Large font and RTL MUST keep complete actions reachable.

#### Scenario: Read and dismiss with TalkBack

- **WHEN** real TalkBack opens and dismisses details in normal or system-off mode
- **THEN** actual speech and focus evidence confirms the selected values and return to the latency action

### Requirement: REQ-LDR-RECONNECT — Observe real reconnect behavior

Acceptance MUST drive Home comparison cancellation and confirmation through the actual service, native runtime and network path. Cancellation MUST retain the original running session and applied state. Confirmation MUST observe a real new runtime and its applied saved settings. Reachability before and after MUST be measured by actual traffic. Stale confirmation MUST not apply an old review. The test MUST NOT inject a connected status or replace the service with a fake.

#### Scenario: Cancel then confirm saved settings

- **WHEN** a running real service has pending saved changes and the user cancels review, then opens and confirms a fresh review
- **THEN** cancellation preserves the current runtime and confirmation starts the new runtime with observed applied state
- **AND** actual traffic succeeds before and after reconnect

#### Scenario: Use supported fixture configuration in each mode

- **WHEN** the real reconnect matrix runs four VPN lanes and two additional Proxy lanes
- **THEN** VPN observes HTTP DoH before reconnect and verified DoT after reconnect
- **AND** Proxy observes fresh HTTP DoH queries and real listener changes before and after reconnect
- **AND** every lane retains cancellation, stale-review, actual-runtime, applied-state and traffic assertions
- **AND** receipts do not claim Proxy custom-CA DoT coverage

### Requirement: REQ-LDR-VERIFY — Keep validation and publication evidence distinct

The change MUST record focused host tests, normal/reduced/off presentation, real TalkBack, RTL/font2, actual reconnect network evidence, exact APK provenance and hosted CI separately. It MUST preserve privacy, service admission and quality gates. All new text MUST cover ten locales. Main integration MUST follow rebased combined checks and authorized ordinary push.

#### Scenario: Publish accepted work

- **WHEN** the change is integrated and pushed
- **THEN** exact-source local and device evidence and exact-published-SHA CI are recorded separately
