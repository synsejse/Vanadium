import { byId, context2d, cssVar, onThemeChange, reducedMotion } from './common';

const LANES = 12;
const REPLAY_MS = 1500;

const before = byId<HTMLCanvasElement>('funnelBefore');
const after = byId<HTMLCanvasElement>('funnelAfter');
let frame = 0;

/** Draws both funnels at [progress] (0 to 1): workers queueing on one monitor, then the same workers lock-free. */
function draw(progress: number): void {
  const background = cssVar('--surface');
  const muted = cssVar('--ink-muted');
  const laneHeight = (height: number) => (height - 20) / LANES;

  {
    const ctx = context2d(before);
    const { width, height } = before;
    ctx.fillStyle = background;
    ctx.fillRect(0, 0, width, height);
    const gateX = width * 0.48;
    ctx.fillStyle = cssVar('--alert-tint');
    ctx.fillRect(gateX - 5, 8, 10, height - 16);
    ctx.strokeStyle = cssVar('--alert');
    ctx.strokeRect(gateX - 5, 8, 10, height - 16);
    const lane = laneHeight(height);
    for (let i = 0; i < LANES; i++) {
      const y = 12 + i * lane;
      const arrived = Math.min(1, progress * 2.2);
      // Lanes pass the gate one after another: the monitor admits one thread at a time.
      const passed = Math.max(0, Math.min(1, (progress - (i / LANES) * 0.72) * 3.2));
      ctx.fillStyle = cssVar('--vanilla');
      ctx.globalAlpha = 0.45;
      ctx.fillRect(12, y, (gateX - 20) * arrived, Math.max(3, lane - 3));
      ctx.globalAlpha = 1;
      if (passed > 0) {
        ctx.fillStyle = cssVar('--alert');
        ctx.fillRect(gateX + 8, y, (width - gateX - 24) * passed * 0.45, Math.max(3, lane - 3));
      }
    }
    ctx.fillStyle = muted;
    ctx.font = '500 10.5px sans-serif';
    ctx.fillText('ItemHolder monitor (1 thread at a time)', gateX - 92, height - 3);
  }

  {
    const ctx = context2d(after);
    const { width, height } = after;
    ctx.fillStyle = background;
    ctx.fillRect(0, 0, width, height);
    const mapX = width * 0.48;
    ctx.strokeStyle = cssVar('--rule');
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(mapX, 8);
    ctx.lineTo(mapX, height - 14);
    ctx.stroke();
    ctx.setLineDash([]);
    const lane = laneHeight(height);
    for (let i = 0; i < LANES; i++) {
      const jitter = 0.85 + ((i * 7) % 4) * 0.05;
      ctx.fillStyle = cssVar(i % 4 === 0 ? '--signal' : '--vanadium');
      ctx.fillRect(12, 12 + i * lane, (width - 24) * Math.min(1, progress * 1.4 * jitter), Math.max(3, lane - 3));
    }
    ctx.fillStyle = muted;
    ctx.font = '500 10.5px sans-serif';
    ctx.fillText('NonBlockingHashMapLong (all workers lock-free)', mapX - 108, height - 3);
  }
}

function replay(): void {
  cancelAnimationFrame(frame);
  if (reducedMotion) {
    draw(1);
    return;
  }
  const started = performance.now();
  const step = (now: number) => {
    const progress = Math.min(1, (now - started) / REPLAY_MS);
    draw(progress);
    if (progress < 1) frame = requestAnimationFrame(step);
  };
  frame = requestAnimationFrame(step);
}

byId('funnelReplayBtn').addEventListener('click', replay);
onThemeChange(() => draw(1));
draw(1);
