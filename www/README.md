# The JOUST docsite

The documentation site for JOUST: a landing page and the docs (Astro + Starlight), with AI-ready artifacts
(`/llms.txt`, `/llms-full.txt`, `/ai/starter-pack.md`, `/ai/catalog.json`).

```sh
npm install
npm run dev          # http://localhost:4321
npm run build        # dist/: the static site, the AI artifacts, then every internal link checked
npm run preview      # serves dist/
bash scripts/check-site.sh   # builds a private copy and checks links (safe to run alongside others)
```

- `src/pages/index.astro`: the landing page (styles in `src/styles/landing.css`, code samples in
  `src/components/landing/samples.mjs`).
- `src/content/docs/`: the docs. Every page's slug is in `sidebar.mjs`, which the AI artifacts follow too.
- `scripts/generate-ai.mjs`: `llms.txt`, `llms-full.txt`, the starter pack as raw markdown, and `catalog.json`
  (annotations read from `julc-state-api`'s sources, examples from `examples/README.md`): generated at build time.
- `site.config.mjs`: the site URL (or `JOUST_SITE_URL`), repository and summary.
- `WRITING.md`: how the docs are written (voice, accuracy rules, page shapes). Read it before editing a page.
