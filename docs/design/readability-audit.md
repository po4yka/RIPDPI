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
| Graphs and data headers | Controls compete with full titles. Fixed diagram geometry scales text down. | Wrap header controls; retain readable diagram nodes in a scrollable canvas. |
| History sections | Peer navigation uses a cramped chip row; clear action lacks a sufficient target. | Use scrollable Material 3 tabs and a 48dp clear action. |

The original OOM, throughput, and import Roborazzi images are retained with hashes under the local `build/reports/readability-audit/before` directory. They show layout defects without relying only on source inspection.

## Design contract

The current Compose theme and verified Roborazzi images define the implementation. The RDS HTML deck remains read-only. Wrapping choices, full action labels, scrolling diagrams, and adaptive bottom navigation are deliberate accessibility corrections to its compact layouts. `DESIGN.md` and `docs/design-system.md` record the revised rules.

Small inline verdict markers, chart ticks, and dense monospace metadata retain their semantic roles. The app-wide correction applies to standalone actions, leading symbols, important facts, and layouts that lose visible content.

## Validation record

Final source review, combined UI gates, golden verification, and fresh light, dark, and 2x font captures are recorded in `openspec/changes/material3-readability/verification.md`. Host-rendered images establish layout evidence; they do not establish device or network acceptance.
