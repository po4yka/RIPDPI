// Theme utilities for RIPDPI user guide PDF generation.
// Provides color parsing, header strips, callout boxes, and step badges.

/// Parse a hex color string like "#1B5E20" into a Typst rgb color.
#let parse-color(hex-str) = {
  rgb(hex-str)
}

/// Resolve all theme colors from the JSON theme dict.
#let resolve-theme(raw) = {
  let keys = ("primary", "accent", "text", "muted", "background")
  let result = (:)
  for key in keys {
    result.insert(key, parse-color(raw.at(key)))
  }
  result
}

/// Render a step badge: filled circle with centered white number.
#let step-badge(number, color, size: 10pt) = {
  box(
    width: size * 2,
    height: size * 2,
    radius: size,
    fill: color,
    align(center + horizon, text(fill: white, weight: "bold", size: size * 0.9)[#number]),
  )
}

/// Render a page header strip with step number and title.
#let header-strip(title, step-num, theme) = {
  block(
    width: 100%,
    height: 14mm,
    fill: theme.primary,
    inset: (left: 10mm, right: 10mm),
    {
      set align(left + horizon)
      box(inset: (right: 6pt), step-badge(step-num, theme.accent, size: 5.5pt))
      text(fill: white, weight: "bold", size: 14pt)[#title]
    },
  )
}

/// Render a callout box (NOTE: / TIP: style).
#let callout-box(prefix, body, theme) = {
  block(
    width: 100%,
    fill: rgb("#FFF3E0"),
    stroke: (left: 3pt + theme.accent, rest: none),
    radius: (right: 4pt),
    inset: 10pt,
    {
      text(fill: theme.accent, weight: "bold", size: 10pt)[#prefix:]
      linebreak()
      text(fill: theme.text, size: 10pt)[#body]
    },
  )
}

/// Parse description text into content, detecting NOTE:/TIP: callout paragraphs.
#let render-description(desc-text, theme) = {
  let paragraphs = desc-text.split(regex("\n\n+"))
  for para in paragraphs {
    let stripped = para.trim()
    if stripped == "" { continue }
    let upper = upper(stripped)
    if upper.starts-with("NOTE:") or upper.starts-with("TIP:") {
      let colon-idx = stripped.position(":")
      let prefix = stripped.slice(0, colon-idx)
      let body = stripped.slice(colon-idx + 1).trim()
      callout-box(prefix, body, theme)
    } else {
      block(
        width: 100%,
        below: 4pt,
        text(fill: theme.text, size: 11pt)[#stripped],
      )
    }
  }
}
