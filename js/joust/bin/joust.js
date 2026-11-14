#!/usr/bin/env node
// joust: JOUST contracts with npm only (ADR-0015 D9), the npm side of ADR-0017's one `joust` command.
//   joust build                 compile contracts/ (.java, .jsc) into build/joust/
//   joust deploy [--network n]  publish the reference scripts; write build/joust/deployments.<n>.json
//   joust dev [--network n] [--port p]  attach to the chain (DevKit/Yaci Store, or Blockfrost), build, deploy, rebuild
//                               on save, serve the page with the chain API proxied at /chain
//   joust serve [--network n] [--dir dist] [--port p]  serve a built page (vite build) as static files, with the chain
//                               API proxied at /chain (and a DevKit's faucet): nothing built, deployed or bundled
//   joust migrate [<file.jsc>…] [--in-place]   .jsc v1 files to .jsc 2 (into build/joust/migrated/, or in place)
//   joust version | help | new
//
// ADR-0017 D5/D9: version, help (also `<cmd> --help`) and new are answered here and never run project code. Project
// commands run at the npm project's root, and by the project's own @julclang/joust: a global or other copy hands over.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, realpathSync } from 'node:fs';
import { constants } from 'node:os';
import { fileURLToPath } from 'node:url';
import { locate, resolveJoust } from '../src/project.js';

const VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;
const SELF = realpathSync(fileURLToPath(import.meta.url));

// A hand-over names its target; only that target honours it, and nothing this process starts inherits it (D5.5).
const deferredTo = process.env.JOUST_DEFERRED;
delete process.env.JOUST_DEFERRED;
const handedOver = deferredTo === SELF;

