# UIX-1790432223307662: Preserve bypass rule on invalid draft

## Objective

An invalid-only draft leaves the active managed bypass rule unchanged and shows errors without a success confirmation.

## Ownership

This worktree owns the `:app` editor and ViewModel, `:core:data` rule repository, direct tests, and this task's OpenSpec files. No serialized shared file changes.

## Execution

- [x] UIX-1790432541016785 Preserve active bypass rule for invalid-only drafts and verify editor feedback #bug !high @item:UIX-1790432223307662

## Verification

Run focused `:core:data` and `:app` unit tests, affected lint and static checks, architecture health, Cargo metadata, and `./taskctl validate` on the combined branch.
- [x] UIX-1790433772090153 Block Save until the bypass draft has loaded, with delayed-flow regression test #bug !high @item:UIX-1790432223307662
