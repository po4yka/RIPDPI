# OUT-1786264762917694: Surface Xray diagnostics and telemetry

## Objective

Surface Xray diagnostics and telemetry

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762919315 Home connection stages identify Xray provider readiness and provider failures distinctly from tunnel failures. — XrayConnectionStage (Validating → StartingEngine → ListenerReady → ProbingOutbound → Connected, with a ProviderFailed branch)… #feature @item:OUT-1786264762917694
- [x] OUT-1786264762919585 Diagnostics can run a provider-path check through the active Xray mode (wired + CI-tested with fakes; live device run still OPEN). — XrayProviderDiagnosticsProbeRunner (:core:service) runs Version + ListenerReadiness + WrapperPing in-proce… #feature @item:OUT-1786264762917694
- [x] OUT-1786264762919696 Export/share summaries redact profile credentials and live endpoints. — XrayProviderTelemetrySummaries routes every endpoint/secret through XrayProfileRedactor; verified by XrayProviderDiagnosticsTest (offline) #feature @item:OUT-1786264762917694
- [x] OUT-1786264762919411 Xray API/stat probing is used only when enabled safely for the Android runtime topology. — StatApi probe kind is typed and flagged child-process-only (never in-process for the Android TUN topology); the safe set is Version/WrapperPing/List… #feature @item:OUT-1786264762917694
- [x] OUT-1786264762919159 Regression fixtures cover provider healthy, config invalid, protect failure, DNS-loop suspected, and outbound unreachable states. — XrayProviderDiagnosticsFixtures (all five states) asserted by XrayProviderDiagnosticsTest (15 tests green o… #feature @item:OUT-1786264762917694

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
