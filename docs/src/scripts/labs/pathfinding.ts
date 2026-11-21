import { byId } from './common';

/** Bytes per vanilla Node object. */
const NODE_BYTES = 64;
const SEARCHES_PER_BATCH = 10;
/** Bar scale floor, so the first batch does not fill the bars. */
const MIN_SCALE = 4000;

let searches = 0;
let vanillaNodes = 0;
let vanadiumNodes = 0;
let poolHigh = 0;

const kilobytes = (nodes: number) => ((nodes * NODE_BYTES) / 1024).toFixed(0);

function update(): void {
  const scale = Math.max(MIN_SCALE, vanillaNodes);
  byId('pfVanillaNodes').textContent = `${vanillaNodes.toLocaleString()} nodes (${kilobytes(vanillaNodes)} KB)`;
  byId('pfVanadiumNodes').textContent = `${vanadiumNodes.toLocaleString()} nodes (${kilobytes(vanadiumNodes)} KB)`;
  byId('pfVanillaBar').style.width = `${(vanillaNodes / scale) * 100}%`;
  byId('pfVanadiumBar').style.width = `${(vanadiumNodes / scale) * 100}%`;
  const caption = byId('pfCaption');
  const savings = byId('pfSavings');
  if (searches === 0) {
    caption.textContent = 'Run a batch of searches to compare cumulative Node allocations.';
    savings.textContent = '';
    return;
  }
  caption.textContent = `After ${searches} searches: pool warmed to ${poolHigh} reusable nodes; each search cloned only its ~14 winning nodes.`;
  savings.textContent = `−${((1 - vanadiumNodes / vanillaNodes) * 100).toFixed(1)}% allocations`;
}

byId('pfRunBtn').addEventListener('click', () => {
  for (let i = 0; i < SEARCHES_PER_BATCH; i++) {
    const explored = 380 + Math.floor(Math.random() * 55);
    const pathLength = 12 + Math.floor(Math.random() * 6);
    searches++;
    vanillaNodes += explored;
    // The pool only grows when a search explores more nodes than any before it; the path itself is copied out.
    if (explored > poolHigh) {
      vanadiumNodes += explored - poolHigh;
      poolHigh = explored;
    }
    vanadiumNodes += pathLength;
  }
  update();
});

byId('pfResetBtn').addEventListener('click', () => {
  searches = 0;
  vanillaNodes = 0;
  vanadiumNodes = 0;
  poolHigh = 0;
  update();
});

update();
