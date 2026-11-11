#!/usr/bin/env python3
"""Worker-scaling chart for the README: three small multiples, one per workload, light and dark.

Usage: make_chart.py SAMPLES.csv OUT_DIR
CSV columns: workload,arm,workers,jvm,sprint_window,tps  (arm: vanadium | lithium)
"""
import csv, math, sys
from collections import defaultdict

THEMES = {
    "dark": dict(surface="#0a0a0a", border="#27272a", text="#f1f3f7", muted="#a3a3a3", accent="#346bf1", grid="#27272a"),
    "light": dict(surface="#ffffff", border="#e4e4e7", text="#18181b", muted="#71717a", accent="#1b4ed8", grid="#e4e4e7"),
}
PANELS = [
    ("uniform4096", "Spread out", "4,096 zombies, four per chunk"),
    ("dense700", "Crowded base", "700 mixed mobs in 4×4 chunks"),
    ("brawl600", "Combat", "600 mobs fighting in an arena"),
]
W, H = 960, 440
PLOT_TOP, PLOT_BOTTOM, PLOT_W = 152, 342, 232
PANEL_X = [62, 364, 666]
X_MAX = 72


def nice_max(value):
    """Smallest of 1/2/2.5/4/5/8 x 10^n steps giving 4 or 5 ticks above the value."""
    for exponent in range(0, 5):
        for step in (1, 2, 2.5, 4, 5, 8):
            tick = step * 10 ** exponent
            for count in (4, 5):
                if tick * count >= value * 1.12:
                    return tick, count
    raise ValueError(value)


def fmt(value):
    return f"{value:,.0f}" if value >= 100 else f"{value:.1f}".rstrip("0").rstrip(".")


def load(path):
    data = defaultdict(lambda: defaultdict(list))
    base = defaultdict(list)
    for row in csv.DictReader(open(path)):
        if row["arm"] == "lithium":
            base[row["workload"]].append(float(row["tps"]))
        else:
            data[row["workload"]][int(row["workers"])].append(float(row["tps"]))
    return data, base


def render(theme, data, base):
    c = THEMES[theme]
    out = []
    add = out.append
    desc = []
    for key, title, _ in PANELS:
        points = ", ".join(f"{n} workers {fmt(sum(v) / len(v))} TPS" for n, v in sorted(data[key].items()))
        desc.append(f"{title}: Lithium alone {fmt(sum(base[key]) / len(base[key]))} TPS; with Vanadium {points}.")
    add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" aria-labelledby="title desc">')
    add('<title id="title">Vanadium worker scaling on Minecraft 26.3</title>')
    add(f'<desc id="desc">Uncapped ticks per second by worker count, three workloads. {" ".join(desc)}</desc>')
    add(f'<rect x=".5" y=".5" width="{W - 1}" height="{H - 1}" rx="12" fill="{c["surface"]}" stroke="{c["border"]}"/>')
    add('<g font-family="Inter, -apple-system, Segoe UI, Helvetica, Arial, sans-serif">')
    add(f'<text x="32" y="40" fill="{c["text"]}" font-size="17" font-weight="600">Throughput by worker count</text>')
    add(f'<text x="32" y="64" fill="{c["muted"]}" font-size="13">Minecraft 26.3 with Lithium · dual Xeon E5-2699 v3 VM · uncapped TPS · higher is better</text>')
    for (key, title, subtitle), x0 in zip(PANELS, PANEL_X):
        series = sorted((n, sum(v) / len(v), min(v), max(v)) for n, v in data[key].items())
        baseline = sum(base[key]) / len(base[key])
        tick, count = nice_max(max(max(hi for _, _, _, hi in series), baseline))
        y_max = tick * count
        px = lambda n: round(x0 + PLOT_W * n / X_MAX, 2)
        py = lambda v: round(PLOT_BOTTOM - (PLOT_BOTTOM - PLOT_TOP) * v / y_max, 2)
        add(f'<text x="{x0}" y="108" fill="{c["text"]}" font-size="14" font-weight="600">{title}</text>')
        add(f'<text x="{x0}" y="128" fill="{c["muted"]}" font-size="11">{subtitle}</text>')
        for i in range(count + 1):
            y = py(tick * i)
            add(f'<path d="M{x0} {y}H{x0 + PLOT_W}" stroke="{c["grid"]}"/>')
            add(f'<text x="{x0 - 8}" y="{y + 4}" fill="{c["muted"]}" font-size="11" text-anchor="end">{fmt(tick * i)}</text>')
        for n in (0, 16, 32, 48, 64):
            add(f'<text x="{px(n)}" y="{PLOT_BOTTOM + 20}" fill="{c["muted"]}" font-size="11" text-anchor="middle">{n}</text>')
        add(f'<text x="{x0 + PLOT_W / 2}" y="{PLOT_BOTTOM + 42}" fill="{c["muted"]}" font-size="11" text-anchor="middle">Workers (+ server thread)</text>')
        # Reference: the same server without Vanadium, one tick thread.
        by = py(baseline)
        add(f'<path d="M{x0} {by}H{x0 + PLOT_W}" stroke="{c["muted"]}" stroke-width="1.5"><title>Lithium alone: {fmt(baseline)} TPS</title></path>')
        add(f'<text x="{x0 + PLOT_W / 2}" y="{by - 6}" fill="{c["muted"]}" font-size="11" text-anchor="middle">Lithium alone · {fmt(baseline)}</text>')
        # Sample range behind each mean.
        for n, _, lo, hi in series:
            add(f'<path d="M{px(n)} {py(lo)}V{py(hi)}" stroke="{c["accent"]}" stroke-width="2" stroke-linecap="round" opacity=".35"/>')
        path = " L".join(f"{px(n)} {py(mean)}" for n, mean, _, _ in series)
        add(f'<path d="M{path}" fill="none" stroke="{c["accent"]}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>')
        peak = max(series, key=lambda p: p[1])
        last = series[-1]
        for n, mean, lo, hi in series:
            add(f'<circle cx="{px(n)}" cy="{py(mean)}" r="4" fill="{c["accent"]}" stroke="{c["surface"]}" stroke-width="2">'
                f'<title>{n} workers: {fmt(mean)} TPS (samples {fmt(lo)}–{fmt(hi)}), {mean / baseline:.1f}× Lithium alone</title></circle>')
        labelled = [peak] + ([last] if last is not peak and px(last[0]) - px(peak[0]) > 44 else [])
        for n, mean, _, hi in labelled:
            anchor = "end" if px(n) > x0 + PLOT_W - 14 else "middle"
            x = px(n) + (6 if anchor == "end" else 0)
            # The last point of a falling line is labelled below it, clear of the segment arriving from above.
            falling = (n, mean) == last[:2] and last is not peak and series[-2][1] > mean
            y = py(mean) + 19 if falling else py(hi) - 9
            add(f'<text x="{x}" y="{y}" fill="{c["text"]}" font-size="12" font-weight="600" text-anchor="{anchor}">{fmt(mean)}</text>')
    add(f'<text x="32" y="{H - 22}" fill="{c["muted"]}" font-size="11">Each point is the mean of four samples (two server JVMs × two sprint windows); bars show their range. '
        'Lithium alone is the same server without Vanadium and C2ME.</text>')
    add('</g>')
    add('</svg>')
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    data, base = load(sys.argv[1])
    for theme in THEMES:
        open(f"{sys.argv[2]}/performance-{theme}.svg", "w").write(render(theme, data, base))
