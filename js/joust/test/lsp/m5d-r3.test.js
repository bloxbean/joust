// #120 round 2 (ADR-0021 r9 §15): S4 and C6, over the protocol. A project's own compiler (trusted, project-matched)
// whose lang.format answers with whitespace-only edits that change the program (the security review's probe engine):
// each is refused, since the bundled compiler does not format its text to the source's normal form. An honest answer
// from the same engine is still applied.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine (the bundled compiler checks)';
const EVIL = fileURLToPath(new URL('./fixtures/evil-format-engine/', import.meta.url));
const contract = (body) => `jsc 1\npackage app\n\ncontract C {\n  state { owner: Key, n: Int }\n\n  init() {\n    return { owner: caller, n: 0 }\n  }\n\n${body}\n}\n`;
const CASES = {
  EVILCOMMENT: contract('  action bump() {\n    // EVILCOMMENT the owner only\n    require(caller == owner, "only the owner")\n    return { ...state, n: n + 1 }\n  }'),
  EVILEXPOSE: contract('  action bump() {\n    // EVILEXPOSE   require(false, "x")\n    return { ...state, n: n + 1 }\n  }'),
  EVILSTRING: contract('  action bump() {\n    require(n >= 0, "Gold Coin") // EVILSTRING\n    return { ...state, n: n + 1 }\n  }'),
  EVILJOIN: contract('  action bump() {\n    // who is admin: EVILJOIN\n    return { ...state, n: n + 1 }\n  }'),
};

function project(dir) {
  mkdirSync(join(dir, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(dir, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(dir, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of readdirSync(EVIL)) copyFileSync(join(EVIL, f), join(pkg, 'engine', f));
  return join(dir, 'contracts', 'app', 'C.jsc');
}

test('S4, C6: a project compiler\'s meaning-changing whitespace edits are refused; an honest answer is applied', { skip, timeout: 180_000 }, async () => {
  const dir = tmp();
  const file = project(dir);
  const lsp = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true } });
  try {
    const uri = uriOf(file);
    let v = 1;
    const ask = async (text) => {
      if (v === 1) lsp.open(uri, text);
      else lsp.change(uri, v, text);
      await lsp.until(() => lsp.statuses.find((x) => x.uri === uri && x.modelVersion >= v), 60000, `the model of v${v}`);
      v++;
      return lsp.request('textDocument/formatting', { textDocument: { uri }, options: { tabSize: 2, insertSpaces: true } });
    };
    const honest = contract('  action bump() {\n    return { ...state, n: n + 1 }\n  }');
    assert.deepEqual(await ask(honest), [], 'an honest (no-change) answer from the project compiler');
    const st = lsp.statuses.find((x) => x.uri === uri && x.mode);
    assert.equal(st.source, 'project', JSON.stringify(st));
    assert.equal(st.mode, 'project-matched', JSON.stringify(st));
    for (const [name, text] of Object.entries(CASES)) {
      assert.equal(await ask(text), null, `${name}: the meaning-changing edit is refused`);
    }
    assert.ok(lsp.logs.some((l) => /refused the project compiler's answer/.test(l)), lsp.logs.join('\n'));
  } finally {
    await lsp.stop();
  }
});
