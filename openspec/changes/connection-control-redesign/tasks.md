# UIX-1791532861060606

## Objective

Separate the home connection action and route information, keep actions visible in every state, and show progress only when evidence supports it. Complete the requested Mobbin MCP research before implementation.

## Ownership

Connection UI owns `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiConnectionActuator*.kt`, `activities/HomeConnectionActuatorUiState.kt`, `activities/MainStateResolvers.kt`, their tests, and the actuator preview scenes. The same writer owns `app/src/main/res/values*/strings_connection_actuator.xml` in all ten locales. No native, service, storage, JNI, or wire-contract file is owned by this change.

## Execution

- [ ] UIX-1791533043597405 Inspect Mobbin MCP connection screens and select the layout #research !high @item:UIX-1791532861060606
- [ ] UIX-1791533044507020 Replace timer driven completion with honest connection status #feature !high @item:UIX-1791532861060606
- [ ] UIX-1791533045357434 Separate the connection action and read only route summary #feature !high @item:UIX-1791532861060606
- [ ] UIX-1791533046236328 Verify accessibility and update all affected locales #feature !high @item:UIX-1791532861060606
- [ ] UIX-1791533047120589 Run behavior gates and inspect adaptive previews #feature !high @item:UIX-1791532861060606
- [ ] UIX-1791533048017458 Review the diff and commit the finished implementation #feature !high @item:UIX-1791532861060606

## Verification

Run the exact component, resolver, theme, locale, static analysis, and preview gates in verification.md. Record device, artifact, and hosted CI evidence separately. No implementation step is complete while the Mobbin reference research remains blocked.
