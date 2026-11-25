# IdleStow branding

Slogan: **Idle files. Reclaimed space.**

## The mark

A mint document with a folded corner on a rounded navy tile. A crescent moon is cut out of the page with a small sparkle beside it: the file is "hibernating", stored away but still there. The mark was supplied by the project owner (Mansoor Alakoozai) as `idlestow-hibernation.svg` and is used unchanged apart from its `<title>`. It is authored on a 512 x 512 grid. At 32 px and below the generator drops the sparkle and shadow and enlarges the page slightly so the icon stays legible.

## Colors

| Role | Hex |
|---|---|
| Tile gradient | `#202D45` to `#101827` |
| Page gradient | `#B0F5DB` to `#48C7A5` |
| Folded corner | `#268F81` |
| Sparkle | `#173B42` |
| Slogan (wordmark) | `#6EE7C4` |
| Wordmark text | `#F8FAFC` |

## Files

- `logo.svg`: the mark, 512 x 512 viewBox, on its own navy tile.
- `logo-wordmark.svg`: mark, "IdleStow" text and slogan on a dark panel, for the README. Uses the font stack `Segoe UI, Inter, Arial, sans-serif`.
- `png/icon-{16,24,32,48,64,128,256,512}.png`: rasterized mark (Java2D, antialiased).
- `IdleStow.ico`: multi-resolution Windows icon with 16, 24, 32, 48, 64, 128 and 256 px entries, PNG-compressed.

Copies of these outputs live in:

- `app/src/main/resources/branding/`: `icon-16.png`, `icon-32.png`, `icon-64.png`, `icon-256.png`, `logo.svg`
- `packaging/windows/IdleStow.ico`: identical to `IdleStow.ico` above

## Regenerating

The generator is a single-file Java program using only the JDK. Run from the repo root:

```
"C:\Program Files\Java\jdk-21\bin\java.exe" scripts/branding/BrandingGenerator.java assets/branding
```

This writes the PNGs to `assets/branding/png/` and the ICO to `assets/branding/IdleStow.ico`. Then copy the outputs to the two locations listed above. The generator's geometry must be kept in step with `logo.svg` and `logo-wordmark.svg` by hand; all three use the same 512-unit coordinates (the wordmark scales the mark by 0.5).
