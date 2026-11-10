# RLY-1794300683762457

## Objective

Correct all six confirmed Cloudflare publish defects.

## Ownership

Use the exact path lanes recorded in docs/tasks/issues/correct-cloudflare-publish-audit.md. The coordinator owns all planning and combined validation changes.

## Execution

- [ ] RLY-1794300805274937 Fix streaming POST lifetime and verify delayed upload cancellation #bug !high @item:RLY-1794300683762457
- [ ] RLY-1794300823103520 Sanitize credential parser failures and verify safe exception chains #bug !high @item:RLY-1794300683762457
- [ ] RLY-1794300828691953 Match xHTTP paths with queries and verify session routing #bug !high @item:RLY-1794300683762457
- [ ] RLY-1794300834987199 Reject non-TCP VLESS commands before socket creation #bug !high @item:RLY-1794300683762457
- [ ] RLY-1794300841013581 Accept IPv6 loopback and validate origin port bounds #bug !high @item:RLY-1794300683762457
- [ ] RLY-1794300844455627 Require publish identity in editor and verify save rejection #bug !high @item:RLY-1794300683762457
- [ ] RLY-1794300851054581 Review combined fixes and run integration source gates #bug !high @item:RLY-1794300683762457

## Verification

Run origin tests and clippy with --locked, focused Kotlin tests and affected lint with skipNativeBuild, just ci-gates, architecture health, locked Cargo metadata, and independent reviews.
