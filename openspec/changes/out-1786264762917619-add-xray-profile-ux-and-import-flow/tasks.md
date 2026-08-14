# OUT-1786264762917619: Add Xray profile UX and import flow

## Objective

Add Xray profile UX and import flow

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762918608 Mode Editor can select Xray-backed VPN mode separately from native RIPDPI direct/proxy modes. — XrayServiceModeOption (:core:data:runtime-state) flattens provider×mode into the mutually-exclusive picker set; XrayProviderSelection (:app) re… #feature @item:OUT-1786264762917619
- [x] OUT-1786264762918726 Import supports at least the first approved share/config shapes and fails closed on unsupported or unsafe fields. — XrayImportParser (:core:data:catalog) parses vless:// REALITY/XHTTP links and raw config JSON, rejecting unsupported transp… #feature @item:OUT-1786264762917619
- [x] OUT-1786264762918259 Validation errors are actionable but redact credentials and endpoints. — import errors return REDACTED, jargon-free messages; verified by XrayImportParserTest (offline) and the redaction regression suite #feature @item:OUT-1786264762917619
- [ ] OUT-1786264762918402 Onboarding can validate an Xray profile as the chosen mode before finish. — the reusable validation surface (XrayProfileImportViewModel, XrayCapability) exists and is wired for onboarding reuse, but the onboarding-to-finish flow is exercis… #feature @item:OUT-1786264762917619
- [ ] OUT-1786264762918551 Compose/UI tests cover selection, validation failure, and successful imported-profile state. — XrayProfileImportScreenTest / XrayProfileImportViewModelTest are authored and were exercised to green during development, but the final :app:tes… #feature @item:OUT-1786264762917619

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
