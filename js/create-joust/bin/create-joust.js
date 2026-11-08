#!/usr/bin/env node
// npm create joust <dir>: a new JOUST app from the template (a Counter contract, a DevKit test).
import { cpSync, existsSync, readdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = resolve(process.argv[2] ?? 'joust-app');
if (existsSync(dir) && readdirSync(dir).length) {
  console.error(`create-joust: ${dir} is not empty`);
  process.exit(1);
}
cpSync(fileURLToPath(new URL('../template/', import.meta.url)), dir, { recursive: true });
renameSync(join(dir, 'gitignore'), join(dir, '.gitignore'));   // npm drops .gitignore from packages
const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
pkg.name = basename(dir).toLowerCase().replace(/[^a-z0-9-_.]/g, '-');
writeFileSync(join(dir, 'package.json'), JSON.stringify(pkg, null, 2) + '\n');
console.log(`Created ${dir}

  cd ${process.argv[2] ?? 'joust-app'}
  npm install
  npm run build
  npm run deploy     # needs a DevKit: yaci-devkit up --enable-yaci-store
  npm test`);
