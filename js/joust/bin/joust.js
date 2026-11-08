#!/usr/bin/env node
// joust: JOUST contracts with npm only (ADR-0015 D9).
//   joust build                 compile contracts/ (.java, .jsc) into build/joust/
//   joust deploy [--network n]  publish the reference scripts; write build/joust/deployments.<n>.json
//   joust version
import { build, VERSION } from '../src/build.js';
import { deploy } from '../src/deploy.js';
import { loadConfig } from '../src/config.js';

const [cmd, ...rest] = process.argv.slice(2);
const flag = (name) => {
  const i = rest.indexOf('--' + name);
  return i >= 0 ? rest[i + 1] : undefined;
};

try {
  const config = loadConfig();
  switch (cmd) {
    case 'build': {
      const r = await build({ src: flag('src') ?? config.contracts, out: flag('out') ?? config.out });
      process.exit(r.ok ? 0 : 1);
      break;
    }
    case 'deploy':
      await deploy({ config, networkName: flag('network') });
      process.exit(0);
      break;
    case 'version':
    case '--version':
      console.log(VERSION);
      break;
    default:
      console.log('usage: joust build | deploy [--network <name>] | version');
      process.exit(cmd ? 1 : 0);
  }
} catch (e) {
  console.error('joust: ' + e.message);
  process.exit(1);
}
