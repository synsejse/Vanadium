import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { z } from 'astro/zod';

const docs = defineCollection({
  loader: glob({ pattern: '*.mdx', base: './src/content/docs' }),
  schema: z.object({
    title: z.string(),
    /** Label in the top navigation. */
    nav: z.string(),
    /** Position in the navigation and the previous/next links. */
    order: z.number(),
    summary: z.string(),
    /** Short facts shown beside the page title, one per line. */
    meta: z.array(z.string()).default([]),
    tocHeading: z.string().default('On this page'),
  }),
});

export const collections = { docs };
