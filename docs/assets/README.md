# Vanadium identity

The mark is a V of two lane groups, three parallel lanes each, meeting at one point: parallel
work converging at a stage boundary. Colors follow the T3 Code theme: neutral surfaces with a
single blue accent.

| Token | Dark | Light |
|---|---|---|
| Surface | `#0a0a0a` (icon `#111`) | `#ffffff` |
| Border | white at 8% | `#e4e4e7` |
| Text | `#f1f3f7` | `#18181b` |
| Muted | `#a3a3a3` | `#71717a` |
| Accent | `#346bf1` | `#1b4ed8` |

- `vanadium.svg`: editable 256 × 256 icon source; the README uses it directly.
- `performance-dark.svg`, `performance-light.svg`: the historical chart for each GitHub theme,
  with accessible title and description.
- `performance.csv`: plotted values; provenance is in [performance notes](../PERFORMANCE.md).

The runtime PNG is rendered from `vanadium.svg` at 256 × 256. With librsvg available:

```sh
rsvg-convert -w 256 -h 256 docs/assets/vanadium.svg -o src/main/resources/assets/vanadium/vanadium.png
```

When editing a chart, change both variants and keep them in step with `performance.csv`.
These original project assets use the repository's [GPLv3 license](../../LICENSE).
