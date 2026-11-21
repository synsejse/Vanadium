import * as Plot from '@observablehq/plot';

export type Palette = Record<
  | 'ink'
  | 'ink2'
  | 'muted'
  | 'rule'
  | 'ruleStrong'
  | 'surface'
  | 'vanadium'
  | 'vanilla'
  | 'signal'
  | 'partial'
  | 'alert',
  string
>;

/** Plot writes colours into SVG attributes, where var() does not resolve, so read the live tokens. */
export function palette(): Palette {
  const style = getComputedStyle(document.documentElement);
  const v = (name: string) => style.getPropertyValue(name).trim();
  return {
    ink: v('--ink'),
    ink2: v('--ink-secondary'),
    muted: v('--ink-muted'),
    rule: v('--rule'),
    ruleStrong: v('--rule-strong'),
    surface: v('--surface'),
    vanadium: v('--vanadium'),
    vanilla: v('--vanilla'),
    signal: v('--signal'),
    partial: v('--partial'),
    alert: v('--alert'),
  };
}

export const baseStyle = (p: Palette) => ({
  background: 'transparent',
  color: p.ink2,
  fontFamily: 'var(--font-ui)',
  fontSize: '12px',
  overflow: 'visible',
});

/** Draws [render] into [el] now, again on resize and on every theme change. */
export function mount(el: HTMLElement, render: (width: number, p: Palette) => Element) {
  let lastWidth = 0;
  const draw = () => {
    lastWidth = el.clientWidth;
    el.replaceChildren(render(lastWidth, palette()));
  };
  draw();
  new ResizeObserver(() => {
    if (Math.abs(el.clientWidth - lastWidth) > 8) draw();
  }).observe(el);
  window.addEventListener('vanadium-theme', draw);
  return draw;
}

export { Plot };
