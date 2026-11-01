# UIX-1793534441702605: Give cards a tonal hierarchy and one elevation rule

## Objective

Give cards a tonal hierarchy and one elevation rule

## Ownership

UI module paths only. Golden fixtures are a serialized lane with one writer. No protobuf, JNI, or locale change.

## Execution

- [ ] UIX-1793534635643076 Document the card rule in DESIGN.md and docs/design-system.md #feature @item:UIX-1793534441702605
- [ ] UIX-1793534636066544 Apply the rule to the Diagnostics, History, and Home call sites #feature @item:UIX-1793534441702605
- [ ] UIX-1793534636484850 Apply the rule to the Settings, Detection, DNS, and Owned Stack call sites and the remediation card default #feature @item:UIX-1793534441702605
- [ ] UIX-1793534636922469 Re-record Roborazzi goldens with owner approval #feature @item:UIX-1793534441702605
- [ ] UIX-1793534637396420 Capture Home, Diagnostics, and Settings on a device #feature @item:UIX-1793534441702605

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
