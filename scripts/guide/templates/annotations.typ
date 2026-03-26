// Vector annotation overlays for screenshot images.
// All coordinates are normalized 0.0-1.0, resolved against display dimensions.
// Typst cannot multiply length*length, so we work with raw float pt values
// and convert to lengths only at the place() call site.

#import "theme.typ": step-badge

/// Convert a length to its raw pt value for arithmetic.
#let to-pt(l) = l / 1pt

/// Label box: rounded rect with theme primary background and white text.
#let annotation-label(label-text, color) = {
  if label-text == "" or label-text == none { return }
  box(
    fill: color.transparentize(18%),
    radius: 6pt,
    inset: (x: 6pt, y: 3pt),
    text(fill: white, size: 8pt, weight: "medium")[#label-text],
  )
}

/// Draw an arrow annotation: line with arrowhead, step badge at origin, label.
#let draw-arrow(ann, step, accent, img-w, img-h) = {
  let x0 = ann.from.at(0) * img-w
  let y0 = ann.from.at(1) * img-h
  let x1 = ann.to.at(0) * img-w
  let y1 = ann.to.at(1) * img-h

  // Work in float pt values for trig
  let dx-pt = to-pt(x1 - x0)
  let dy-pt = to-pt(y1 - y0)
  let len-pt = calc.sqrt(dx-pt * dx-pt + dy-pt * dy-pt)
  if len-pt == 0 { return }

  let head-size = 8.0  // pt
  let ux = dx-pt / len-pt
  let uy = dy-pt / len-pt

  // Arrow line (shortened to leave room for arrowhead)
  let end-dx = dx-pt - ux * head-size
  let end-dy = dy-pt - uy * head-size
  place(
    dx: x0,
    dy: y0,
    line(
      start: (0pt, 0pt),
      end: (end-dx * 1pt, end-dy * 1pt),
      stroke: 2pt + accent,
    ),
  )

  // Arrowhead (filled triangle)
  let spread = 25deg
  let angle = calc.atan2(dy-pt, dx-pt)
  let x1-pt = to-pt(x1)
  let y1-pt = to-pt(y1)
  let p1-x = (x1-pt - head-size * calc.cos(angle - spread)) * 1pt
  let p1-y = (y1-pt - head-size * calc.sin(angle - spread)) * 1pt
  let p2-x = (x1-pt - head-size * calc.cos(angle + spread)) * 1pt
  let p2-y = (y1-pt - head-size * calc.sin(angle + spread)) * 1pt
  place(
    dx: 0pt,
    dy: 0pt,
    polygon(
      fill: accent,
      (x1, y1),
      (p1-x, p1-y),
      (p2-x, p2-y),
    ),
  )

  // Step badge at arrow origin
  place(
    dx: x0 - 8pt,
    dy: y0 - 20pt,
    step-badge(step, accent, size: 5pt),
  )

  // Label near origin
  if ann.at("label", default: "") != "" {
    place(
      dx: x0 + 4pt,
      dy: y0 - 32pt,
      annotation-label(ann.label, accent),
    )
  }
}

/// Draw a circle annotation: ring outline, step badge, label.
#let draw-circle(ann, step, accent, img-w, img-h) = {
  let cx = ann.center.at(0) * img-w
  let cy = ann.center.at(1) * img-h
  let r = ann.at("radius", default: 0.05) * calc.min(img-w, img-h)
  let d = r * 2

  place(
    dx: cx - r,
    dy: cy - r,
    ellipse(width: d, height: d, fill: none, stroke: 2pt + accent),
  )

  // Step badge at top-right of circle
  place(
    dx: cx + r - 4pt,
    dy: cy - r - 8pt,
    step-badge(step, accent, size: 5pt),
  )

  // Label above circle
  if ann.at("label", default: "") != "" {
    place(
      dx: cx - r,
      dy: cy - r - 22pt,
      annotation-label(ann.label, accent),
    )
  }
}

/// Draw a bracket annotation: vertical line with ticks, badge, label.
#let draw-bracket(ann, step, accent, img-w, img-h) = {
  let y-top = ann.y_range.at(0) * img-h
  let y-bot = ann.y_range.at(1) * img-h
  let tick = 12pt
  let side = ann.at("side", default: "right")
  let inset = 16pt

  let x = if side == "right" { img-w - inset } else { inset }
  let tick-end = if side == "right" { -tick } else { tick }

  // Vertical line
  place(dx: x, dy: y-top, line(start: (0pt, 0pt), end: (0pt, y-bot - y-top), stroke: 2pt + accent))
  // Top tick
  place(dx: x, dy: y-top, line(start: (0pt, 0pt), end: (tick-end, 0pt), stroke: 2pt + accent))
  // Bottom tick
  place(dx: x, dy: y-bot, line(start: (0pt, 0pt), end: (tick-end, 0pt), stroke: 2pt + accent))

  // Badge at midpoint
  let mid-y = (y-top + y-bot) / 2
  let badge-x = x + tick-end - 6pt
  place(
    dx: badge-x,
    dy: mid-y - 10pt,
    step-badge(step, accent, size: 5pt),
  )

  // Label at midpoint
  if ann.at("label", default: "") != "" {
    let label-x = if side == "right" { badge-x - 8pt } else { badge-x + 16pt }
    place(
      dx: label-x,
      dy: mid-y + 4pt,
      annotation-label(ann.label, accent),
    )
  }
}

/// Dispatch to the correct annotation renderer by type.
#let draw-annotation(ann, step, accent, img-w, img-h) = {
  let ann-type = ann.at("type")
  if ann-type == "arrow" {
    draw-arrow(ann, step, accent, img-w, img-h)
  } else if ann-type == "circle" {
    draw-circle(ann, step, accent, img-w, img-h)
  } else if ann-type == "bracket" {
    draw-bracket(ann, step, accent, img-w, img-h)
  }
}
