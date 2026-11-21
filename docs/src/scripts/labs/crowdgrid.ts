import { byId, clamp, context2d, cssVar, onThemeChange } from './common';

type Mob = { x: number; y: number; r: number };
type Point = { x: number; y: number };

/** The section is 16 blocks wide; CrowdGrid splits it into 8×8 cells of 2 blocks. */
const SECTION = 16;
const CELL = 2;
const LAST_CELL = SECTION / CELL - 1;
const MOBS = 64;
const CLUSTERED = 42;
const SMALL_RADIUS = 0.68;
const LARGE_RADIUS = 1.85;

const vanillaCanvas = byId<HTMLCanvasElement>('cgVanillaCanvas');
const vanadiumCanvas = byId<HTMLCanvasElement>('cgVanadiumCanvas');

let mobs: Mob[] = [];
let query: Point = { x: 8.3, y: 6.5 };
let radius = SMALL_RADIUS;

const cellOf = (blocks: number) => clamp(Math.floor(blocks / CELL), 0, LAST_CELL);

function spawn(): void {
  mobs = Array.from({ length: MOBS }, (_, i) => {
    const clustered = i < CLUSTERED;
    const coordinate = () => (clustered ? 8 + (Math.random() - 0.5) * 10.5 : Math.random() * 15.2 + 0.4);
    return { x: clamp(coordinate(), 0.4, 15.6), y: clamp(coordinate(), 0.4, 15.6), r: 0.32 };
  });
  draw();
}

function draw(): void {
  const isLight = document.documentElement.dataset.theme === 'light';
  const colours = {
    background: cssVar('--surface'),
    grid: cssVar('--rule'),
    vanilla: cssVar('--vanilla'),
    signal: cssVar('--signal'),
    vanadium: cssVar('--vanadium'),
    vanadiumTint: cssVar('--vanadium-tint'),
    vanillaTint: cssVar('--vanilla-tint'),
    dim: isLight ? 'rgba(104,117,138,0.38)' : 'rgba(152,164,184,0.30)',
  };
  const box = {
    minX: Math.max(0, query.x - radius),
    maxX: Math.min(SECTION, query.x + radius),
    minY: Math.max(0, query.y - radius),
    maxY: Math.min(SECTION, query.y + radius),
  };
  const cells = { minX: cellOf(box.minX), maxX: cellOf(box.maxX), minY: cellOf(box.minY), maxY: cellOf(box.maxY) };

  let hits = 0;
  let tested = 0;

  const render = (canvas: HTMLCanvasElement, vanadium: boolean) => {
    const ctx = context2d(canvas);
    const { width, height } = canvas;
    const sx = width / SECTION;
    const sy = height / SECTION;
    ctx.fillStyle = colours.background;
    ctx.fillRect(0, 0, width, height);

    if (vanadium) {
      ctx.fillStyle = colours.vanadiumTint;
      for (let cx = cells.minX; cx <= cells.maxX; cx++) {
        for (let cy = cells.minY; cy <= cells.maxY; cy++)
          ctx.fillRect(cx * CELL * sx, cy * CELL * sy, CELL * sx, CELL * sy);
      }
      ctx.strokeStyle = colours.grid;
      ctx.lineWidth = 1;
      for (let i = 1; i <= LAST_CELL; i++) {
        ctx.beginPath();
        ctx.moveTo(i * CELL * sx, 0);
        ctx.lineTo(i * CELL * sx, height);
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(0, i * CELL * sy);
        ctx.lineTo(width, i * CELL * sy);
        ctx.stroke();
      }
    } else {
      ctx.fillStyle = colours.vanillaTint;
      ctx.fillRect(0, 0, width, height);
    }

    for (const mob of mobs) {
      const hit =
        mob.x + mob.r >= box.minX &&
        mob.x - mob.r <= box.maxX &&
        mob.y + mob.r >= box.minY &&
        mob.y - mob.r <= box.maxY;
      const inCell = !(
        cellOf(mob.x + mob.r) < cells.minX ||
        cellOf(mob.x - mob.r) > cells.maxX ||
        cellOf(mob.y + mob.r) < cells.minY ||
        cellOf(mob.y - mob.r) > cells.maxY
      );
      if (vanadium && inCell) tested++;
      if (!vanadium && hit) hits++;
      ctx.beginPath();
      ctx.arc(mob.x * sx, mob.y * sy, 4, 0, Math.PI * 2);
      ctx.fillStyle = hit ? colours.signal : !vanadium || inCell ? colours.vanilla : colours.dim;
      ctx.fill();
    }

    ctx.strokeStyle = colours.vanadium;
    ctx.lineWidth = 2;
    ctx.strokeRect(box.minX * sx, box.minY * sy, (box.maxX - box.minX) * sx, (box.maxY - box.minY) * sy);
  };

  render(vanillaCanvas, false);
  render(vanadiumCanvas, true);
  const visited = (cells.maxX - cells.minX + 1) * (cells.maxY - cells.minY + 1);
  const mode = visited === 1 ? 'wait-free 1-cell' : visited === 2 ? 'zero-scratch 2-cell' : `${visited} cells`;
  byId('cgVanillaCount').textContent = `${mobs.length} tested (${hits} hits)`;
  byId('cgVanadiumCount').textContent = `${tested} tested (${mode})`;
}

function follow(canvas: HTMLCanvasElement): void {
  const move = (event: PointerEvent) => {
    const rect = canvas.getBoundingClientRect();
    query = {
      x: clamp(((event.clientX - rect.left) / rect.width) * SECTION, 0.5, 15.5),
      y: clamp(((event.clientY - rect.top) / rect.height) * SECTION, 0.5, 15.5),
    };
    draw();
  };
  canvas.addEventListener('pointermove', move);
  canvas.addEventListener('pointerdown', move);
}

follow(vanillaCanvas);
follow(vanadiumCanvas);
byId('cgShuffleBtn').addEventListener('click', spawn);
const sizeButton = byId('cgSizeBtn');
sizeButton.addEventListener('click', () => {
  radius = radius === SMALL_RADIUS ? LARGE_RADIUS : SMALL_RADIUS;
  sizeButton.textContent = radius === SMALL_RADIUS ? 'Box size: small push' : 'Box size: multi-cell search';
  draw();
});
onThemeChange(draw);
spawn();
