# Change: Implement measured network UX and durable connection controls

Task ID: `EPC-1791124000119505`

## Why

Users need to distinguish direct measurements from the active runtime path, understand available metrics, and see what configuration is actually applied. Profile browsing and export require clear, intentional actions. Timed pause and measured profile selection must deliver real lifecycle behavior rather than visual promises. The approved Mobbin research identifies these seven related product slices.

## What Changes

- Explain scan scope and lifecycle consequences in launch controls and results.
- Explain supported metrics, sample scope, aggregation and evidence freshness.
- Show factual active configuration, unapplied edits and causal recovery guidance.
- Add search to grouped diagnostic and relay profile selection.
- Preview diagnostic exports with explicit redaction and cancellation.
- Add durable timed pause/resume and explicit cancellation.
- Add favorite/recent profiles and measured automatic selection based on actual URL-tests.

## Capabilities

### New Capabilities

- `measured-network-ux`: understandable diagnostic evidence, configuration actions, profile selection and durable timed connection control.

### Modified Capabilities

- None. Existing runtime/probe contracts are consumed; any necessary persistence additions are additive local state.

## Impact

- Kotlin app UI/ViewModels, diagnostics presentation, service controller/lifecycle, and local profile/paused-intent persistence as required.
- All ten locale resource sets and affected UI/unit tests.
- Existing URL-test probes provide measured profile latency; no backend or new production dependency.
- One private TUN kernel-identity JNI addition is required for scope route correlation; the user explicitly approved its reviewed one-symbol baseline update on 2026-10-05. No public serialized wire/protocol change is planned. Persistence and runtime decisions are specified in design.md before implementation.
- The repeated API35 acceptance incident requires preventing no-op refreshes caused by unused direct-DNS underlay inputs. Retain split proxy DNS policy and guards for effective DIRECT rules, together with strict owned-peer negative assertions; do not equate a VPN capability or a retained descriptor with installed routes. This correction gates the still-open export acceptance step.
- Xray acceptance must use its own deterministic DoH resolver. Public/default resolver attempts caused an unrelated producer reset during the positive packet-counter assertion; add only test-peer DoH capability and test settings, retaining production failover and strict assertions.
