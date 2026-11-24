# AND-1795444347241489: Exclude privileged Android features from Google Play

## Objective

Deliver the base Play runtime and the shared extended GitHub and F-Droid runtime.

## Ownership

See the portfolio record for source and serialized file ownership.

## Execution

## Verification

Run source validation, Android and Rust tests, debug package checks for all three channels, and the Play smoke tests on a non-root emulator.
- [x] AND-1795444402230111 Separate Android sources and executable assets by distribution #feature !high @item:AND-1795444347241489
- [x] AND-1795444402897115 Separate native privilege code and enforce supported runtime inputs #feature !high @item:AND-1795444347241489
- [x] AND-1795444403544089 Verify debug packages, the non-root Play smoke tests, and the release scanner contract #feature !high @item:AND-1795444347241489
- [x] AND-1795444663235135 Separate generated helper and packet backend assets by distribution #feature !high @item:AND-1795444347241489