const COMMANDS = {
  build: 'build [--accept-abi[=<Contract>@<committed>..<new>]] [--accept-lock] [--traces]   compile contracts/ (.java, .jsc) into build/joust/',
  abi: 'abi diff <Contract> [--out <file>]   the committed ABI manifest and the one the build computes now, with their SHA-256',
  test: 'test [-- <node --test args>]   build, then run test/*.test.js offline (an in-memory ledger: @julclang/joust/test)',
  deploy: 'deploy [--network <name>]   publish the reference scripts; write build/joust/deployments.<name>.json',
  dev: 'dev [--network <name>] [--port <port>] [--accept-abi]   build, deploy, rebuild on save, serve the page',
  serve: 'serve [--network <name>] [--dir <dir>] [--port <port>]   serve a built page with the chain API proxied',
  migrate: 'migrate [<file.jsc>…] [--in-place]   translate .jsc v1 files to .jsc 2, into build/joust/migrated/ (--in-place: replace them, keeping <Name>.jsc.v1)',
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
  if (!p) {
    // No trusted project from here (none, the filesystem root, or one in a directory another user owns): never act on
    // the working directory with this copy (ADR-0017 D9; PR #55 IC10).
    console.error(`joust: '${cmd}' needs a JOUST project, and none was found from ${process.cwd()} (a joust.config.json,`
      + ' or a package.json depending on @julclang/joust, in a directory you own; JOUST_SAFE_DIRS lists others).'
      + ' Create one with npm create joust.');
    return 2;
  }
  if (p.kind === 'gradle') {
    console.error(`joust: ${p.root} is a Gradle build; this is the npm joust. Use ./gradlew (or the native joust:`
      + ' brew install bloxbean/tap/joust).');
    return 2;
  }
  if (p?.kind === 'npm') {
    const own = resolveJoust(p.root);
    if (!own) {
      // The project chooses the implementation (ADR-0017 D4): never this copy in its place (PR #55 IC3).
      console.error(`joust: ${p.root} is an npm project without @julclang/joust installed; run npm install first`
        + ` (then joust ${cmd}, or npm run <script>)`);
      return 1;
    }
    if (own.bin !== SELF) {
      if (handedOver) {
        console.error(`joust: handed over to ${SELF}, but ${p.root} resolves to ${own.bin}; not handing over again`);
        return 1;
      }
      // Ctrl-C reaches the child too: this process waits for it and returns its status (128 + n for a signal).
      process.on('SIGINT', () => {});
      const r = spawnSync(process.execPath, [own.bin, ...args], {
        cwd: p.root, stdio: 'inherit', env: { ...process.env, JOUST_DEFERRED: own.bin },
      });
      if (r.status !== null) return r.status;
      return r.signal ? 128 + (constants.signals[r.signal] ?? 0) : 1;
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
      // ADR-0021 D7a (ADR-0018 D12 amended): --accept-abi=<Contract>@<committed-sha256|none>..<new-sha256> accepts that one
      // contract, bound to the two manifests reviewed; a bare --accept-abi accepts every changed manifest, as before.
      const bound = args.find((a) => a.startsWith('--accept-abi='));
      let acceptAbi = args.includes('--accept-abi');
      if (bound) {
        const { parseBoundAcceptance } = await import('../src/abi.js');
        acceptAbi = parseBoundAcceptance(bound.slice('--accept-abi='.length));
        if (!acceptAbi) {
          console.error('joust build: --accept-abi=<Contract>@<committed-sha256 or none>..<new-sha256> (from joust abi diff <Contract>)');
          return 2;
        }
      }
      const r = await build({ src: flag('src') ?? config.contracts, out: flag('out') ?? config.out, acceptAbi,
        acceptLock: args.includes('--accept-lock'), traces: args.includes('--traces') });
      return r.ok ? 0 : 1;
    }
    case 'abi': {
      // ADR-0021 D7a: `joust abi diff <Contract>`, what Accept ABI… shows before anything is accepted. Writes nothing into
      // the project (the build runs in memory); --out writes the diff as JSON (the extension's own storage).
      if (args[1] !== 'diff' || !args[2] || args[2].startsWith('-')) {
        console.error(`usage: joust ${COMMANDS.abi}`);
        return 2;
      }
      const { compile } = await import('../src/build.js');
      const { abiDiff } = await import('../src/abi.js');
      const r = await compile({ src: flag('src') ?? config.contracts });
      if (!r.generated?.length) {
        for (const d of r.diagnostics ?? []) console.error(`${d.severity}${d.code ? `[${d.code}]` : ''}: ${d.message}`);
        return 1;
      }
      const d = abiDiff({ generated: r.generated, srcDir: flag('src') ?? config.contracts, contract: args[2] });
      if (d.error) {
        console.error(`joust abi diff: ${d.error}`);
        return 1;
      }
      console.log(`--- committed ${d.path} sha256 ${d.committed.sha256}`);
      console.log(d.committed.text ?? '(none)');
      console.log(`+++ now sha256 ${d.now.sha256}`);
      console.log(d.now.text);
      console.log(d.committed.sha256 === d.now.sha256 ? 'unchanged' : `accept it with: joust build --accept-abi=${d.contract}@${d.committed.sha256}..${d.now.sha256}`);
      const out = flag('out');
      if (out) {
        const { writeFileSync } = await import('node:fs');
        writeFileSync(out, JSON.stringify(d, null, 2) + '\n');
      }
      return 0;
    }
    case 'migrate': {
      const { migrate } = await import('../src/migrate.js');
      const files = args.slice(1).filter((a) => !a.startsWith('--'));
      const r = await migrate({ src: config.contracts, out: config.out, files, inPlace: args.includes('--in-place') });
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
        port: flag('port') ? Number(flag('port')) : undefined, acceptAbi: args.includes('--accept-abi') });
      const stop = async () => { await d.close(); process.exit(0); };
      process.on('SIGINT', stop);
      process.on('SIGTERM', stop);
      return undefined;   // keeps running
    }
    case 'test': {
      // ADR-0018 D14 (M4c): build, then the project's tests (test/*.test.js) with Node's runner. They run offline when
      // they use @julclang/joust/test (an in-memory ledger, the validators evaluated by the runtime engine); nothing here
      // needs a chain.
      const { build } = await import('../src/build.js');
      const r = await build({ src: config.contracts, out: config.out, traces: args.includes('--traces') });
      if (!r.ok) return 1;
      const { readdirSync } = await import('node:fs');
      const tests = existsSync('test') ? readdirSync('test').filter((f) => f.endsWith('.test.js')).sort().map((f) => `test/${f}`) : [];
      if (!tests.length) {
        console.error('joust test: no test/*.test.js');
        return 1;
      }
      const t = spawnSync(process.execPath, ['--test', '--test-concurrency=1', ...(end >= 0 ? args.slice(end + 1) : []), ...tests],
        { stdio: 'inherit', env: { ...process.env, JOUST_OFFLINE: '1' } });
      return t.status ?? 1;
    }
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
