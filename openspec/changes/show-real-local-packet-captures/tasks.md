# DGN-1790329640901759

## Objective

Show completed local captures and packets read from the file selected by the user.

## Ownership

PCAP agent owns `app` PCAP routes, presentation, navigation, unit tests, and ten locale string resources in its worktree. Coordinator owns generated `docs/tasks/board.md` and integration.

## Execution

- [x] DGN-1790329739827433 Connect completed capture list and safe selected-file viewer with tests and app gates #bug !high @item:DGN-1790329640901759

## Verification

Run `:app:testGithubFullDebugUnitTest` for `PcapCaptureContentTest`, app Kotlin compile, locale lint, and `staticAnalysis`. Confirm device behavior separately.
