## Orchestration Failure Harness

`ControllableNetworkHandoverMonitor` gives diagnostics orchestration tests deterministic network-swap input without wiring inline `MutableSharedFlow` instances in every test.

Use it for home-analysis and other staged runs that need to inject specific handover events at exact points in the orchestration timeline.
