import puppeteer from "puppeteer";
import path from "path";
import fs from "fs";

const PORT = Number(process.env.CAPTURE_PORT ?? 3099);

const LANGS = ["en", "ru", "es", "de", "fr", "fa", "zh-CN"];

const SLIDES = [
  { param: "1", name: "01-hero", w: 1080, h: 1920 },
  { param: "2", name: "02-no-root", w: 1080, h: 1920 },
  { param: "3", name: "03-privacy", w: 1080, h: 1920 },
  { param: "4", name: "04-controls", w: 1080, h: 1920 },
  { param: "5", name: "05-diagnostics", w: 1080, h: 1920 },
  { param: "6", name: "06-more-features", w: 1080, h: 1920 },
  { param: "fg", name: "feature-graphic", w: 1024, h: 500 },
];

const OUT_DIR = path.resolve("../docs/screenshots");
const DEFAULT_LANG = "en";
const BROWSER_ARGS = process.env.CI ? ["--no-sandbox", "--disable-setuid-sandbox"] : [];

function ensureDir(dir) {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

async function main() {
  ensureDir(OUT_DIR);
  for (const lang of LANGS) {
    ensureDir(path.join(OUT_DIR, lang));
  }

  const browser = await puppeteer.launch({ args: BROWSER_ARGS, headless: true });
  const page = await browser.newPage();

  for (const lang of LANGS) {
    for (const slide of SLIDES) {
      await page.setViewport({ width: slide.w, height: slide.h, deviceScaleFactor: 1 });
      const url = `http://localhost:${PORT}/?slide=${slide.param}&lang=${encodeURIComponent(lang)}`;
      try {
        await page.goto(url, { waitUntil: "load", timeout: 60000 });
      } catch {
        console.log(`Timeout navigating to ${url}, trying screenshot anyway...`);
      }
      await new Promise((r) => setTimeout(r, 2000));

      const localePath = path.join(OUT_DIR, lang, `${slide.name}.png`);
      await page.screenshot({
        path: localePath,
        type: "png",
        clip: { x: 0, y: 0, width: slide.w, height: slide.h },
      });
      console.log(`Saved ${localePath}`);

      // Backward compatibility: also emit English assets at the top level.
      if (lang === DEFAULT_LANG) {
        const topLevelPath = path.join(OUT_DIR, `${slide.name}.png`);
        fs.copyFileSync(localePath, topLevelPath);
        console.log(`Saved ${topLevelPath}`);
      }
    }
  }

  await browser.close();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
