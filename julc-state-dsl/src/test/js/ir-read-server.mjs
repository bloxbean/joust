// The JavaScript IR reader (js/jsc-interpreter's load: strict JSON, validate.js, the Program it builds) for
// IrReaderFuzzTest: one IR export per line on stdin, base64 of its UTF-8 text; one answer per line on stdout, as JSON:
//   {"ok": true}                                  load accepted it
//   {"ok": false, "kind": "refused", ...}         it refused with its own errors (Error, SyntaxError)
//   {"ok": false, "kind": "crash", ...}           anything else (a TypeError, a RangeError): a finding
// Usage: node ir-read-server.mjs <path to js/jsc-interpreter/src/index.js>
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

const { load } = await import(pathToFileURL(process.argv[2]).href);
const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of rl) {
  let answer;
  try {
    load(Buffer.from(line, 'base64').toString('utf8'));
    answer = { ok: true };
  } catch (e) {
    const clean = e instanceof SyntaxError || (e instanceof Error && e.constructor === Error);
    answer = { ok: false, kind: clean ? 'refused' : 'crash', error: e?.constructor?.name ?? typeof e, message: String(e?.message ?? e).slice(0, 300) };
  }
  process.stdout.write(JSON.stringify(answer) + '\n');
}
