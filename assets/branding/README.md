# IdleStow branding

Slogan: **Idle files. Reclaimed space.**

## The mark

A rounded storage box with a downward compression chevron pressing into it. The lower half of the box is dimmed to suggest idle storage. The shapes are original and simple, and the mark uses no third-party icons. It is authored on a 256 x 256 grid and is designed to stay legible down to 16 x 16 px.

## Colors

| Role        | Hex       |
|-------------|-----------|
| Accent (box)| `#60A5FA` |
| Secondary (slogan) | `#2DD4BF` |
| Dark (chevron, dim overlay, wordmark panel) | `#0F172A` |
| Light (wordmark text) | `#F8FAFC` |

## Files

- `logo.svg`: the mark, 256 x 256 viewBox, transparent background.
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

This writes the PNGs to `assets/branding/png/` and the ICO to `assets/branding/IdleStow.ico`. Then copy the outputs to the two locations listed above. The generator's geometry must be kept in step with `logo.svg` and `logo-wordmark.svg` by hand; the three files share the same coordinates.
