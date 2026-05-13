#!/usr/bin/env node
// Validate Google Play screenshot assets against Google Play's hard constraints.
//
// Constraints enforced:
//  1. 24-bit PNG (color type 2 = RGB; no alpha). Color type 6 = RGBA is rejected.
//  2. Exact dimensions: 1080x1920 for phone screenshots; 1024x500 for
//     `feature-graphic*.png`.
//  3. Aspect ratio between 1:2 and 2:1 inclusive (both target sizes satisfy).
//  4. File size <= 8 MB.
//  5. With --strict: at least 4 phone screenshots per locale.
//  6. With --strict: at most 8 phone screenshots per locale.
//
// Zero runtime deps: PNG header parsed directly from the IHDR chunk
// (bytes 16-25 of the file after the 8-byte signature).

import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, basename, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = fileURLToPath(new URL(".", import.meta.url));
const ROOT = resolve(SCRIPT_DIR, "..", "..", "docs", "screenshots");
const MAX_BYTES = 8 * 1024 * 1024;
const PHONE = { w: 1080, h: 1920 };
const FEATURE = { w: 1024, h: 500 };
const MIN_PHONES = 4;
const MAX_PHONES = 8;

const ANSI = {
  reset: "\x1b[0m",
  red: "\x1b[31m",
  green: "\x1b[32m",
  yellow: "\x1b[33m",
  cyan: "\x1b[36m",
  bold: "\x1b[1m",
};
const c = (color, s) => `${ANSI[color]}${s}${ANSI.reset}`;

const strict = process.argv.includes("--strict");

function parsePng(filePath) {
  const buf = readFileSync(filePath);
  // PNG signature: 89 50 4E 47 0D 0A 1A 0A
  if (buf.length < 33 || buf.readUInt32BE(0) !== 0x89504e47) {
    throw new Error("not a PNG");
  }
  // IHDR chunk starts at byte 8: 4 bytes length, 4 bytes "IHDR", then 13 bytes data.
  if (buf.slice(12, 16).toString("ascii") !== "IHDR") {
    throw new Error("missing IHDR");
  }
  return {
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20),
    bitDepth: buf.readUInt8(24),
    colorType: buf.readUInt8(25),
    bytes: buf.length,
  };
}

const COLOR_TYPE_NAMES = {
  0: "grayscale",
  2: "RGB (24-bit)",
  3: "indexed",
  4: "grayscale+alpha",
  6: "RGBA (32-bit)",
};

function isFeatureGraphic(name) {
  return /^feature-graphic.*\.png$/i.test(name);
}

function ratioOk(w, h) {
  const r = w / h;
  return r >= 0.5 && r <= 2.0;
}

function validateFile(filePath) {
  const name = basename(filePath);
  const reasons = [];
  let info;
  try {
    info = parsePng(filePath);
  } catch (e) {
    return { name, ok: false, reasons: [`PNG parse failed: ${e.message}`], info: null };
  }
  if (info.colorType !== 2) {
    reasons.push(
      `color type ${info.colorType} (${COLOR_TYPE_NAMES[info.colorType] ?? "unknown"}); Google Play requires 24-bit RGB (color type 2, no alpha)`,
    );
  }
  const feature = isFeatureGraphic(name);
  const target = feature ? FEATURE : PHONE;
  if (info.width !== target.w || info.height !== target.h) {
    reasons.push(`dimensions ${info.width}x${info.height}; expected ${target.w}x${target.h}`);
  }
  // Aspect ratio rule applies to phone screenshots only; feature graphic has
  // its own fixed dimensions and is exempt from the [1:2, 2:1] window.
  if (!feature && !ratioOk(info.width, info.height)) {
    reasons.push(`aspect ratio outside [1:2, 2:1]`);
  }
  if (info.bytes > MAX_BYTES) {
    reasons.push(`file size ${(info.bytes / 1024 / 1024).toFixed(2)} MB exceeds 8 MB`);
  }
  return { name, ok: reasons.length === 0, reasons, info };
}

function collectGroups(root) {
  // Returns Map<locale, string[]> where locale "" = root dir.
  const groups = new Map();
  const rootPngs = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const full = join(root, entry.name);
    if (entry.isDirectory()) {
      const subPngs = readdirSync(full)
        .filter((f) => f.toLowerCase().endsWith(".png"))
        .map((f) => join(full, f));
      if (subPngs.length > 0) groups.set(entry.name, subPngs);
    } else if (entry.isFile() && entry.name.toLowerCase().endsWith(".png")) {
      rootPngs.push(full);
    }
  }
  if (rootPngs.length > 0) groups.set("", rootPngs);
  return groups;
}

function main() {
  if (!existsSync(ROOT) || !statSync(ROOT).isDirectory()) {
    console.error(c("red", `error: screenshots directory not found at ${ROOT}`));
    process.exit(1);
  }
  const groups = collectGroups(ROOT);
  if (groups.size === 0) {
    console.error(c("red", `error: no PNG files found under ${ROOT}`));
    process.exit(1);
  }

  let totalChecked = 0;
  let totalFailed = 0;
  let countFailures = 0;

  for (const [locale, files] of groups) {
    const label = locale === "" ? "(root)" : locale;
    console.log(c("bold", `\n== Locale ${label} ==`));
    let phoneCount = 0;
    for (const f of files.sort()) {
      totalChecked += 1;
      const r = validateFile(f);
      if (!isFeatureGraphic(r.name)) phoneCount += 1;
      if (r.ok) {
        console.log(`  ${c("green", "PASS")} ${r.name}`);
      } else {
        totalFailed += 1;
        console.log(`  ${c("red", "FAIL")} ${r.name}: ${r.reasons.join("; ")}`);
      }
    }
    if (strict) {
      if (phoneCount < MIN_PHONES) {
        countFailures += 1;
        console.log(c("red", `  FAIL[count]: only ${phoneCount} phone screenshots (min ${MIN_PHONES})`));
      }
      if (phoneCount > MAX_PHONES) {
        countFailures += 1;
        console.log(c("red", `  FAIL[count]: ${phoneCount} phone screenshots (max ${MAX_PHONES})`));
      }
    } else {
      console.log(c("yellow", `  (count check skipped; pass --strict to enforce min ${MIN_PHONES} / max ${MAX_PHONES})`));
    }
  }

  console.log(c("bold", `\n== Summary ==`));
  console.log(`  files checked : ${totalChecked}`);
  console.log(`  file failures : ${totalFailed}`);
  if (strict) console.log(`  count failures: ${countFailures}`);
  const failed = totalFailed + countFailures;
  if (failed === 0) {
    console.log(c("green", "All checks passed."));
    process.exit(0);
  }
  console.log(c("red", `Validation failed (${failed} issue(s)).`));
  process.exit(1);
}

main();
