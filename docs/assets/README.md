# Vanadium identity

The mark is a V woven from three mint and three violet lanes: parallel work converging
at a stage boundary. The wordmark and charts share the same palette:
ink `#111724`, mint `#93dec2`, violet `#b6a2ff`, text `#f0f3fa`.

- `vanadium.svg`: editable 256 × 256 icon source.
- `vanadium-header.svg`: self-contained README wordmark; no remote fonts or images.
- `performance.svg`: editable historical chart, with accessible title/description.
- `performance.csv`: plotted values; provenance is in [performance notes](../PERFORMANCE.md).

The runtime PNG is rendered from `vanadium.svg` at 256 × 256. With librsvg available:

```sh
rsvg-convert -w 256 -h 256 docs/assets/vanadium.svg -o src/main/resources/assets/vanadium/vanadium.png
```

Keep the mark paths in the header consistent with the icon source when editing.
These original project assets use the repository's [GPLv3 license](../../LICENSE).
