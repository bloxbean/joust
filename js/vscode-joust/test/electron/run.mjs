// ADR-0021 D12: the extension's smoke and hostile-workspace tests with @vscode/test-electron, on the packaged .vsix
// (installed into a fresh extensions directory, so the packaged launch path is what runs), on the minimum VS Code
// (1.109.0) and the current stable. Scenarios:
//   smoke              the create-joust template (jsc), trusted: diagnostics on a seeded error, the outline, folding,
//                      the nesting cap, no code action on save, the Quick Fix on request;
//   hostile-trusted    a hostile workspace, trusted: only the project's two engine files load (sentinels never appear),
//                      serverInfo names the project's compiler, settings that would act on save apply nothing;
//   hostile-restricted the same workspace in Restricted Mode: the bundled compiler, advisory; no task; no sentinel;
//   abi                the template with its own CLI: Accept ABI… refuses unsaved buffers, diffs app.Counter by its
//                      qualified name, refuses a bound acceptance after the source changed, accepts after a new diff.
// Usage: node test/electron/run.mjs [--vsix <file>] [--versions 1.109.0,stable] [--only <scenario>]
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { downloadAndUnzipVSCode, resolveCliArgsFromVSCodeExecutablePath } from '@vscode/test-electron';
import { abiWorkspace, hostileWorkspace, templateWorkspace } from './workspaces.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const argv = process.argv.slice(2);
const opt = (k, d) => (argv.includes(k) ? argv[argv.indexOf(k) + 1] : d);
const vsix = resolve(opt('--vsix', join(root, 'vscode-joust.vsix')));
const versions = opt('--versions', process.env.VSCODE_VERSIONS ?? '1.109.0,stable').split(',');
const only = opt('--only');
const cache = resolve(process.env.VSCODE_TEST_CACHE ?? join(root, '.vscode-test'));
const engine = resolve(opt('--engine', join(root, 'engine')));
if (!existsSync(vsix)) throw new Error(`no ${vsix}: run npm run package first`);

const scenarios = [
  { name: 'smoke', short: 's', trusted: true, make: (base) => templateWorkspace(base) },
  { name: 'hostile-trusted', short: 'ht', trusted: true, make: (base) => hostileWorkspace(base, engine) },
  { name: 'hostile-restricted', short: 'hr', trusted: false, make: (base) => hostileWorkspace(base, engine) },
  { name: 'abi', short: 'ab', trusted: true, make: (base) => abiWorkspace(base, engine) },
].filter((s) => !only || s.name === only);

let failed = 0;
for (const version of versions) {
  const exe = await downloadAndUnzipVSCode({ version, cachePath: cache });
  const [cli, ...cliArgs] = resolveCliArgsFromVSCodeExecutablePath(exe);
  for (const s of scenarios) {
    // Short names: VS Code's IPC socket lives in the user data directory, and macOS bounds socket paths at 104 bytes.
    const base = realpathSync(mkdtempSync(join(tmpdir(), `jv-${s.short}-`)));
    const extDir = join(base, 'x');
    const userDir = join(base, 'u');
    mkdirSync(join(userDir, 'User'), { recursive: true });
    // Restricted Mode: trust on, no prompt, the folder left untrusted. Trusted runs pass --disable-workspace-trust.
    writeFileSync(join(userDir, 'User', 'settings.json'), JSON.stringify({
      'security.workspace.trust.enabled': true, 'security.workspace.trust.startupPrompt': 'never',
      'security.workspace.trust.untrustedFiles': 'open', 'security.workspace.trust.emptyWindow': false,
      'extensions.autoUpdate': false, ...(process.env.JOUST_TRACE ? { 'joust.trace.server': 'verbose' } : {}), 'extensions.autoCheckUpdates': false, 'telemetry.telemetryLevel': 'off', 'update.mode': 'none',
      // VS Code's built-in chat offers its own quick fixes for every diagnostic; without a model it never answers one on
      // save, which hangs a save with codeActionsOnSave "quickfix" in any language with markers (not this extension's).
      'chat.disableAIFeatures': true,
    }, null, 2));
    const install = spawnSync(cli, [...cliArgs, '--extensions-dir', extDir, '--user-data-dir', userDir, '--install-extension', vsix, '--force'],
      { encoding: 'utf8' });
    if (install.status !== 0) throw new Error(`installing the .vsix failed: ${install.stdout}${install.stderr}`);
    const ws = s.make(base);
    const sentinel = join(base, 'SENTINEL');
    console.log(`\n=== VS Code ${version}: ${s.name} (${ws.folder}) ===`);
    try {
      // As @vscode/test-electron's runTests launches it, except that runTests always adds --disable-workspace-trust:
      // here only the trusted scenarios do, so Restricted Mode is real.
      const args = [ws.folder, '--extensions-dir', extDir, '--user-data-dir', userDir, '--no-sandbox', '--disable-gpu-sandbox',
        '--disable-updates', '--skip-welcome', '--skip-release-notes', '--no-cached-data', '--disable-gpu',
        ...(s.trusted ? ['--disable-workspace-trust'] : []),
        `--extensionTestsPath=${join(root, 'test', 'electron', 'suite', 'index.js')}`,
        `--extensionDevelopmentPath=${join(root, 'test', 'electron', 'harness')}`];
      const env = { ...process.env, JOUST_LSP_DEBUG: process.env.JOUST_TRACE ?? '',
        JOUST_SCENARIO: s.name, JOUST_WS: ws.folder, JOUST_SENTINEL: sentinel, JOUST_VSCODE: version };
      delete env.ELECTRON_RUN_AS_NODE;
      const code = await new Promise((ok) => {
        const p = spawn(exe, args, { env, stdio: 'inherit' });
        p.on('exit', (c, sig) => ok(c ?? sig));
      });
      if (code !== 0) throw new Error(`Test run failed with code ${code}`);
      if (existsSync(sentinel)) throw new Error(`the sentinel ${sentinel} appeared`);
      console.log(`=== PASS ${version} ${s.name}`);
    } catch (e) {
      failed++;
      console.error(`=== FAIL ${version} ${s.name}: ${e?.message ?? e}`);
    }
  }
}
console.log(failed ? `${failed} scenario(s) failed` : `ALL PASS: ${scenarios.length} scenarios on ${versions.join(', ')}`);
process.exit(failed ? 1 : 0);

