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

Acceptance MUST drive Home review through the actual service, native runtime and network. Cancellation MUST retain the running session and applied transport. Confirmation MUST observe a new runtime with applied saved settings. Actual traffic MUST succeed before and after. Stale confirmation MUST NOT apply an old review. Acceptance MUST NOT inject connected status or replace the service with a fake.

#### Scenario: Cancel then confirm saved settings

- **WHEN** a running real service has pending saved changes and the user cancels review, then opens and confirms a fresh review
- **THEN** cancellation preserves the current runtime and confirmation starts the new runtime with observed applied state
- **AND** actual traffic succeeds before and after reconnect

#### Scenario: Record a supported live DNS update

- **WHEN** known owned settings cause a supported live DNS refresh before review
- **THEN** acceptance records only its coherent positive DNS revision with the same runtime and durable command
- **AND** unchanged transport remains pending until explicit confirmation
- **AND** cancellation preserves that running transport and does not reverse an independently applied DNS update
- **AND** evidence does not attribute the DNS change to confirmation

#### Scenario: Use supported fixture configuration in each mode

- **WHEN** the real reconnect matrix runs four VPN lanes and two additional Proxy lanes
- **THEN** VPN observes initial HTTP DoH, the coherent live change to verified DoT, and verified DoT after explicit transport reconnect
- **AND** Proxy observes fresh HTTP DoH queries and real listener changes before and after reconnect
- **AND** every lane retains cancellation, stale-review, actual-runtime, applied-state and traffic assertions
- **AND** receipts do not claim Proxy custom-CA DoT coverage

#### Scenario: Verify the actual listener owned by VPN

- **WHEN** VPN acceptance observes its running native service
- **THEN** it resolves the actual private listener from a fresh snapshot of the admitted runtime
- **AND** it does not infer that listener from the saved Proxy port or use a no-auth SOCKS handshake
- **AND** supported DNS refresh, cancellation and stale review preserve the same runtime and actual endpoint
- **AND** confirmation requires a new owned runtime, its actual listener and successful VPN DNS and payload traffic
- **AND** if the new address differs, the old address is closed
- **AND** reuse of an ephemeral address cannot replace positive evidence of the new runtime generation
- **AND** Proxy retains configured A/B/C port and supported SOCKS checks

### Requirement: REQ-LDR-VERIFY — Keep validation and publication evidence distinct

The change MUST record focused host tests, normal/reduced/off presentation, real TalkBack, RTL/font2, actual reconnect network evidence, exact APK provenance and hosted CI separately. It MUST preserve privacy, service admission and quality gates. All new text MUST cover ten locales. Main integration MUST follow rebased combined checks and authorized ordinary push.

#### Scenario: Publish accepted work

- **WHEN** the change is integrated and pushed
- **THEN** exact-source local and device evidence and exact-published-SHA CI are recorded separately
