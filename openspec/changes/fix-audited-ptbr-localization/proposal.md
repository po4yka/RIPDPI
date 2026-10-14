# Change: Correct audited Brazilian Portuguese localization

Task ID: `UIX-1791978914456411`

## Why

The pt-BR audit at af004adb89dbf443be8f190a3cead0e275026b24 found 14 active copy defects and three shared presentation defects. Incorrect cache, retry and prelude descriptions misstate measurements or controls. History can read system-language captions on API 32. Retained UI models can keep the previous language, and Detection History exposes generated English network names.

## What Changes

- Correct all 14 confirmed pt-BR keys, two related DNS captions, the source noise-prelude hint in every locale, and stale glossary metadata.
- Use the selected app language for every affected History caption.
- Refresh retained Diagnostics, History and subscription presentation when the app language changes, including selected details.
- Translate known generated unknown-network summaries at the presentation boundary.
- Preserve raw records, user names, protocol values, selection and sensitive-detail state.

## Capabilities

### New Capabilities

- `ptbr-localization-presentation`: corrected copy and responsive app-language presentation.

### Modified Capabilities

- None. No breaking change is planned.

## Impact

- App locale resources, app presentation sources and tests, glossary and provenance.
- Existing resource keys remain in all ten locales. No dependency, native behavior, storage, wire or service-lifecycle contract changes are planned.
- Local, hosted CI, render and device evidence remain separate. Main integration and push are explicitly authorized by the user.
