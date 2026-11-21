import { byId, context2d, cssVar, onThemeChange, selectButton } from './common';

/** What a grid cell holds: open air, a full cube, or a partial shape (slab, fence, …). */
enum Block {
  Air,
  Cube,
  Partial,
}
type Preset = 'clear' | 'wall' | 'partial';
type Hit = 'clear' | 'cube' | 'partial';
type Visit = { bx: number; by: number; cell: Block };

const COLS = 12;
const ROWS = 7;
const MAX_STEPS = 40;
const FROM = { x: 1.5, y: 5.5 };
const TO = { x: 10.5, y: 1.5 };
/** Vanilla's ClipContext, BlockHitResult and Vec3 set-up, plus a mutable position per step (bytes). */
const VANILLA_BASE_BYTES = 248;
const VANILLA_BYTES_PER_STEP = 16;
/** The CollisionContext built lazily for a partial shape (bytes). */
const PARTIAL_CONTEXT_BYTES = 88;

const canvas = byId<HTMLCanvasElement>('losCanvas');
const presetButtons = { clear: byId('losPresetClear'), wall: byId('losPresetWall'), partial: byId('losPresetPartial') };

const emptyMap = (): Block[][] => Array.from({ length: ROWS }, () => Array<Block>(COLS).fill(Block.Air));
let map = emptyMap();

function setPreset(preset: Preset): void {
  map = emptyMap();
  if (preset === 'wall') {
    for (const y of [2, 3, 4]) map[y]![6] = Block.Cube;
  } else if (preset === 'partial') {
    map[3]![5] = Block.Partial;
    map[3]![6] = Block.Partial;
  }
  draw();
}

/** Amanatides–Woo voxel walk from FROM to TO, the same cell order Vanadium's fast path visits. */
function trace(): { visited: Visit[]; hit: Hit } {
  let bx = Math.floor(FROM.x);
  let by = Math.floor(FROM.y);
  const endX = Math.floor(TO.x);
  const endY = Math.floor(TO.y);
  const dx = TO.x - FROM.x;
  const dy = TO.y - FROM.y;
  const stepX = Math.sign(dx);
  const stepY = Math.sign(dy);
  const tDeltaX = dx === 0 ? Infinity : Math.abs(1 / dx);
  const tDeltaY = dy === 0 ? Infinity : Math.abs(1 / dy);
  let tMaxX = dx === 0 ? Infinity : (stepX > 0 ? bx + 1 - FROM.x : FROM.x - bx) * tDeltaX;
  let tMaxY = dy === 0 ? Infinity : (stepY > 0 ? by + 1 - FROM.y : FROM.y - by) * tDeltaY;

  const visited: Visit[] = [];
  for (let i = 0; i < MAX_STEPS; i++) {
    if (bx < 0 || bx >= COLS || by < 0 || by >= ROWS) break;
    const cell = map[by]![bx]!;
    visited.push({ bx, by, cell });
    if (cell === Block.Cube) return { visited, hit: 'cube' };
    if (cell === Block.Partial) return { visited, hit: 'partial' };
    if (bx === endX && by === endY) break;
    if (tMaxX < tMaxY) {
      tMaxX += tDeltaX;
      bx += stepX;
    } else {
      tMaxY += tDeltaY;
      by += stepY;
    }
  }
  return { visited, hit: 'clear' };
}

