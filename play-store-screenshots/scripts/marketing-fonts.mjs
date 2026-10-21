import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";

// This is the parser used by the pinned Next local-font loader.
const require = createRequire(import.meta.url);
const parseFont = require("next/dist/compiled/@next/font/dist/fontkit").default;
if (typeof parseFont !== "function") throw new Error("Next local-font parser is unavailable");

export const CHINESE_FONT_SHA256 = "61ef2b0acfdc88ee4b7a5031f28cbc7db46a619c419d3321896bb566186dbc05";
export const chineseFontPath = new URL("../public/fonts/NotoSansSC-Marketing.woff2", import.meta.url);

export function chineseCopyCharacters() {
  const source = fs.readFileSync(new URL("../src/copy/zh-CN.ts", import.meta.url), "utf8");
  const copy = JSON.parse(source.slice(source.indexOf("= ") + 2).trim().replace(/;$/, ""));
  const values = [...copy.headlines.flat(), ...copy.labels, ...copy.descriptions, ...copy.featureGraphic.tagline];
  return [...new Set(values.join(""))];
}

export function assertGlyphCoverage(buffer, characters) {
  const font = parseFont(buffer);
  const missing = characters.filter((character) => !font.hasGlyphForCodePoint(character.codePointAt(0)));
  assert.equal(missing.length, 0, `Chinese marketing font has missing glyphs: ${missing.join("")}`);
  return font;
}

export function verifyBundledChineseFont() {
  const buffer = fs.readFileSync(chineseFontPath);
  assert.equal(buffer.toString("ascii", 0, 4), "wOF2", "Chinese font must be WOFF2");
  assert.equal(createHash("sha256").update(buffer).digest("hex"), CHINESE_FONT_SHA256,
    "Chinese font does not match its pinned source");
  const characters = chineseCopyCharacters();
  const font = assertGlyphCoverage(buffer, characters);
  assert.ok(font.variationAxes.wght.min <= 400 && font.variationAxes.wght.max >= 700,
    "Chinese font must support regular and bold text");
  return { sha256: CHINESE_FONT_SHA256, characters: characters.length, family: font.familyName };
}

export function assertRenderedChineseFonts(fonts) {
  assert.ok(fonts.length, "Chinese text has no rendered font proof");
  for (const font of fonts) {
    assert.ok(font.isCustomFont && font.familyName.toLowerCase().replace(/[^a-z]/g, "").includes("notosanssc"),
      `Chinese text used an unpinned font: ${font.familyName}`);
  }
}

export async function verifyRenderedChineseFont(page) {
  const selector = "[data-overlay-text], [data-overlay-text] *";
  const hasChinese = await page.$$eval(selector,
    (elements) => elements.map((element) => /\p{Script=Han}/u.test(element.textContent)));
  const session = await page.createCDPSession();
  try {
    await session.send("DOM.enable");
    await session.send("CSS.enable");
    const { root } = await session.send("DOM.getDocument");
    const { nodeIds } = await session.send("DOM.querySelectorAll", { nodeId: root.nodeId, selector });
    assert.equal(nodeIds.length, hasChinese.length, "Chinese font probe DOM changed");
    const proof = [];
    for (const [index, nodeId] of nodeIds.entries()) {
      if (!hasChinese[index]) continue;
      const { fonts } = await session.send("CSS.getPlatformFontsForNode", { nodeId });
      const used = fonts.filter((font) => font.glyphCount > 0);
      // Headings can contain child spans; those spans are checked separately.
      if (!used.length) continue;
      assertRenderedChineseFonts(used);
      proof.push(...used);
    }
    assert.ok(proof.length, "Chinese slide has no pinned rendered glyphs");
    return proof;
  } finally {
    await session.detach();
  }
}
