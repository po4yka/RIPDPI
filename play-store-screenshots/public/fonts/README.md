# Marketing fonts

Vazirmatn v33.003 is bundled for Persian marketing copy. The unmodified font files come from the pinned upstream tag and are licensed under SIL OFL 1.1. The license text has trailing whitespace removed. These assets are not an Android dependency.

Source: https://github.com/rastikerdar/vazirmatn/tree/v33.003

| File | SHA-256 |
|---|---|
| `Vazirmatn-Regular.woff2` | `e382101336c6eb32cfb31381c027d02d2e0354bad08f6a395d4088beb3db3d91` |
| `Vazirmatn-Bold.woff2` | `836fae7d42d83faa249bc00e0099592be98a1fa260d22d82f269b6091e585627` |
| `OFL-Vazirmatn.txt` | `e6f4ce7b6c830d5a25a4f8ac777c267cd7d3c180bc2a7150ca57cbb9b280c4b7` |

## Simplified Chinese marketing font

Noto Sans SC 2.004-H2 is bundled as an unmodified Google Fonts variable WOFF2 subset for the current Chinese marketing copy. The font supports weights 100–900; the generator uses 400–700. The 65-character subset is 20,592 bytes. It is not an Android dependency and does not use a runtime font service.

The [Google Fonts text API](https://developers.google.com/fonts/docs/css2#optimizing_your_font_requests) produced this subset. The [license source](https://github.com/google/fonts/blob/2894aab31764f10f29c421bdfd2340d3b382d384/ofl/notosanssc/OFL.txt) is pinned. The font is unchanged. The license text has trailing whitespace removed. The CSS request used a Chrome 130 Linux user agent with AppleWebKit/537.36 to request variable-font support.

[Exact CSS request](https://fonts.googleapis.com/css2?family=Noto+Sans+SC%3Awght%40400..700&text=+6DHINPSov%E4%B8%8E%E4%B8%AD%E4%B8%BA%E4%B9%89%E4%BB%BD%E4%BC%A0%E4%BF%9D%E5%87%BA%E5%8A%A0%E5%8C%85%E5%92%8C%E5%A4%87%E5%A4%8D%E5%AD%98%E5%AE%9A%E5%AF%86%E5%AF%BC%E5%BC%8F%E5%BE%84%E6%80%81%E6%81%A2%E6%82%A8%E6%8B%A9%E6%8D%AE%E6%8E%8C%E6%8E%A5%E6%8E%A7%E6%95%B0%E6%95%B4%E6%96%AD%E6%96%B9%E6%9E%9C%E6%9F%A5%E6%A3%80%E6%B5%81%E7%8A%B6%E7%95%A5%E7%9A%84%E7%9C%8B%E7%AD%96%E7%BB%93%E7%BB%9C%E7%BB%A7%E7%BD%91%E7%BD%AE%E8%87%AA%E8%AE%BE%E8%AF%8A%E8%B0%83%E8%B7%AF%E8%BE%93%E8%BF%9E%E9%80%89%E9%85%8D%E9%87%8F&display=swap)

[Exact WOFF2 source](https://fonts.gstatic.com/l/font?kit=k3kXo84MPvpLmixcA63oeALhLJAi8C_ItuTTVUfV0vqD0Ho3RN2tSI2IlsMO73m1nM_xBcHVUNX3hQds1R2lSfi0JpX6fhDMGsQ5QL9jhqjnSEqaIXl8YDHw31Cn5b4jPiKrsKNiaH69tNMriq8QQ_7fjUWWsRhko7Bsde4tYi6nUFnWwyN4EpHeix8ErwbdIBt8ph3Mc3LINN7u0TCjPRKQgu1Rz4sntbcA1kCBhc_lo7okPnuSZy1S_q4jo96jTJPnqGk&skey=cf0bb10b5602fdc3&v=v41)

| File | SHA-256 |
|---|---|
| `NotoSansSC-Marketing.woff2` | `61ef2b0acfdc88ee4b7a5031f28cbc7db46a619c419d3321896bb566186dbc05` |
| `OFL-NotoSansSC.txt` | `babcfe66c8a098b2fa279bc724a3a342f8124f77ce18941fbcc1bbb39823cded` |

`bun run test:fonts` checks every character in the actual Chinese copy against the pinned font. New characters outside this subset require a new subset, source pin and review. The gate uses the same compiled font parser as the pinned Next local-font loader and fails if that parser changes. During capture, Chrome reports the actual fonts used for Chinese text; system fallback or a Latin font fails the capture. Normal layout, size and image checks still apply.
