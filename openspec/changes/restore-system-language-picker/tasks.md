# UIX-1790432109043744: Restore system language in the picker

## Objective

The user can return to the device language from the app language picker.

## Ownership

The UI audit agent owns `LanguagePickerSheet.kt`, its direct test, and the new
app string in all ten locale sets. Locale sets are a serialized shared-file lane.

## Execution

- [x] UIX-1790432252771292 Add a failing picker regression test for clearing an app locale override #bug @item:UIX-1790432109043744
- [x] UIX-1790432254802374 Add the device-language row and translated label in every app locale #bug @item:UIX-1790432109043744
- [x] UIX-1790432257011846 Run focused tests, app source checks, and app/service lint parity #bug @item:UIX-1790432109043744

## Verification

Run a focused regression test, app source checks, and
`./gradlew :app:lintGithubFullDebug :core:service:lintDebug`.
