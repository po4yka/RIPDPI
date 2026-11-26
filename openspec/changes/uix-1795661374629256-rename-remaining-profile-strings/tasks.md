# UIX-1795661374629256: Use the server term in all remaining strings

## Objective

Every UI string that names a saved relay or AmneziaWG entry calls it a server, and every string that names DPI settings calls it a preset, in all ten locales.

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md`.
- [ ] UIX-1795661387364302 Each remaining English string that contains "profile" is classified as server, preset, or other, and the classification is recorded in the change design. #feature @item:UIX-1795661374629256
- [ ] UIX-1795661387832510 Strings classified as server or preset use that term in all ten locales; strings classified as other keep their wording. #feature @item:UIX-1795661374629256
- [ ] UIX-1795661388329874 `config/i18n/translatable-keys.txt` is regenerated and `:app:lintGithubDebug` and `:core:service:lintDebug` pass. #feature @item:UIX-1795661374629256
- [ ] UIX-1795661388823900 Roborazzi baselines that change because of the new wording are re-recorded under the golden bless rules. #feature @item:UIX-1795661374629256
