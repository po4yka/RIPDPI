// Main template for RIPDPI user guide PDF.
// Reads guide-data.json, renders title page, TOC, and annotated content pages.

#import "theme.typ": resolve-theme, header-strip, render-description
#import "annotations.typ": draw-annotation

// ---------------------------------------------------------------------------
// Data loading
// ---------------------------------------------------------------------------

#let data = json(sys.inputs.at("data-path"))
#let theme = resolve-theme(data.theme)
#let audit = data.at("audit", default: (:))
#let audit-total = audit.at("total", default: 0)
#let audit-coverage-total = audit.at("coverage_total", default: audit-total)
#let audit-reachable = audit.at("reachable", default: 0)
#let audit-failed = audit.at("failed", default: 0)
#let audit-excluded = audit.at("excluded_count", default: 0)

// ---------------------------------------------------------------------------
// Document settings
// ---------------------------------------------------------------------------

#set document(title: data.title, author: "RIPDPI")

#set page(
  paper: "a4",
  margin: (top: 12mm, bottom: 16mm, left: 16mm, right: 16mm),
)

#set text(
  font: ("Helvetica Neue", "Helvetica", "Arial"),
  size: 11pt,
  fill: theme.text,
)

// Footer on all pages except the first
#set page(
  footer: context {
    if counter(page).get().first() > 1 {
      set align(center)
      set text(8pt, fill: theme.muted, style: "italic")
      [Page #counter(page).display() \/ #counter(page).final().first()]
    }
  },
)

// Heading styling (used for TOC entries)
#show heading.where(level: 1): it => {
  // Headings are rendered via header-strip, hide the default rendering
  // but keep them visible to outline()
  hide(it)
  v(-1em) // Collapse hidden heading space
}

// ---------------------------------------------------------------------------
// Title page
// ---------------------------------------------------------------------------

#place(top + left, dx: -16mm, dy: -12mm,
  rect(width: 230mm, height: 64mm, fill: theme.primary),
)

#v(8mm)
#align(left, text(fill: white, weight: "bold", size: 30pt)[#data.title])

#if data.at("subtitle", default: "") != "" {
  v(2mm)
  align(left, text(fill: rgb("#D9D9D9"), size: 13pt)[#data.subtitle])
}

#v(18mm)

#line(start: (0mm, 0mm), end: (178mm, 0mm), stroke: 1pt + rgb("#111111"))

#v(6mm)
#align(left, text(fill: theme.muted, size: 11pt)[Generated #data.generated_date])

#v(8mm)

#grid(
  columns: (1fr, 1fr, 1fr, 1fr),
  gutter: 5mm,
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 9pt)[
    #text(fill: theme.muted, size: 9pt)[Captured screens]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 22pt)[#audit-total]
  ],
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 9pt)[
    #text(fill: theme.muted, size: 9pt)[Reachable states]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 22pt)[#audit-reachable]
  ],
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 9pt)[
    #text(fill: theme.muted, size: 9pt)[Review flags]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 22pt)[#audit-failed]
  ],
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 9pt)[
    #text(fill: theme.muted, size: 9pt)[Excluded routes]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 22pt)[#audit-excluded]
  ],
)

#v(6mm)

#grid(
  columns: (0.95fr, 1.05fr),
  gutter: 8mm,
  [
    #text(weight: "bold", size: 13pt)[Audit package]
    #v(3mm)
    #text(fill: theme.text, size: 10.5pt)[This report combines captured device screenshots, accessibility-tree reachability checks, route/state metadata, and the generated Mermaid user-flow source for the current RIPDPI UI.]
    #v(5mm)
    #text(fill: theme.muted, size: 9pt)[Scope]
    #v(2mm)
    #text(fill: theme.text, size: 10pt)[Onboarding, home connection states, configuration, diagnostics, settings, route/profile editors, and security-gated surfaces.]
  ],
  {
    let cover-page = if data.pages.len() > 0 { data.pages.first() } else { none }
    if cover-page != none {
      box(
        width: 64mm,
        stroke: 0.6pt + rgb("#D9D9D9"),
        inset: 0pt,
        clip: true,
        image(cover-page.screenshot, width: 100%),
      )
    }
  },
)

// ---------------------------------------------------------------------------
// Table of contents
// ---------------------------------------------------------------------------

#pagebreak()

// TOC header
#place(top + left, dx: -16mm, dy: -12mm,
  rect(width: 230mm, height: 13mm, fill: theme.primary),
)
#v(-3mm)
#text(fill: white, weight: "bold", size: 14pt)[#h(0mm) Contents]
#v(10mm)

#outline(title: none, indent: 0pt)

// ---------------------------------------------------------------------------
// Audit summary
// ---------------------------------------------------------------------------

#pagebreak()

#place(top + left, dx: -16mm, dy: -12mm,
  rect(width: 230mm, height: 13mm, fill: theme.primary),
)
#v(-3mm)
#text(fill: white, weight: "bold", size: 14pt)[#h(0mm) UI Audit Summary]
#v(10mm)

#grid(
  columns: (1fr, 1fr, 1fr, 1fr),
  gutter: 6mm,
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Route coverage]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 20pt)[#audit-coverage-total]
  ],
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Reachable]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 20pt)[#audit-reachable]
  ],
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Needs review]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 20pt)[#audit-failed]
  ],
  box(fill: rgb("#F7F7F7"), stroke: 0.6pt + rgb("#D9D9D9"), radius: 3pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Excluded]
    #linebreak()
    #text(fill: theme.text, weight: "bold", size: 20pt)[#audit-excluded]
  ],
)

#v(6mm)

