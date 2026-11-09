// Publishes the AI artifacts (generate-ai.mjs): written into dist/ by `astro build`, generated on request by
// `astro dev` (so they follow edits without a restart).
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateAi, SERVED } from './generate-ai.mjs';

const TYPES = { '.txt': 'text/plain; charset=utf-8', '.md': 'text/markdown; charset=utf-8', '.json': 'application/json' };

export default function llmsIntegration() {
  return {
    name: 'joust-llms',
    hooks: {
      'astro:build:done': async ({ dir, logger }) => {
        await generateAi({ outDir: fileURLToPath(dir), logger });
      },
      'astro:server:setup': async ({ server, logger }) => {
        const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'joust-ai-'));
        const quiet = { info: () => {} };
        server.middlewares.use(async (req, res, next) => {
          const url = (req.url ?? '').split('?')[0];
          if (!SERVED.includes(url)) return next();
          try {
            await generateAi({ outDir: tmp, logger: quiet });
            res.setHeader('content-type', TYPES[path.extname(url)] ?? 'text/plain');
            res.end(await fs.readFile(path.join(tmp, url)));
          } catch (e) {
            logger.error(`[ai] ${e.message}`);
            next(e);
          }
        });
      },
    },
  };
}
