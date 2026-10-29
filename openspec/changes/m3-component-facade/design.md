## Context

Task ID: `UIX-1793256927624290`. Part of epic `EPC-1793256695505524`; blocked by `UIX-1793256870879296`. Owner decisions of 2026-10-29 apply.

## Goals / Non-Goals

- Goal: Build every shared RipDpi component on its Material 3 counterpart behind an unchanged facade, and delete the RDS layers that Material 3 now covers.
- Non-goal: network, service, native, JNI, or storage behavior changes.

## Decisions

- Mapping: RipDpiButton to Button, FilledTonalButton, OutlinedButton, or TextButton; RipDpiChip to FilterChip or AssistChip; RipDpiDropdown to ExposedDropdownMenuBox; SettingsRow to ListItem; RipDpiDialog to AlertDialog; RipDpiBottomSheet to ModalBottomSheet; RipDpiSnackbar to Snackbar.
- WarningBanner has no Material 3 component; build it from Card and status container roles.
- Use ButtonGroup and SplitButton only where a screen has paired or split actions.

## Contracts and ownership

- Automation resource IDs and test tags stay stable unless a step names the change.
- Screenshot fixtures change only after owner approval.

## Risks / Trade-offs

- Appium locators depend on test tags; verify the Appium contract suite.
- Material 3 defaults can lower density; keep the 48dp target and readable type from the theme foundation.
