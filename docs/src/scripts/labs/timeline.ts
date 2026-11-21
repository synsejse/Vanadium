import { byId, cssVar, reducedMotion, selectButton } from './common';

type Segment = { label: string; ms: number; color: string };
type ParallelSegment = Segment & { lanes: number };
type Workload = { vanilla: Segment[]; vanadium: ParallelSegment[] };
type WorkloadKey = 'combat' | 'mixed';

// Stage colours follow the legend in TickTimeline.astro.
const STAGE = {
  sched: '#8b63c9',
  spawn: '#2b958f',
  chunks: '#3d74d6',
  entities: '#4f9a5a',
  blockEntities: '#c98a1c',
  track: '#b9587c',
};

const workloads: Record<WorkloadKey, Workload> = {
  combat: {
    vanilla: [
      { label: '1. Sched', ms: 2.0, color: STAGE.sched },
      { label: '2. Spawn', ms: 7.2, color: STAGE.spawn },
      { label: '3. Chunks', ms: 4.3, color: STAGE.chunks },
      { label: '4. Entities (600 mobs)', ms: 52.5, color: STAGE.entities },
      { label: '5. BEs', ms: 5.2, color: STAGE.blockEntities },
      { label: '6. Track', ms: 4.4, color: STAGE.track },
    ],
    vanadium: [
      { label: '1', ms: 0.7, color: STAGE.sched, lanes: 3 },
      { label: '2. Spawn', ms: 1.8, color: STAGE.spawn, lanes: 4 },
      { label: '3', ms: 1.0, color: STAGE.chunks, lanes: 4 },
      { label: '4. Entities (flat)', ms: 7.4, color: STAGE.entities, lanes: 4 },
      { label: '5', ms: 1.4, color: STAGE.blockEntities, lanes: 3 },
      { label: '6', ms: 1.1, color: STAGE.track, lanes: 4 },
    ],
  },
  mixed: {
    vanilla: [
      { label: '1. Sched', ms: 4.6, color: STAGE.sched },
      { label: '2. Spawning', ms: 15.8, color: STAGE.spawn },
      { label: '3. Chunks', ms: 8.4, color: STAGE.chunks },
      { label: '4. Entities', ms: 22.0, color: STAGE.entities },
      { label: '5. Block entities', ms: 13.0, color: STAGE.blockEntities },
      { label: '6. Track', ms: 5.0, color: STAGE.track },
    ],
    vanadium: [
      { label: '1', ms: 1.4, color: STAGE.sched, lanes: 3 },
      { label: '2. Spawn', ms: 2.4, color: STAGE.spawn, lanes: 4 },
      { label: '3', ms: 1.5, color: STAGE.chunks, lanes: 4 },
      { label: '4. Entities', ms: 4.8, color: STAGE.entities, lanes: 4 },
      { label: '5. BEs', ms: 1.8, color: STAGE.blockEntities, lanes: 4 },
      { label: '6', ms: 1.2, color: STAGE.track, lanes: 4 },
    ],
  },
};

/** Milliseconds across the full track width; the 50 ms budget line sits at 62.5%. */
const TRACK_MS = 80;
const BUDGET_MS = 50;
/** Real milliseconds per simulated tick millisecond while replaying. */
const PLAYBACK_SLOWDOWN = 24;
const LANES = ['Server', 'Worker 1', 'Worker 2', 'Worker N'];

const vanillaBars = byId('tlVanillaBars');
const vanillaTime = byId('tlVanillaTime');
const vanadiumLanes = byId('tlVanadiumLanes');
const vanadiumTime = byId('tlVanadiumTime');
const workloadButtons = [byId('tlCombatBtn'), byId('tlMixedBtn')];

let active: WorkloadKey = 'combat';
let frame = 0;

const sum = (segments: Segment[]) => segments.reduce((total, segment) => total + segment.ms, 0);

function segment(startMs: number, shownMs: number, color: string, label: string, opacity = 1): HTMLDivElement {
  const element = document.createElement('div');
  element.className = 'tl-seg';
  element.style.left = `${(startMs / TRACK_MS) * 100}%`;
  element.style.width = `${(shownMs / TRACK_MS) * 100}%`;
  element.style.background = color;
  element.style.opacity = String(opacity);
  element.textContent = label;
  return element;
}

function render(progressMs: number): void {
  const workload = workloads[active];

  vanillaBars.replaceChildren();
  let start = 0;
  for (const stage of workload.vanilla) {
    const shown = Math.max(0, Math.min(stage.ms, progressMs - start));
    if (shown > 0) vanillaBars.append(segment(start, shown, stage.color, shown > 3.6 ? stage.label : ''));
    start += stage.ms;
  }
  const vanillaTotal = start;
  const vanillaNow = Math.min(progressMs, vanillaTotal);
  const overBudget = vanillaNow > BUDGET_MS;
  vanillaTime.textContent = `${vanillaNow.toFixed(1)} ms${overBudget ? ' (over 50 ms budget)' : ' / 50 ms'}`;
  vanillaTime.style.color = cssVar(overBudget ? '--alert' : '--vanilla-ink');

  vanadiumLanes.replaceChildren();
  const vanadiumTotal = sum(workload.vanadium);
  LANES.forEach((name, lane) => {
    const row = document.createElement('div');
    row.className = 'tl-lane-row';
    const label = document.createElement('div');
    label.className = 'tl-lane-name';
    label.textContent = name;
    const track = document.createElement('div');
    track.className = 'tl-lane-track';
    const budget = document.createElement('div');
    budget.className = 'tl-budget';
    track.append(budget);

    let laneStart = 0;
    for (const stage of workload.vanadium) {
      const stageStart = laneStart;
      laneStart += stage.ms;
      if (lane >= stage.lanes) continue;
      // Workers finish their share of a wave at slightly different times; the caller lane runs the full stage.
      const factor = lane === 0 ? 1 : 0.85 + ((lane * 5) % 3) * 0.06;
      const shown = Math.max(0, Math.min(stage.ms * factor, progressMs - stageStart));
      if (shown <= 0) continue;
      track.append(
        segment(stageStart, shown, stage.color, lane === 0 && shown > 1.7 ? stage.label : '', lane === 0 ? 1 : 0.86),
      );
    }
    row.append(label, track);
    vanadiumLanes.append(row);
  });
  const vanadiumNow = Math.min(progressMs, vanadiumTotal);
  vanadiumTime.textContent = `${vanadiumNow.toFixed(1)} ms${
    progressMs >= vanadiumTotal ? ` (${(vanillaTotal / vanadiumTotal).toFixed(1)}× faster)` : ' / 50 ms'
  }`;
}

function play(): void {
  cancelAnimationFrame(frame);
  const total = sum(workloads[active].vanilla);
  if (reducedMotion) {
    render(total);
    return;
  }
  const started = performance.now();
  const step = (now: number) => {
    const elapsed = (now - started) / PLAYBACK_SLOWDOWN;
    render(Math.min(elapsed, total));
    if (elapsed < total) frame = requestAnimationFrame(step);
  };
  frame = requestAnimationFrame(step);
}

function choose(workload: WorkloadKey, button: HTMLElement): void {
  active = workload;
  selectButton(workloadButtons, button);
  play();
}

byId('tlPlayBtn').addEventListener('click', play);
byId('tlCombatBtn').addEventListener('click', (event) => choose('combat', event.currentTarget as HTMLElement));
byId('tlMixedBtn').addEventListener('click', (event) => choose('mixed', event.currentTarget as HTMLElement));
play();
