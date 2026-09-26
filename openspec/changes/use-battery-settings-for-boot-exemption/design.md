# Design: Battery settings guidance

Task ID: `AND-1790434316820665`

## Decision

Remove `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from the ordered intent
candidates. Keep vendor autostart screens first, then
`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, then app details. The existing
launch loop already catches an unavailable activity and tries the next intent.
The Start on boot toggle continues to emit guidance only after user opt-in.

No direct exemption permission is added. Android documents that most apps can
open the general settings screen, whereas the direct exemption request needs
both a specific permission and a qualifying core-use case.

## Verification

Test the candidate list and existing opt-in effect, then run app lint.
