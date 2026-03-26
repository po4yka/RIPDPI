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
