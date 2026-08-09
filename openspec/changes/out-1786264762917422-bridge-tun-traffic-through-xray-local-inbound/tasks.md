# OUT-1786264762917422: Finish and verify Xray VPN provider end to end

## Objective

Turn the landed Xray orchestration into a reproducible selectable provider proven with a real artifact and device egress.

## Ownership

- Xray provider onboarding, runtime orchestration, diagnostics, and tests
- exact gomobile artifact and physical-device evidence lane

## Execution

- [x] OUT-1786264762918956 Select Xray as the VPN tunnel upstream #feature !high @item:OUT-1786264762917422
- [x] OUT-1786264762918267 Protect Xray outbound sockets and preserve single-owner DNS #feature !high @item:OUT-1786264762917422
- [x] OUT-1786264762918785 Preserve tunnel telemetry through the Xray upstream #feature !high @item:OUT-1786264762917422
- [x] OUT-1786264762918495 Restart Xray and tunnel in safe order after network handover #feature !high @item:OUT-1786264762917422
- [ ] OUT-1786264762918700 Prove real Xray outbound egress on a physical device #feature !high @item:OUT-1786264762917422 @blocked_by:OUT-1786272743763099
- [ ] OUT-1786272743760717 Validate imported and editor-created Xray profiles through onboarding and durable provider selection #feature !high @item:OUT-1786264762917422
- [ ] OUT-1786272743763099 Build and verify the exact gomobile libXray artifact in a clean checkout #feature !high @item:OUT-1786264762917422 @blocked_by:OUT-1786272743760717
- [ ] OUT-1786272743765628 Prove physical-device Xray egress, protect-denial handling, lifecycle recovery, and live telemetry #feature !high @item:OUT-1786264762917422 @blocked_by:OUT-1786272743763099

## Verification

- current Compose and engine-api provider tests
- exact gomobile artifact inspection
- physical-device egress, DNS, socket protection, restart, and telemetry receipt
