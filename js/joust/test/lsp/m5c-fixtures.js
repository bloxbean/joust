// ADR-0021 M5c's fixtures: a library, its importer, a trusted npm project with the real engine as its compiler.
import { copyFileSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ENGINE } from './client.js';

export const FEES = `jsc 1
library app

type Fee = { bps: Int }

fn fee(amount: Int, f: Fee): Int = amount * f.bps / 10000
`;

export const USER = `jsc 1
package app

import { fee, Fee } from "./fees.jsc"

contract User {
  state { paid: Int }

  init() {
    return { paid: 0 }
  }

  action pay(amount: Int) {
    const f = Fee { bps: 30 }
    return { paid: paid + fee(amount, f) }
  }
}
`;

/** A trusted npm project with the real engine as its compiler: fees.jsc and User.jsc under contracts/app. */
export function crossProject(root) {
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of readdirSync(ENGINE).filter((x) => /^(joust-toolchain\.js(\.wasm)?|package\.json)$/.test(x))) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  writeFileSync(join(root, 'contracts', 'app', 'fees.jsc'), FEES);
  writeFileSync(join(root, 'contracts', 'app', 'User.jsc'), USER);
  return { fees: join(root, 'contracts', 'app', 'fees.jsc'), user: join(root, 'contracts', 'app', 'User.jsc') };
}

