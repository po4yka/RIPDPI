import puppeteer from "puppeteer";
import path from "path";

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

async function main() {
  const browser = await puppeteer.launch({ headless: true });
  const page = await browser.newPage();

  for (const slide of SLIDES) {
    await page.setViewport({ width: slide.w, height: slide.h, deviceScaleFactor: 1 });
    try {
      await page.goto(`http://localhost:3099/?slide=${slide.param}`, { waitUntil: "load", timeout: 60000 });
    } catch {
      console.log(`Timeout navigating to slide ${slide.param}, trying screenshot anyway...`);
    }
    await new Promise((r) => setTimeout(r, 2000));
    const outPath = path.join(OUT_DIR, `${slide.name}.png`);
    await page.screenshot({ path: outPath, type: "png", clip: { x: 0, y: 0, width: slide.w, height: slide.h } });
    console.log(`Saved ${outPath}`);
  }

  await browser.close();
}

main().catch(console.error);
