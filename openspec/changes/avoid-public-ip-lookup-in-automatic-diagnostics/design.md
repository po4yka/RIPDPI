# Design

Task ID: `DGN-1790434142197991`

Pass the existing `DiagnosticsScanOrigin` to primary pre-scan snapshot capture. Store the resulting public-IP permission in `PreparedDiagnosticsScan` and inherit it when preparing a DNS-corrected re-probe. Use the stored permission for post-scan capture. Re-probe pre-scan capture already disables public-IP lookup. The provider skips its resolver when the flag is false. No storage schema or UI change is needed. Recording-provider tests cover automatic, manual, and automatic re-probe captures.
