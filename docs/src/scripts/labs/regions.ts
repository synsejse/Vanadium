import { byId, selectButton } from './common';

type Chunk = { x: number; y: number };
type Region = Chunk & { id: string };

const GRID = 10;
const REGIONS: Region[] = [
  { id: 'A', x: 1, y: 1 },
  { id: 'B', x: 3, y: 2 },
  { id: 'C', x: 7, y: 1 },
  { id: 'D', x: 1, y: 5 },
  { id: 'E', x: 4, y: 4 },
  { id: 'F', x: 8, y: 4 },
  { id: 'G', x: 2, y: 8 },
  { id: 'H', x: 6, y: 7 },
  { id: 'I', x: 8, y: 8 },
];
const IDLE_STATS = `Round 0: ${REGIONS.length} active regions. Vanilla requires ${REGIONS.length} serial steps.`;

const grid = byId('regionGrid');
const status = byId('regStatus');
const stats = byId('regStats');

let isolation = 2;
let selected: Chunk = { x: 4, y: 4 };
let round = 0;
const done = new Set<string>();
const running = new Set<string>();

const chebyshev = (a: Chunk, b: Chunk) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));

function resetWave(): void {
  running.clear();
  done.clear();
  round = 0;
}

function inspect(chunk: Chunk): void {
  resetWave();
  selected = chunk;
  const clear = REGIONS.filter((region) => chebyshev(region, chunk) > isolation).length;
  const side = isolation * 2 + 1;
  status.innerHTML =
    `Inspecting chunk <code>(${chunk.x}, ${chunk.y})</code> with <code>isolation = ${isolation}</code>. ` +
    `Its safety square spans <strong>${side}×${side}</strong> chunks; ` +
    `<strong>${clear}</strong> active regions lie outside it and may run concurrently.`;
  stats.textContent = `Inspect mode: ${clear} of ${REGIONS.length} regions clear of (${chunk.x}, ${chunk.y}).`;
  render();
}

function cellState(chunk: Chunk, region: Region | undefined): string | null {
  if (running.size > 0) {
    if (region && running.has(region.id)) return 'claimed';
    if (region && done.has(region.id)) return 'done';
    const runningRegions = REGIONS.filter((r) => running.has(r.id));
    return runningRegions.some((r) => chebyshev(r, chunk) <= isolation) ? 'halo' : null;
  }
  const distance = chebyshev(selected, chunk);
  if (distance === 0) return 'claimed';
  if (distance <= isolation) return 'halo';
  return region ? 'safe' : null;
}

function render(): void {
  const cells: HTMLButtonElement[] = [];
  for (let y = 0; y < GRID; y++) {
    for (let x = 0; x < GRID; x++) {
      const chunk = { x, y };
      const region = REGIONS.find((r) => r.x === x && r.y === y);
      const cell = document.createElement('button');
      cell.type = 'button';
      cell.className = 'chunk-btn';
      cell.setAttribute('aria-label', region ? `Region ${region.id} at chunk ${x}, ${y}` : `Chunk ${x}, ${y}`);
      if (region) cell.textContent = region.id;
      const state = cellState(chunk, region);
      if (state) cell.classList.add(state);
      cell.addEventListener('click', () => inspect(chunk));
      cells.push(cell);
    }
  }
  grid.replaceChildren(...cells);
}

const isolationButtons = [...document.querySelectorAll<HTMLButtonElement>('[data-iso]')];
for (const button of isolationButtons) {
  button.addEventListener('click', () => {
    isolation = Number(button.dataset.iso);
    selectButton(isolationButtons, button);
    resetWave();
    status.innerHTML = `Isolation set to <code>${isolation}</code> chunks. Select a chunk or step the parallel wave.`;
    stats.textContent = IDLE_STATS;
    render();
  });
}

byId('regStepBtn').addEventListener('click', () => {
  for (const id of running) done.add(id);
  running.clear();
  if (done.size === REGIONS.length) resetWave();
  // Greedy colouring: a region joins this round if it is clear of every region already chosen.
  const chosen: Region[] = [];
  for (const region of REGIONS) {
    if (done.has(region.id)) continue;
    if (chosen.every((other) => chebyshev(other, region) > isolation)) {
      chosen.push(region);
      running.add(region.id);
    }
  }
  round++;
  const scheduled = done.size + running.size;
  status.innerHTML = `Round ${round} executes <strong>${chosen.length} regions concurrently</strong> (${chosen.map((r) => r.id).join(', ')}) with zero buffer overlap.`;
  stats.textContent = `Round ${round}: ${scheduled}/${REGIONS.length} regions scheduled (${scheduled} serial steps in Vanilla).`;
  render();
});

byId('regResetBtn').addEventListener('click', () => {
  resetWave();
  selected = { x: 4, y: 4 };
  status.innerHTML =
    'Select any chunk cell to inspect its Chebyshev isolation halo, or press <strong>Step parallel wave</strong>.';
  stats.textContent = IDLE_STATS;
  render();
});

render();
