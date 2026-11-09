#!/usr/bin/env node
// joust-gen <description.json>... --out <dir> [--import <module>]
// Writes <Contract>.js and <Contract>.d.ts for each JOUST contract description (META-INF/joust/*.json).

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { generate } from '../src/codegen.js';

const argv = process.argv.slice(2);
let out = '.';
let importFrom;
const files = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--out') out = argv[++i];
  else if (argv[i] === '--import') importFrom = argv[++i];
  else files.push(argv[i]);
}
if (!files.length) {
  console.error('usage: joust-gen <description.json>... --out <dir> [--import <module>]');
  process.exit(1);
}
mkdirSync(out, { recursive: true });
for (const f of files) {
  const d = JSON.parse(readFileSync(f, 'utf8'));
  const { js, dts } = generate(d, importFrom ? { importFrom } : {});
  const name = d['x-joust'].contract;
  writeFileSync(join(out, name + '.js'), js);
  writeFileSync(join(out, name + '.d.ts'), dts);
  console.log(`${f} -> ${join(out, name)}.js, .d.ts`);
}
