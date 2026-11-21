import type { Element, ElementContent } from 'hast';
import { createCssVariablesTheme, type ShikiTransformer } from 'shiki';

/** Token colours come from CSS variables, so code follows the light/dark theme without a second render. */
export const vanadiumTheme = createCssVariablesTheme({
  name: 'vanadium',
  variablePrefix: '--shiki-',
  fontStyle: true,
});

const element = (
  tagName: string,
  className: string,
  children: ElementContent[],
  extra: Element['properties'] = {},
): Element => ({
  type: 'element',
  tagName,
  properties: { className: [className], ...extra },
  children,
});

/**
 * Wraps each highlighted block in a figure with a title bar and a copy button.
 * The title comes from the fence meta: ```toml title="config/vanadium.toml"
 */
export function codeFrame(): ShikiTransformer {
  return {
    name: 'vanadium-code-frame',
    root(root) {
      const raw = this.options.meta?.__raw ?? '';
      const title = /title="([^"]*)"/.exec(raw)?.[1] ?? this.options.lang;
      const pre = root.children.find((node): node is Element => node.type === 'element' && node.tagName === 'pre');
      if (!pre) return;
      const bar = element('figcaption', 'code-bar', [
        element('span', 'code-title', [{ type: 'text', value: title }]),
        element('button', 'code-copy', [{ type: 'text', value: 'Copy' }], { type: 'button', dataCopy: '' }),
      ]);
      root.children = [element('figure', 'code', [bar, pre], { dataLang: this.options.lang })];
    },
  };
}
