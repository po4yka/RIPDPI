# Change: Correct audited Arabic localization

Task ID: `UIX-1792040423920590`

## Why

The Arabic audit at 4e132e8c confirms 86 active resource defects and eight shared Home presentation groups. A sensitive-settings warning remains English. Other text changes the meaning of cloud sync, domain suffixes, network ports and diagnostics. Home generates English text outside locale resources.

## What Changes

- Correct all 86 audited Arabic resource entries and related source ambiguity where required.
- Resolve generated Home text through the selected app language without changing persisted evidence or exports.
- Keep new keys complete in all ten locales and test retained EN to Arabic to EN presentation.
- Correct numeric IPv6 rendering in the two configuration endpoint rows after a production-screen reproduction confirmed reversed address order under RTL.

## Capabilities

### New Capabilities

- `arabic-localization-presentation`: accurate Arabic copy and localized Home presentation.

### Modified Capabilities

- None. No breaking contract change.

## Impact

- App resources, presentation and tests, locale metadata and translation export manifest.
- No dependency, core diagnostics model, schema, JNI, service lifecycle, network behavior or raw storage/export change.
- Main integration and push are explicitly authorized. Hosted CI must pass on the published source.
