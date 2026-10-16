# UIX-1792130566603442

## Objective

Make the connection path, diagnostic result and live log stream clear and usable.

## Ownership

Config screen family and config tests belong to the connection writer.
Diagnostics overview, app projection models and tests belong to the diagnostic
writer. LogsScreen, LogsViewModel and tests belong to the log writer. The
integration writer owns locale resources, shared test tags, device tests and
all planning/task files. Each writer uses a dedicated worktree.

## Execution

- [ ] UIX-1792130696967335 Simplify saved connection summary and profile disclosure with regression tests #feature !high @item:UIX-1792130566603442
- [ ] UIX-1792130697552157 Show one diagnostic outcome with provenance and consistent action tests #feature !high @item:UIX-1792130566603442
- [ ] UIX-1792130698111801 Give live logs one adaptive stream and verify filters and scroll retention #feature !high @item:UIX-1792130566603442
- [ ] UIX-1792130698718422 Provide locale and accessibility coverage and run combined UI gates #feature !high @item:UIX-1792130566603442
- [ ] UIX-1792130699357356 Verify rebuilt APK on a leased emulator and record final review evidence #feature !high @item:UIX-1792130566603442

## Verification

Run reproducing and focused JVM tests, theme tests, staticAnalysis, app/service
locale lint, Full app/test APK assembly, architecture health, locked Cargo
metadata and strict task/OpenSpec gates. Verify the affected Roborazzi family
without recording. Use a leased API 37 emulator for interaction and light/dark,
360dp and 2x font images. Record exact source SHA and evidence in verification.md.
