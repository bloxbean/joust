// @ts-check
// The JOUST docsite: a custom landing page (src/pages/index.astro) and Starlight docs (src/content/docs). The llms
// integration publishes the AI artifacts (/llms.txt, /llms-full.txt, /ai/starter-pack.md, /ai/catalog.json).
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import llmsIntegration from './scripts/llms-integration.mjs';
import { SITE } from './site.config.mjs';
import { SIDEBAR } from './sidebar.mjs';

export default defineConfig({
  site: SITE.url,
  integrations: [
    starlight({
      title: 'JOUST',
      description: SITE.description,
      // The Knight emblem: navy on the light theme, #eef2fd on the dark one (docs/assets/logo/ in the repository).
      logo: { light: './src/assets/joust-emblem.svg', dark: './src/assets/joust-emblem-on-dark.svg', replacesTitle: false },
      favicon: '/favicon.svg',
      social: [{ icon: 'github', label: 'GitHub', href: SITE.repo }],
      editLink: { baseUrl: `${SITE.repo}/edit/main/www/` },
      lastUpdated: false,
      customCss: ['./src/styles/starlight.css'],
      components: { Head: './src/components/overrides/Head.astro' },
      expressiveCode: {
        themes: ['github-dark-default', 'github-light'],
        // .jsc is JOUST's contract language; Kotlin's grammar highlights it closely (types, strings, comments).
        shiki: { langAlias: { jsc: 'kotlin' } },
        styleOverrides: { borderRadius: '0.6rem' },
      },
      sidebar: SIDEBAR,
    }),
    llmsIntegration(),
  ],
});
