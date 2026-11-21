/**
 * Benchmark data behind the performance page's charts. Same numbers as the tables on that page and the CSVs in
 * docs/benchmarks/ (dual Xeon E5-2699 v3 VM, Minecraft 26.3 with Lithium, uncapped TPS: mean, min, max).
 */
export type Sample = { mean: number; min: number; max: number };
export type Workload = { key: string; name: string; detail: string; lithium: Sample; byWorkers: [number, Sample][] };

const s = (mean: number, min: number, max: number): Sample => ({ mean, min, max });

export const DEFAULT_WORKERS = 36;

export const workerScaling: Workload[] = [
  {
    key: 'uniform4096',
    name: 'Spread out',
    detail: '4,096 zombies, four per chunk',
    lithium: s(25.5, 22.9, 27.0),
    byWorkers: [
      [2, s(73, 72, 75)],
      [4, s(117, 113, 122)],
      [8, s(176, 169, 187)],
      [16, s(278, 264, 301)],
      [24, s(337, 326, 348)],
      [32, s(376, 362, 383)],
      [36, s(367, 340, 394)],
      [48, s(371, 348, 389)],
      [64, s(319, 290, 353)],
      [72, s(224, 219, 232)],
    ],
  },
  {
    key: 'dense700',
    name: 'Crowded base',
    detail: '700 mixed mobs in 4×4 chunks',
    lithium: s(206, 188, 223),
    byWorkers: [
      [2, s(554, 526, 581)],
      [4, s(778, 704, 826)],
      [8, s(1129, 1020, 1250)],
      [16, s(1605, 1471, 1754)],
      [24, s(1621, 909, 2083)],
      [32, s(1871, 1282, 2273)],
      [36, s(1887, 1639, 2222)],
      [48, s(1895, 1639, 2174)],
      [64, s(1845, 1563, 2083)],
      [72, s(1080, 962, 1266)],
    ],
  },
  {
    key: 'brawl600',
    name: 'Combat',
    detail: '600 mobs fighting in an arena',
    lithium: s(27.9, 25.6, 30.6),
    byWorkers: [
      [2, s(86, 82, 89)],
      [4, s(126, 123, 131)],
      [8, s(192, 185, 200)],
      [16, s(280, 267, 301)],
      [24, s(304, 296, 317)],
      [32, s(292, 282, 303)],
      [36, s(298, 286, 313)],
      [48, s(288, 279, 309)],
      [64, s(299, 271, 322)],
      [72, s(291, 265, 306)],
    ],
  },
];

export type Pair = { label: string; before: number; after: number; change: string };

export const flatWave: Pair[] = [
  { label: '600 mobs fighting in a 34×34 arena, 24 workers', before: 21, after: 168, change: '7.8×' },
  { label: '600 mobs in 6×6 blocks (one chunk), 8 workers', before: 107, after: 510, change: '4.8×' },
  { label: '700 mixed mobs in 4×4 chunks, 24 workers', before: 282, after: 821, change: '2.9×' },
  { label: '2,000 villagers in 8×8 chunks, 24 workers', before: 88, after: 187, change: '2.1×' },
  { label: '700 mixed mobs in 4×4 chunks, 8 workers', before: 335, after: 551, change: '1.6×' },
];

export const secondWave: Pair[] = [
  { label: 'Spread out', before: 261, after: 379, change: '+45%' },
  { label: 'Crowded base', before: 1440, after: 1965, change: '+36%' },
  { label: 'Combat', before: 237, after: 295, change: '+25%' },
];

export const tickPath: Pair[] = [
  { label: 'Combat, 24 workers', before: 180, after: 260, change: '+45%' },
  { label: 'Combat, 32 workers', before: 181, after: 235, change: '+30%' },
  { label: 'Combat, 48 workers', before: 164, after: 208, change: '+27%' },
  { label: 'Spread out, 8 workers', before: 109, after: 118, change: '+9%' },
  { label: 'Spread out, 24 workers', before: 197, after: 231, change: '+17%' },
  { label: 'Spread out, 48 workers', before: 226, after: 261, change: '+16%' },
  { label: 'Crowded base, 24 workers', before: 1313, after: 1293, change: 'no change' },
];

export const datasets = { flatWave, secondWave, tickPath };
