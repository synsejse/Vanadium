# Vanadium identity

The mark is a V of two lane groups, three parallel lanes each, meeting at one point: parallel
work converging at a stage boundary. Its accent is vanadyl blue (`#346bf1`) on a neutral tile.

- `vanadium.svg`: editable 256 × 256 icon source, used by the README and as the documentation
  site's favicon (copied to `../public/favicon.svg`).

The runtime PNG is rendered from `vanadium.svg` at 256 × 256. With librsvg available:

```sh
rsvg-convert -w 256 -h 256 docs/assets/vanadium.svg -o src/main/resources/assets/vanadium/vanadium.png
```

The documentation's charts are drawn live by the site from `../src/data/performance.ts`; the raw
samples behind them are in `../benchmarks/`.

These original project assets use the repository's [GPLv3 license](../../LICENSE).
