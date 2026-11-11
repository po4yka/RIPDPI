# TRN-1794384538458949

## Objective

Repair F1 through F9 with one tested and reviewed fix commit per finding.

## Ownership

Use the exact ownership lanes in the linked portfolio and design. The
coordinator alone updates task/spec states and generated contract snapshots.

## Execution

- [ ] TRN-1794384922559287 F1 authenticate WARP readiness and report peer failure with native regressions #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794384940808206 F2 carry explicit VPN protection policy through WARP runtime and probes #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794384955046919 F3 select current-network endpoints independently of account refresh #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794384974171806 F4 normalize provider endpoint addresses in Kotlin and Rust #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794384989760526 F5 preserve truthful server account state across local license changes #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794385010176922 F6 parse GET refresh without a returned token and redact provider errors #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794385033249122 F7 validate complete Zero Trust imports and replace stale endpoints #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794385053055273 F8 expose explicit consumer registration and ready-profile import #bug !high @item:TRN-1794384538458949
- [ ] TRN-1794385072923375 Review and validate the combined change before main integration and push #bug !high @item:TRN-1794384538458949

## Verification

Use focused native and Kotlin regression suites, staticAnalysis, locale lint,
ci-gates, locked metadata, task-check, and emulator/UI checks where available.
Keep each evidence category separate in verification.md.

## Additional finding from the native gate

- [ ] TRN-1794395789446963 F9 keep endpoint probe packet buffers off worker stacks with a bounded-stack loopback regression #bug !high @item:TRN-1794384538458949