function draw(): void {
  const ctx = context2d(canvas);
  const { width, height } = canvas;
  const cw = width / COLS;
  const ch = height / ROWS;
  const { visited, hit } = trace();

  ctx.fillStyle = cssVar('--surface');
  ctx.fillRect(0, 0, width, height);
  for (const visit of visited) {
    ctx.fillStyle = cssVar(visit.cell === Block.Air ? '--vanadium-tint' : '--alert-tint');
    ctx.fillRect(visit.bx * cw, visit.by * ch, cw, ch);
  }
  for (let y = 0; y < ROWS; y++) {
    for (let x = 0; x < COLS; x++) {
      const cell = map[y]![x];
      if (cell === Block.Cube) {
        ctx.fillStyle = cssVar('--ink-muted');
        ctx.fillRect(x * cw + 2, y * ch + 2, cw - 4, ch - 4);
      } else if (cell === Block.Partial) {
        ctx.fillStyle = cssVar('--partial');
        ctx.fillRect(x * cw + cw * 0.28, y * ch + 3, cw * 0.44, ch - 6);
      }
      ctx.strokeStyle = cssVar('--rule');
      ctx.strokeRect(x * cw, y * ch, cw, ch);
    }
  }

  ctx.strokeStyle = cssVar(hit === 'clear' ? '--signal' : '--alert');
  ctx.lineWidth = 2.2;
  ctx.setLineDash([5, 4]);
  ctx.beginPath();
  ctx.moveTo(FROM.x * cw, FROM.y * ch);
  const last = visited.at(-1);
  if (hit === 'clear' || !last) ctx.lineTo(TO.x * cw, TO.y * ch);
  else ctx.lineTo((last.bx + 0.5) * cw, (last.by + 0.5) * ch);
  ctx.stroke();
  ctx.setLineDash([]);

  const endpoint = (point: { x: number; y: number }, colour: string, label: string) => {
    ctx.beginPath();
    ctx.arc(point.x * cw, point.y * ch, 8, 0, Math.PI * 2);
    ctx.fillStyle = colour;
    ctx.fill();
    ctx.fillStyle = '#fff';
    ctx.font = '600 10px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, point.x * cw, point.y * ch);
  };
  endpoint(FROM, cssVar('--vanadium'), 'A');
  endpoint(TO, cssVar('--signal'), 'B');

  const vanillaBytes = VANILLA_BASE_BYTES + visited.length * VANILLA_BYTES_PER_STEP;
  const vanadiumBytes = hit === 'partial' ? PARTIAL_CONTEXT_BYTES : 0;
  byId('losVanillaBytes').textContent = `${vanillaBytes} B`;
  byId('losVanadiumBytes').textContent = `${vanadiumBytes} B`;
  byId('losVanadiumBar').style.width = `${(vanadiumBytes / vanillaBytes) * 100}%`;

  const title = byId('losVerdict');
  const body = byId('losDetails');
  switch (hit) {
    case 'clear':
      title.textContent = 'Clear line of sight';
      title.style.color = cssVar('--signal-ink');
      body.innerHTML = `Traversed <strong>${visited.length} air cells</strong> in primitive registers using cached chunk sections. Zero heap allocations.`;
      break;
    case 'cube':
      title.textContent = 'Blocked by full cube';
      title.style.color = cssVar('--alert');
      body.innerHTML = `Stopped at step ${visited.length} using bit-exact <code>clipsFullCube()</code> with no <code>CollisionContext</code> or <code>Vec3</code> created. Zero heap allocations.`;
      break;
    case 'partial':
      title.textContent = 'Blocked by partial shape';
      title.style.color = cssVar('--partial');
      body.innerHTML = `Hit a non-cube shape at step ${visited.length}. Lazily constructs vanilla's <code>CollisionContext</code> only for this cell so complex hitboxes stay exact.`;
      break;
  }
}

canvas.addEventListener('click', (event) => {
  const rect = canvas.getBoundingClientRect();
  const x = Math.floor(((event.clientX - rect.left) / rect.width) * COLS);
  const y = Math.floor(((event.clientY - rect.top) / rect.height) * ROWS);
  const row = map[y];
  if (!row || x < 0 || x >= COLS) return;
  // The endpoints stay open air.
  if ((x === Math.floor(FROM.x) && y === Math.floor(FROM.y)) || (x === Math.floor(TO.x) && y === Math.floor(TO.y)))
    return;
  row[x] = ((row[x]! + 1) % 3) as Block;
  draw();
});

for (const [preset, button] of Object.entries(presetButtons) as [Preset, HTMLElement][]) {
  button.addEventListener('click', () => {
    selectButton(Object.values(presetButtons), button);
    setPreset(preset);
  });
}
onThemeChange(draw);
setPreset('wall');
