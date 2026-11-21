/** Shared helpers for the overview's interactive demos. */

/** The element with [id], which the demo's own component renders; a missing one is a markup bug. */
export function byId<T extends HTMLElement = HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (!element) throw new Error(`Demo element #${id} is missing`);
  return element as T;
}

export function context2d(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
  const context = canvas.getContext('2d');
  if (!context) throw new Error(`Canvas #${canvas.id} has no 2D context`);
  return context;
}

/** A theme token's current value, for canvas drawing, which cannot read CSS variables itself. */
export function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Runs [redraw] whenever the reader switches between light and dark. */
export function onThemeChange(redraw: () => void): void {
  window.addEventListener('vanadium-theme', redraw);
}

/** Marks [active] selected among [buttons]. */
export function selectButton(buttons: Iterable<Element>, active: Element): void {
  for (const button of buttons) button.classList.toggle('selected', button === active);
}

export const clamp = (value: number, min: number, max: number): number => Math.max(min, Math.min(max, value));
