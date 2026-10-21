import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { assertGlyphCoverage, assertRenderedChineseFonts, chineseCopyCharacters,
  chineseFontPath, verifyBundledChineseFont } from "./marketing-fonts.mjs";

test("the pinned font covers every current Chinese marketing character", () => {
  const proof = verifyBundledChineseFont();
  assert.equal(proof.characters, chineseCopyCharacters().length);
});

test("the previous Latin-only font cannot satisfy Chinese copy", () => {
  const latin = fs.readFileSync(new URL("../node_modules/next/dist/compiled/@vercel/og/Geist-Regular.ttf", import.meta.url));
  assert.throws(() => assertGlyphCoverage(latin, chineseCopyCharacters()), /missing glyphs/);
});

test("new characters outside the pinned subset require a font update", () => {
  assert.throws(() => assertGlyphCoverage(fs.readFileSync(chineseFontPath), ["龘"]), /missing glyphs/);
});

test("system fallback and Latin custom fonts cannot satisfy rendered Chinese", () => {
  assert.throws(() => assertRenderedChineseFonts([]), /no rendered font proof/);
  assert.throws(() => assertRenderedChineseFonts([{ familyName: "PingFang SC", isCustomFont: false }]), /unpinned/);
  assert.throws(() => assertRenderedChineseFonts([{ familyName: "Geist", isCustomFont: true }]), /unpinned/);
  assert.doesNotThrow(() => assertRenderedChineseFonts([{ familyName: "Noto Sans SC", isCustomFont: true }]));
});
