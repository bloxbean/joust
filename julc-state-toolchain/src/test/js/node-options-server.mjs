// The npm joust's NODE_OPTIONS parser (js/joust/src/toolchain.js parseNodeOptions, stripHeapFlags) for
// NodeOptionsFuzzTest: one value per line on stdin (base64 of its UTF-8 text), one answer per line on stdout:
//   {"args": [...], "stripped": "...", "strippedArgs": [...]}   or   {"error": "<message>"}
// Usage: node node-options-server.mjs <path to js/joust/src/toolchain.js>
// toolchain.js imports the runtime package's image loader, which this parser never uses: a resolve hook stands it in, so
// the server runs without the repository's packages linked.
import { register } from 'node:module';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

const stub = 'data:text/javascript,' + encodeURIComponent('export function loadImage() { throw new Error("not in this test"); }');
const hooks = `export async function resolve(specifier, context, next) {
  if (specifier === '@julclang/joust-runtime/load') return { url: ${JSON.stringify(stub)}, shortCircuit: true };
  return next(specifier, context);
}`;
register('data:text/javascript,' + encodeURIComponent(hooks));
const { parseNodeOptions, stripHeapFlags } = await import(pathToFileURL(process.argv[2]).href);

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of rl) {
  const value = Buffer.from(line, 'base64').toString('utf8');
  let answer;
  try {
    const args = parseNodeOptions(value);
    const stripped = stripHeapFlags(value);
    answer = { args, stripped, strippedArgs: parseNodeOptions(stripped) };
  } catch (e) {
    answer = { error: String(e?.message ?? e), kind: e?.constructor?.name };
  }
  process.stdout.write(JSON.stringify(answer) + '\n');
}
