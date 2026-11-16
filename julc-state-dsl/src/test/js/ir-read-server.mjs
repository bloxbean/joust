// The JavaScript IR reader (js/jsc-interpreter's load: strict JSON, validate.js, the Program it builds) for
// IrReaderFuzzTest: one IR export per line on stdin, base64 of its UTF-8 text; one answer per line on stdout, as JSON:
//   {"ok": true}                                  load accepted it
//   {"ok": false, "kind": "refused", ...}         it refused with its own errors (Error, SyntaxError); a validate.js
//                                                 refusal adds irWhere, irWhat, irCheck and, for an enumerated
//                                                 attribute, irNode
//   {"ok": false, "kind": "crash", ...}           anything else (a TypeError, a RangeError): a finding
// Usage: node ir-read-server.mjs <path to js/jsc-interpreter/src/index.js>   (or a copy of it: the C4 mutant control)
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
    // load() reports a TypeError or a RangeError thrown while validating as a plain Error whose cause is it: that is a
    // validator crash, never a clean refusal (review r1, security S3), so it is a finding of its own.
    const crashed = e?.cause instanceof TypeError || e?.cause instanceof RangeError;
    const clean = !crashed && (e instanceof SyntaxError || (e instanceof Error && e.constructor === Error));
    answer = { ok: false, kind: clean ? 'refused' : 'crash', error: e?.constructor?.name ?? typeof e, message: String(e?.message ?? e).slice(0, 300) };
    // validate.js's parts of a refusal (review r2, S13, C4): the test matches these, never the message's text.
    for (const k of ['irWhere', 'irWhat', 'irCheck', 'irNode']) if (typeof e?.[k] === 'string') answer[k] = e[k].slice(0, 300);
  }
  process.stdout.write(JSON.stringify(answer) + '\n');
}
