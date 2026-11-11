#!/usr/bin/env node
// joust: JOUST contracts with npm only (ADR-0015 D9), the npm side of ADR-0017's one `joust` command.
//   joust build                 compile contracts/ (.java, .jsc) into build/joust/
//   joust deploy [--network n]  publish the reference scripts; write build/joust/deployments.<n>.json
//   joust dev [--network n] [--port p]  attach to the chain (DevKit/Yaci Store, or Blockfrost), build, deploy, rebuild
//                               on save, serve the page with the chain API proxied at /chain
//   joust serve [--network n] [--dir dist] [--port p]  serve a built page (vite build) as static files, with the chain
//                               API proxied at /chain (and a DevKit's faucet): nothing built, deployed or bundled
//   joust version | help | new
//
// ADR-0017 D5/D9: version, help (also `<cmd> --help`) and new are answered here and never run project code. Project
// commands run at the npm project's root, and by the project's own @julclang/joust: a global or other copy hands over.
import { spawnSync } from 'node:child_process';
import { readFileSync, realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { locate, resolveJoust } from '../src/project.js';

const VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;
const SELF = realpathSync(fileURLToPath(import.meta.url));

// A hand-over names its target; only that target honours it, and nothing this process starts inherits it (D5.5).
const deferredTo = process.env.JOUST_DEFERRED;
delete process.env.JOUST_DEFERRED;
const handedOver = deferredTo === SELF;

const COMMANDS = {
  build: 'build                       compile contracts/ (.java, .jsc) into build/joust/',
  deploy: 'deploy [--network <name>]   publish the reference scripts; write build/joust/deployments.<name>.json',
  dev: 'dev [--network <name>] [--port <port>]   build, deploy, rebuild on save, serve the page',
  serve: 'serve [--network <name>] [--dir <dir>] [--port <port>]   serve a built page with the chain API proxied',
  version: 'version                     this joust\'s version',
  help: 'help [<command>]            this help',
  new: 'new <dir>                   how to create a project (npm create joust, or the native joust new)',
};

const usage = (topic) => {
  if (topic && COMMANDS[topic]) return `joust ${COMMANDS[topic]}`;
  return [`joust ${VERSION} (npm): JOUST contracts with npm`, '', 'Usage: joust <command> [args]', '',
    ...Object.values(COMMANDS).map((l) => `  ${l}`), '',
    'Tests run with npm test. Gradle (Java) projects come from the native joust: brew install bloxbean/tap/joust.'].join('\n');
};

const args = process.argv.slice(2);
const [cmd] = args;
const end = args.indexOf('--');
const wantsHelp = (end < 0 ? args : args.slice(0, end)).some((a) => a === '--help' || a === '-h');

function newCommand(rest) {
  const opt = (short, long) => {
    const i = rest.findIndex((a) => a === short || a === long);
    return i >= 0 ? rest[i + 1] : undefined;
  };
  const dir = rest.find((a, i) => !a.startsWith('-') && !['-t', '--template', '-l', '--lang', '-p', '--package']
    .includes(rest[i - 1])) ?? 'joust-app';
  const template = opt('-t', '--template');
  if (template === 'gradle' || opt('-p', '--package') !== undefined) {
    console.error('joust new: Gradle (Java) projects come from the native joust (brew install bloxbean/tap/joust, or the'
      + ' release archive at https://github.com/bloxbean/joust/releases): joust new ' + dir);
    return 2;
  }
  const flags = [template && `--template ${template}`, opt('-l', '--lang') && `--lang ${opt('-l', '--lang')}`]
    .filter(Boolean).join(' ');
  console.error(`joust new: the npm joust creates nothing itself. Run:\n\n  npm create joust ${dir}${flags ? ` -- ${flags}` : ''}\n`);
  return 2;
}

async function project() {
  const p = locate();
  if (p?.kind === 'npm') {
    const own = resolveJoust(p.root);
    if (own && own.bin !== SELF) {
      if (handedOver) {
        console.error(`joust: handed over to ${SELF}, but ${p.root} resolves to ${own.bin}; not handing over again`);
        return 1;
      }
      const r = spawnSync(process.execPath, [own.bin, ...args], {
        cwd: p.root, stdio: 'inherit', env: { ...process.env, JOUST_DEFERRED: own.bin },
      });
      return r.status ?? 1;
    }
    process.chdir(p.root);   // as npm run: joust.config.json and contracts/ are the root's
  }
  const { loadConfig } = await import('../src/config.js');
  const config = loadConfig();
  const flag = (name) => {
    const i = args.indexOf('--' + name);
    return i >= 0 ? args[i + 1] : undefined;
  };
  switch (cmd) {
    case 'build': {
      const { build } = await import('../src/build.js');
      const r = await build({ src: flag('src') ?? config.contracts, out: flag('out') ?? config.out });
      return r.ok ? 0 : 1;
    }
    case 'deploy': {
      const { deploy } = await import('../src/deploy.js');
      await deploy({ config, networkName: flag('network') });
      return 0;
    }
    case 'dev':
    case 'serve': {
      const run = cmd === 'dev' ? (await import('../src/dev.js')).dev : (await import('../src/serve.js')).serve;
      const d = await run({ config, networkName: flag('network'), dir: flag('dir'),
        port: flag('port') ? Number(flag('port')) : undefined });
      const stop = async () => { await d.close(); process.exit(0); };
      process.on('SIGINT', stop);
      process.on('SIGTERM', stop);
      return undefined;   // keeps running
    }
    case 'test':
      console.error('joust: the npm joust has no test command; run npm test');
      return 2;
    default:
      console.error(`joust: unknown command '${cmd}' (this is the npm joust, @julclang/joust ${VERSION})\n\n${usage()}`);
      return 2;
  }
}

async function main() {
  if (!cmd) {
    console.log(usage());
    return 0;
  }
  if (wantsHelp) {
    console.log(usage(cmd.startsWith('-') || cmd === 'help' ? args[1] : cmd));
    return 0;
  }
  if (['version', '--version', '-V', '-v'].includes(cmd)) {
    console.log(`joust ${VERSION} (npm)`);
    return 0;
  }
  if (cmd === 'help') {
    console.log(usage(args[1]));
    return 0;
  }
  if (cmd === 'new') return newCommand(args.slice(1));
  if (cmd.startsWith('-')) {
    console.error(`joust: unknown option ${cmd} (this is the npm joust; see joust help)`);
    return 2;
  }
  return project();
}

try {
  const code = await main();
  if (code !== undefined) process.exit(code);
} catch (e) {
  console.error('joust: ' + e.message);
  process.exit(1);
}
