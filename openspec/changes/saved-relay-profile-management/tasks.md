# RLY-1790329719797199: Make saved relay profiles selectable and editable

## Objective

Every saved VPN relay profile is reachable and can be selected or edited without overwriting another profile.

## Ownership

This worktree owns the Config/Mode Editor UI, relay persistence and coordinator CAS API, related app/core tests, and the serialized locale lane after PCAP edits finish.

## Execution

## Verification

Run targeted `:app:testGithubFullDebugUnitTest` with `-Pripdpi.skipNativeBuild=true`, `:app:lintGithubFullDebug`, and `staticAnalysis`. Device behavior needs a connected Android device.
- [x] RLY-1790329940999261 Guard relay profile identity at validation and persistence boundaries #bug !high @item:RLY-1790329719797199
- [x] RLY-1790329946121394 Expose every saved VPN profile and wire select and edit actions #bug !high @item:RLY-1790329719797199
- [x] RLY-1790329950497065 Run app unit and static analysis gates for relay profiles #bug !high @item:RLY-1790329719797199