#for page in audit.at("pages", default: ()) {
  let ok = page.at("reachable", default: false)
  let status-color = if ok { theme.text } else { theme.muted }
  block(
    width: 100%,
    below: 4pt,
    fill: if ok { rgb("#FFFFFF") } else { rgb("#F7F7F7") },
    stroke: 0.5pt + rgb("#D9D9D9"),
    radius: 3pt,
    inset: 7pt,
    {
      text(fill: status-color, weight: "bold", size: 10pt)[#if ok { "REACHABLE" } else { "CHECK" }]
      h(4pt)
      text(weight: "bold")[#page.page_id]
      h(4pt)
      text(fill: theme.muted, size: 9pt)[route: #page.route, root: #page.expected_root]
      linebreak()
      text(fill: theme.muted, size: 9pt)[nodes: #page.node_count, clickable: #page.clickable_count, scrollable: #page.scrollable_count]
      if page.at("missing_elements", default: ()).len() > 0 {
        linebreak()
        text(fill: theme.muted, size: 9pt)[missing: #page.missing_elements.join(", ")]
      }
      if page.at("error", default: none) != none {
        linebreak()
        text(fill: theme.muted, size: 9pt)[error: #page.error]
      }
    },
  )
}

#if audit.at("exclusions", default: ()).len() > 0 {
  v(7mm)
  text(weight: "bold", size: 13pt)[Excluded routes and prerequisites]
  v(3mm)
  for exclusion in audit.at("exclusions", default: ()) {
    block(
      width: 100%,
      below: 4pt,
      fill: rgb("#F7F7F7"),
      stroke: 0.5pt + rgb("#D9D9D9"),
      radius: 3pt,
      inset: 7pt,
      {
        text(weight: "bold", size: 10pt)[#exclusion.route]
        linebreak()
        text(fill: theme.muted, size: 9pt)[prerequisite: #exclusion.prerequisite]
        linebreak()
        text(fill: theme.muted, size: 9pt)[reason: #exclusion.reason]
      },
    )
  }
}

// ---------------------------------------------------------------------------
// Mermaid user flow
// ---------------------------------------------------------------------------

#pagebreak()

#place(top + left, dx: -16mm, dy: -12mm,
  rect(width: 230mm, height: 13mm, fill: theme.primary),
)
#v(-3mm)
#text(fill: white, weight: "bold", size: 14pt)[#h(0mm) #data.at("flow_title", default: "Current user flow")]
#v(10mm)

#text(fill: theme.muted, size: 10pt)[Rendered from the generated Mermaid source at #data.mermaid.path.]
#v(5mm)
#for (idx, section) in data.mermaid.at("sections", default: ()).enumerate() {
  if idx > 0 {
    pagebreak()
    place(top + left, dx: -16mm, dy: -12mm,
      rect(width: 230mm, height: 13mm, fill: theme.primary),
    )
    v(-3mm)
    text(fill: white, weight: "bold", size: 14pt)[#h(0mm) #data.at("flow_title", default: "Current user flow")]
    v(10mm)
  }
  text(weight: "bold", size: 13pt)[#section.title]
  linebreak()
  text(fill: theme.muted, size: 9pt)[#section.subtitle]
  v(3mm)
  box(
    width: 100%,
    stroke: 0.6pt + rgb("#D9D9D9"),
    inset: 0pt,
    clip: true,
    image(section.path, width: 100%, height: 185mm, fit: "contain"),
  )
  v(7mm)
}

// ---------------------------------------------------------------------------
// Content pages
// ---------------------------------------------------------------------------

#let display-width = 178mm // A4 minus margins
#let paired-shot-width = 84mm

#let render-screenshot-shot(page, shot, shot-width, theme) = {
  let screenshot-path = shot.path
  let px-w = shot.pixel_width
  let px-h = shot.pixel_height
  let display-height = shot-width * (px-h / px-w)
  let max-img-height = 185mm
  let (final-w, final-h) = if display-height > max-img-height {
    (max-img-height * (px-w / px-h), max-img-height)
  } else {
    (shot-width, display-height)
  }
  align(center, [
    #text(fill: theme.muted, size: 9pt, weight: "bold")[#shot.label]
    #v(2mm)
    #box(width: final-w, height: final-h, {
      place(
        box(
          width: final-w,
          height: final-h,
          stroke: 0.6pt + rgb("#CFCFCF"),
          clip: true,
          {
            image(screenshot-path, width: 100%, height: 100%)
            let annotations = page.at("annotations", default: ())
            for (i, ann) in annotations.enumerate() {
              draw-annotation(ann, i + 1, theme.primary, final-w, final-h)
            }
          },
        ),
      )
    })
  ])
}

#for (idx, page) in data.pages.enumerate() {
  pagebreak()

  let step-num = idx + 1

  // Section heading (hidden, consumed by outline)
  [= #page.title]

  // Header strip
  header-strip(page.title, step-num, theme)

  v(4mm)

  // Theme comparison screenshots with annotation overlays
  let screenshots = page.at("screenshots", default: ())
  if screenshots.len() >= 2 {
    grid(
      columns: (1fr, 1fr),
      gutter: 6mm,
      render-screenshot-shot(page, screenshots.at(0), paired-shot-width, theme),
      render-screenshot-shot(page, screenshots.at(1), paired-shot-width, theme),
    )
  } else if screenshots.len() == 1 {
    align(center, render-screenshot-shot(page, screenshots.at(0), display-width, theme))
  }

  v(4mm)

  // Description
  let desc = page.at("description", default: "")
  if desc != "" {
    render-description(desc, theme)
  }
}
