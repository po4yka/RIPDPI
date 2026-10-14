# App-wide readability audit

Task: `UIX-1791793531264679`. Change: `material3-readability`.

The first stage raised shared typography and settings icons. This pass addresses the remaining causes of small symbols and compressed text. The baseline for this audit is `15b634db6`.

| Area | Observed defect | Correction |
|---|---|---|
| Shared actions and feedback | Compact action glyphs use 16dp. Feedback symbols use 16dp in a 28dp badge. Some actions always ellipsize. | Use 24dp action glyphs, 32dp leading symbols in 40dp badges, and complete wrapping labels. |
| Chips and relay choices | Fixed 32dp height and equal-width columns compress localized labels. Selection changes label size. | Use intrinsic widths, natural heights, wrapping groups, and stable label size. |
| Bottom navigation | Single-line clipped labels lose destination names at large font scale. | Measure full labels and adapt the column count without removing destinations. |
| DNS and localized forms | Selected badges, switches, status, and fact values compete with title width. | Reserve control width; move badges and facts below titles when needed. |
| Recovery actions | The second OOM action reads `Vie...` even at standard font scale. | Give each action enough width and allow complete labels. |
| Throughput cards | Background canvases and their labels are separate siblings. The selected range text falls outside its pill. | Put background and text in the same surface; let each stat cell grow. |
| Import and overview grids | Two narrow columns compress descriptions and values. | Adapt to one column where width or font scale requires it. |
| Long localized words and app-bar titles | A fixed two-line limit hides large titles; words wider than the full line break without a visible hyphen. An empty controls row wastes height on screens with no actions. | Give large titles a separate full-width area without a line limit; add a controls row only when controls exist; use system hyphenation for oversize sans words. |
| Graphs and data headers | Controls compete with full titles. Fixed diagram geometry scales text down. | Wrap header controls; retain readable diagram nodes in a scrollable canvas. |
| History sections | Peer navigation uses a cramped chip row; clear action lacks a sufficient target. | Use scrollable Material 3 tabs and a 48dp clear action. |

The original OOM, throughput, and import Roborazzi images were inspected with hashes under the local `build/reports/readability-audit/before` directory. A separate host cleanup removed these disposable reports on 2026-10-13. The historical results below identify their source commits; current emulator evidence is stored outside the cleaned build directories.

## Design contract

The current Compose theme and verified Roborazzi images define the implementation. The RDS HTML deck remains read-only. Wrapping choices, full action labels, scrolling diagrams, and adaptive bottom navigation are deliberate accessibility corrections to its compact layouts. `DESIGN.md` and `docs/design-system.md` record the revised rules.

Small inline verdict markers, chart ticks, and dense monospace metadata retain their semantic roles. The app-wide correction applies to standalone actions, leading symbols, important facts, and layouts that lose visible content.

## Validation record

Final source review, combined UI gates, golden verification, and fresh light, dark, and 2x font captures are recorded in `openspec/changes/material3-readability/verification.md`. Host-rendered images establish layout evidence; they do not establish device or network acceptance.

## Installed emulator follow-up

An API 37 ARM64 emulator at 411dp, 420dpi, and 200% system font scale exposed additional defects after the host-render pass. Diagnostic session titles and the Russian Local proxy choice could collapse to one glyph per line. Dark launcher previews used a theme input background instead of their matching adaptive-icon backgrounds. Theme labels, DNS summaries, and history status filters retained English presentation labels. Permission guidance retained the previous language after an in-app language change in the same process.

The follow-up uses measured row budgets, wrapping endpoint choices, existing launcher artwork backgrounds, localized presentation labels, and a locale input for permission presentation. The change verification document tracks the device captures and installed APK identity. Seeded report data proves the diagnostic UI path; it does not prove live network results.

The installed follow-up also caught a shared 1.5x preview zoom that cut five silhouettes at the mask. Only the Clean vector has an internal 0.70 group inset. Preview metadata enlarges that inset artwork and retains the natural scale of the other five assets. Grid and selected previews use the same metadata; launcher assets and aliases stay unchanged.

History filter choices use a bounded wrapping group. Long localized labels stay inside the card at 200% font scale, including the selected checkmark. Failure and cancellation captions describe the stored status without adding an unsupported cause.

The full strategy report exposed two more competing headers: the audit confidence status formed a one-letter column, and a candidate title ended in an ellipsis. Confidence, winner, and candidate headers now reserve whole-word title width and the complete status width. They stack when that budget exceeds the card; candidate titles have no fixed line cap.

Final installed acceptance covers 88 reviewed API 37 ARM64 captures at 411dp, 420dpi, and 200% font in both themes, including permission language changes without a process restart. The real native APK and installed package have the same hash. See the change verification document for the source, fixture, and runtime receipts.
