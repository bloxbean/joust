#!/usr/bin/env node
// npm create joust <dir> [-- --template node|web] [--lang jsc|java]: a new JOUST app with a Counter contract.
import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import { scaffold, TEMPLATES, LANGS, DEFAULTS } from '../src/scaffold.js';

const USAGE = `Usage: npm create joust@latest [dir] -- [--template node|web] [--lang jsc|java]

  dir          the directory to create, new or empty (default: joust-app)
  --template   node: a Node script that deploys and calls the contract (default)
               web:  a browser page with wallets, served by joust dev
  --lang       jsc:  the contract in JOUST's .jsc language, contracts/app/Counter.jsc (default)
               java: the contract in Java, contracts/app/Counter.java`;

let args;
try {
  args = parseArgs({
    allowPositionals: true,
    options: {
      template: { type: 'string', short: 't', default: DEFAULTS.template },
      lang: { type: 'string', short: 'l', default: DEFAULTS.lang },
      help: { type: 'boolean', short: 'h' },
    },
  });
} catch (e) {
  console.error(`create-joust: ${e.message}\n\n${USAGE}`);
  process.exit(1);
}
const { values: o, positionals } = args;
if (o.help) {
  console.log(USAGE);
  process.exit(0);
}
if (positionals.length > 1 || !TEMPLATES.includes(o.template) || !LANGS.includes(o.lang)) {
  console.error(`create-joust: ${positionals.length > 1 ? 'one directory only' : `unknown ${!TEMPLATES.includes(o.template) ? `template '${o.template}'` : `lang '${o.lang}'`}`}\n\n${USAGE}`);
  process.exit(1);
}
const name = positionals[0] ?? 'joust-app';
const dir = resolve(name);
try {
  scaffold({ dir, template: o.template, lang: o.lang });
} catch (e) {
  console.error(`create-joust: ${e.message}`);
  process.exit(1);
}
const file = `contracts/app/Counter.${o.lang}`;
console.log(o.template === 'node' ? `Created ${dir}: ${file} and index.js, a Node script that calls it

  cd ${name}
  npm install
  npm start          # build, deploy and run index.js; needs a DevKit: yaci-devkit up --enable-yaci-store
  npm test` : `Created ${dir}: ${file} and a page (page/) that calls it

  cd ${name}
  npm install
  npm run build
  npm run deploy     # needs a DevKit: yaci-devkit up --enable-yaci-store
  npm test
  npm run dev        # the page at http://127.0.0.1:5178 (npm run dev:preview for preview)`);
