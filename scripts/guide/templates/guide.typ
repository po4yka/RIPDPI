// Main template for RIPDPI user guide PDF.
// Reads guide-data.json, renders title page, TOC, and annotated content pages.

#import "theme.typ": resolve-theme, header-strip, render-description
#import "annotations.typ": draw-annotation

// ---------------------------------------------------------------------------
// Data loading
// ---------------------------------------------------------------------------

#let data = json(sys.inputs.at("data-path"))
#let theme = resolve-theme(data.theme)

// ---------------------------------------------------------------------------
// Document settings
// ---------------------------------------------------------------------------

#set document(title: data.title, author: "RIPDPI")

#set page(
  paper: "a4",
  margin: (top: 10mm, bottom: 20mm, left: 20mm, right: 20mm),
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

// Green header bar
#place(top + left, dx: -20mm, dy: -10mm,
  rect(width: 250mm, height: 50mm, fill: theme.primary),
)

#v(6mm)
#align(center, text(fill: white, weight: "bold", size: 32pt)[#data.title])

#if data.at("subtitle", default: "") != "" {
  v(2mm)
  align(center, text(fill: white, size: 14pt)[#data.subtitle])
}

#v(10mm)

// Accent line
#line(start: (0mm, 0mm), end: (170mm, 0mm), stroke: 1pt + theme.accent)

#v(6mm)
#align(center, text(fill: theme.muted, size: 12pt)[Generated #data.generated_date])

// ---------------------------------------------------------------------------
// Table of contents
// ---------------------------------------------------------------------------

#pagebreak()

// TOC header
#place(top + left, dx: -20mm, dy: -10mm,
  rect(width: 250mm, height: 14mm, fill: theme.primary),
)
#v(-2mm)
#text(fill: white, weight: "bold", size: 14pt)[#h(0mm) Contents]
#v(10mm)

#outline(title: none, indent: 0pt)

// ---------------------------------------------------------------------------
// Audit summary
// ---------------------------------------------------------------------------

#pagebreak()

#place(top + left, dx: -20mm, dy: -10mm,
  rect(width: 250mm, height: 14mm, fill: theme.primary),
)
#v(-2mm)
#text(fill: white, weight: "bold", size: 14pt)[#h(0mm) UI Audit Summary]
#v(10mm)

#let audit = data.at("audit", default: (:))
#let audit-total = audit.at("total", default: 0)
#let audit-reachable = audit.at("reachable", default: 0)
#let audit-failed = audit.at("failed", default: 0)

#grid(
  columns: (1fr, 1fr, 1fr),
  gutter: 6mm,
  box(fill: rgb("#E8F5E9"), radius: 4pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Screens]
    #linebreak()
    #text(fill: theme.primary, weight: "bold", size: 20pt)[#audit-total]
  ],
  box(fill: rgb("#E8F5E9"), radius: 4pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Reachable]
    #linebreak()
    #text(fill: theme.primary, weight: "bold", size: 20pt)[#audit-reachable]
  ],
  box(fill: rgb("#FFF3E0"), radius: 4pt, inset: 8pt)[
    #text(fill: theme.muted, size: 9pt)[Needs review]
    #linebreak()
    #text(fill: theme.accent, weight: "bold", size: 20pt)[#audit-failed]
  ],
)

#v(6mm)

#for page in audit.at("pages", default: ()) {
  let ok = page.at("reachable", default: false)
  let status-color = if ok { theme.primary } else { theme.accent }
  block(
    width: 100%,
    below: 4pt,
    fill: if ok { rgb("#F6FBF6") } else { rgb("#FFF8F0") },
    stroke: 0.5pt + luma(220),
    radius: 4pt,
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
        text(fill: theme.accent, size: 9pt)[missing: #page.missing_elements.join(", ")]
      }
      if page.at("error", default: none) != none {
        linebreak()
        text(fill: theme.accent, size: 9pt)[error: #page.error]
      }
    },
  )
}

// ---------------------------------------------------------------------------
// Mermaid user flow
// ---------------------------------------------------------------------------

#pagebreak()

#place(top + left, dx: -20mm, dy: -10mm,
  rect(width: 250mm, height: 14mm, fill: theme.primary),
)
#v(-2mm)
#text(fill: white, weight: "bold", size: 14pt)[#h(0mm) #data.at("flow_title", default: "Current user flow")]
#v(10mm)

#text(fill: theme.muted, size: 10pt)[Mermaid source is also written to #data.mermaid.path.]
#v(4mm)
#raw(data.mermaid.code, lang: "mermaid")

// ---------------------------------------------------------------------------
// Content pages
// ---------------------------------------------------------------------------

#let display-width = 170mm // A4 minus margins

#for (idx, page) in data.pages.enumerate() {
  pagebreak()

  let step-num = idx + 1

  // Section heading (hidden, consumed by outline)
  [= #page.title]

  // Header strip
  header-strip(page.title, step-num, theme)

  v(4mm)

  // Screenshot with annotation overlays
  let screenshot-path = page.screenshot
  let px-w = page.pixel_width
  let px-h = page.pixel_height
  let display-height = display-width * (px-h / px-w)

  // Clamp height to leave room for description
  let max-img-height = 195mm
  let (final-w, final-h) = if display-height > max-img-height {
    (max-img-height * (px-w / px-h), max-img-height)
  } else {
    (display-width, display-height)
  }

  align(center, {
    // Outer container with room for shadow
    box(width: final-w + 3pt, height: final-h + 3pt, {
      // Shadow layer (offset behind)
      place(dx: 3pt, dy: 3pt,
        rect(width: final-w, height: final-h, fill: luma(210), radius: 1pt),
      )
      // Image with border and annotation overlays
      place(
        box(
          width: final-w,
          height: final-h,
          stroke: 0.5pt + luma(200),
          clip: true,
          {
            image(screenshot-path, width: 100%, height: 100%)
            let annotations = page.at("annotations", default: ())
            for (i, ann) in annotations.enumerate() {
              draw-annotation(ann, i + 1, theme.accent, final-w, final-h)
            }
          },
        ),
      )
    })
  })

  v(4mm)

  // Description
  let desc = page.at("description", default: "")
  if desc != "" {
    render-description(desc, theme)
  }
}
