# DiagnosticsHome composite run stages

`core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/HomeCompositeStageDefinitions.kt`
is the source of truth for both stage lists below; read it directly
rather than trusting this table once stages are added, removed, or
reordered.

### `HomeCompositeStageSpecs` (full run, 9 stages)

| Stage key | Profile | Notes |
|-----------|---------|-------|
| `automatic_audit` | `automatic-audit` (full_matrix_v1 strategy probe) | `StrategyProbeStageTimeoutMs` (330s); see "Stage timeouts" in SKILL.md section 4 |
| `detection_signals` | `detection-signals` | `DETECTION_SIGNALS` kind; `DetectionStageTimeoutMs` (90s) |
| `default_connectivity` | `default` | Standard connectivity check |
| `ru_throttling` | `ru-throttling` | `ThrottlingStageTimeoutMs` (240s) |
| `ru_circumvention` | `ru-circumvention` | Sensitive-services reachability; `SensitiveServicesStageTimeoutMs` (240s) |
| `dpi_full` | `ru-dpi-full` | Full DPI detection sweep; `DpiFullStageTimeoutMs` (240s) -- **not** `dpi-detector-full` and **not** the 330s strategy-probe budget |
| `path_comparison` | `path-comparison` | IN_PATH mode (proxy vs. direct); `PathComparisonStageTimeoutMs` (180s) |
| `vpn_route_evidence` | `vpn-route-evidence` | Passive VPN-route evidence only; does not start a scan session (`startsScanSession = false`) |
| `dpi_strategy` | `ru-dpi-strategy` | `STRATEGY_PROBE` (full_matrix_v1) scoped to Russian-domain target packs; shares `StrategyProbeStageTimeoutMs` (330s) with `automatic_audit` |

### `QuickScanStageSpecs` (reduced run, 4 stages)

A separate list used for the quick/automatic (non-manual-audit) path:
`automatic_audit`, `detection_signals`, `vpn_route_evidence`,
`dpi_strategy` -- the same specs as above, but `dpi_strategy` uses
`QuickScanStrategyProbeNativeDeadlineMs` (60s) instead of the full 270s
native deadline (see "Stage timeouts" in SKILL.md section 4).
