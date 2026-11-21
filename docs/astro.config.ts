import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import tailwindcss from '@tailwindcss/vite';
import { codeFrame, vanadiumTheme } from './src/lib/shiki';

// DOCS_BASE lets a project-page deploy (https://<user>.github.io/Vanadium/) set its sub-path.
export default defineConfig({
  site: process.env.DOCS_SITE ?? 'https://synsejse.github.io',
  base: process.env.DOCS_BASE ?? '/',
  // architecture.html and friends keep the URLs the old static pages had.
  build: { format: 'file' },
  integrations: [mdx()],
  markdown: {
    shikiConfig: {
      theme: vanadiumTheme,
      transformers: [codeFrame()],
    },
  },
  vite: { plugins: [tailwindcss()] },
});
