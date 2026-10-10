import puppeteer from "puppeteer";
import path from "path";
import fs from "fs";
import os from "os";

const PORT = Number(process.env.CAPTURE_PORT ?? 3099);

const LANGS = ["en", "ru", "es", "de", "fr", "fa", "zh-CN"];

const SLIDES = [
  { param: "1", name: "01-hero", w: 1080, h: 1920 },
  { param: "2", name: "02-diagnostics", w: 1080, h: 1920 },
  { param: "3", name: "03-relays", w: 1080, h: 1920 },
  { param: "4", name: "04-dns", w: 1080, h: 1920 },
  { param: "5", name: "05-strategies", w: 1080, h: 1920 },
  { param: "6", name: "06-local-tools", w: 1080, h: 1920 },
  { param: "fg", name: "feature-graphic", w: 1024, h: 500 },
];

const OUT_DIR = path.resolve("../docs/screenshots");
const DEFAULT_LANG = "en";
const BROWSER_ARGS = process.env.CI ? ["--no-sandbox", "--disable-setuid-sandbox"] : [];
const BROWSER_EXECUTABLE_PATH = process.env.PUPPETEER_EXECUTABLE_PATH || undefined;

function ensureDir(dir) {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

async function main() {
  const sourceManifest = JSON.parse(fs.readFileSync("public/screenshots/source-capture.json", "utf8"));
  const sourceSize = /^([1-9]\d*)x([1-9]\d*)$/.exec(sourceManifest.device?.screen ?? "");
  if (!sourceSize) throw new Error("Capture manifest has no valid physical screen size");
  const captureSize = { width: Number(sourceSize[1]), height: Number(sourceSize[2]) };
  ensureDir(OUT_DIR);
  for (const lang of LANGS) {
    ensureDir(path.join(OUT_DIR, lang));
  }

  const browser = await puppeteer.launch({
    args: BROWSER_ARGS,
    executablePath: BROWSER_EXECUTABLE_PATH,
    headless: true,
  });
  const staging = fs.mkdtempSync(path.join(os.tmpdir(), "ripdpi-play-capture-"));
  const measurements = [];
  try {
    const page = await browser.newPage();
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    for (const lang of LANGS) {
      ensureDir(path.join(staging, lang));
      for (const slide of SLIDES) {
        pageErrors.length = 0;
        await page.setViewport({ width: slide.w, height: slide.h, deviceScaleFactor: 1 });
        const url = `http://localhost:${PORT}/?slide=${slide.param}&lang=${encodeURIComponent(lang)}`;
        const response = await page.goto(url, { waitUntil: "load", timeout: 60000 });
        if (!response?.ok()) throw new Error(`Failed to load ${url}: ${response?.status()}`);
        await page.waitForSelector(`[data-marketing-slide][data-locale="${lang}"]`, { timeout: 30000 });
        const measurement = await page.evaluate(async ({ captureSize, phone }) => {
          await document.fonts.ready;
          await Promise.all(Array.from(document.images, async (img) => {
            await img.decode();
            if (!img.complete || !img.naturalWidth) throw new Error(`Broken image: ${img.src}`);
          }));
          const slide = document.querySelector("[data-marketing-slide]");
          const bounds = slide.getBoundingClientRect();
          const within = (r) => r.left >= bounds.left - 0.5 && r.top >= bounds.top - 0.5 &&
            r.right <= bounds.right + 0.5 && r.bottom <= bounds.bottom + 0.5;
          const texts = Array.from(slide.querySelectorAll("[data-overlay-text]"));
          const textRects = [];
          let smallestTextAt260 = Infinity;
          let textArea = 0;
          for (const text of texts) {
            const box = text.getBoundingClientRect();
            const computed = getComputedStyle(text);
            const fontSize = Number.parseFloat(computed.fontSize);
            smallestTextAt260 = Math.min(smallestTextAt260, fontSize * 260 / bounds.width);
            if (fontSize < (phone ? 48 : 50))
              throw new Error(`Marketing text too small: ${text.textContent}`);
            const range = document.createRange();
            range.selectNodeContents(text);
            const glyphs = range.getBoundingClientRect();
            // Font glyphs can extend beyond a line box while overflow is visible.
            // Check both bounds, including actual laid-out text, rather than scrollHeight.
            if (!within(box) || !within(glyphs) || text.scrollWidth > box.width + 1)
              throw new Error(`Clipped text: ${text.textContent}`);
            textArea += Math.max(box.width * box.height, glyphs.width * glyphs.height);
            textRects.push(glyphs);
          }
          const textFraction = textArea / (bounds.width * bounds.height);
          if (textFraction > 0.2) throw new Error(`Text envelope exceeds 20%: ${textFraction}`);
          const headline = slide.querySelector("h1");
          if (headline) {
            const style = getComputedStyle(headline);
            if (Number.parseFloat(style.fontSize) < 100 ||
              headline.getBoundingClientRect().height > Number.parseFloat(style.lineHeight) * 2 + 1)
              throw new Error(`Headline is too small or exceeds two lines: ${headline.textContent}`);
          }
          if (slide.dataset.locale === "fa") {
            const family = getComputedStyle(slide).fontFamily.split(",")[0];
            if (!family.toLowerCase().includes("vazirmatn") ||
              !document.fonts.check(`400 48px ${family}`, "شبکه") ||
              !document.fonts.check(`700 100px ${family}`, "تنظیمات"))
              throw new Error("Pinned Persian font is not loaded");
          }
          const captures = Array.from(slide.querySelectorAll("[data-app-capture]"));
          if (captures.length !== (phone ? 1 : 0))
            throw new Error("Each phone poster must show one real feature frame; banner uses no miniature UI");
          for (const img of captures) {
            const r = img.getBoundingClientRect();
            if (!within(r) || img.naturalWidth !== captureSize.width || img.naturalHeight !== captureSize.height ||
              r.width < 824 ||
              Math.abs(r.width / r.height - img.naturalWidth / img.naturalHeight) > 0.0001)
              throw new Error(`Clipped or distorted Android capture: ${img.src}`);
            if (textRects.some((t) =>
              t.left < r.right && t.right > r.left && t.top < r.bottom && t.bottom > r.top
            )) throw new Error(`Marketing text overlaps Android UI: ${img.src}`);
          }
          return { textFraction, actualUiCount: captures.length, smallestTextAt260,
            actualUiWidthAt260: captures.length ? captures[0].getBoundingClientRect().width * 260 / bounds.width : null };
        }, { captureSize, phone: slide.param !== "fg" });
        if (pageErrors.length) throw new Error(`Page errors: ${pageErrors.join("; ")}`);
        const stagedPath = path.join(staging, lang, `${slide.name}.png`);
        await page.screenshot({ path: stagedPath, type: "png", omitBackground: false,
          clip: { x: 0, y: 0, width: slide.w, height: slide.h } });
        measurements.push({ locale: lang, slide: slide.name, ...measurement });
        console.log(`Checked ${lang}/${slide.name}: text ${(measurement.textFraction * 100).toFixed(1)}%, UI ${measurement.actualUiCount}`);
      }
    }
    // Publish only after all locale pages pass. Remove only known retired exports.
    const retired = ["02-no-root", "04-controls", "05-diagnostics", "06-more-features"];
    for (const lang of LANGS) {
      for (const slide of SLIDES) {
        const filename = `${slide.name}.png`;
        fs.copyFileSync(path.join(staging, lang, filename), path.join(OUT_DIR, lang, filename));
        if (lang === DEFAULT_LANG) fs.copyFileSync(path.join(staging, lang, filename), path.join(OUT_DIR, filename));
      }
      for (const name of retired) fs.rmSync(path.join(OUT_DIR, lang, `${name}.png`), { force: true });
    }
    for (const name of retired) fs.rmSync(path.join(OUT_DIR, `${name}.png`), { force: true });
    if (process.env.CAPTURE_QA_PATH) fs.writeFileSync(process.env.CAPTURE_QA_PATH, JSON.stringify(measurements, null, 2));
    console.log(`Saved 56 assets; maximum text envelope ${(Math.max(...measurements.map(m => m.textFraction)) * 100).toFixed(1)}%.`);
  } finally {
    await browser.close();
    fs.rmSync(staging, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
