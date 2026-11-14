// The ABI gate of .jsc 2 (ADR-0018 D12; review F1, S3): each .jsc 2 file's manifest (<Contract>.abi.json beside it)
// is committed. A build compares the lowering's manifest with it: equal passes; a different or missing manifest fails
// (JSC0002, JSC0003) unless the build accepts it (joust build --accept-abi, joust dev --accept-abi), which writes it,
// to be reviewed and committed. Nothing else accepts. Needs nothing but Node.

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';

/** `line N: committed …, now …` for the first line where two manifests differ. */
export function firstDifference(committed, now) {
  const a = committed.split('\n'), b = now.split('\n');
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    // A trailing comma is not a difference of its own: an entry added after a line adds one to it.
    const x = i < a.length ? a[i].trim().replace(/,$/, '') : '(end)', y = i < b.length ? b[i].trim().replace(/,$/, '') : '(end)';
    if (x !== y) return `line ${i + 1}: committed ${x}, now ${y}`;
  }
  return '(whitespace only)';
}

/**
 * Checks (and with acceptAbi writes) the manifests of `generated` (the engine's: { jscFile, javaFile, abi }), the
 * .jsc files being under `srcDir`. Returns the errors, as lines; logs what it wrote. Adds the javaFile of each refused
 * manifest to `refused`: the build does not write that Java (security S9).
 */
export function abiGate({ generated = [], srcDir, root = process.cwd(), acceptAbi = false, log = console.log,
  refused = new Set() }) {
  const errors = [];
  for (const g of generated) {
    if (!g.abi) continue;   // .jsc v1: no manifest
    const committed = join(resolve(root, srcDir), dirname(g.jscFile), g.javaFile.split('/').pop().replace(/\.java$/, '.abi.json'));
    const exists = existsSync(committed);
    const old = exists ? readFileSync(committed, 'utf8') : null;
    if (old === g.abi) continue;
    if (acceptAbi === true) {   // only an explicit true accepts (a string "false" is not acceptance)
      writeFileSync(committed, g.abi);
      log(`${relative(root, committed)}: ${exists ? 'accepted the changed' : 'created the'} ABI manifest; commit it beside the .jsc file`);
      continue;
    }
    refused.add(g.javaFile);
    errors.push(exists
      ? `${g.jscFile}:1:1: error[JSC0002]: the ABI manifest ${relative(root, committed)} differs from the committed one at `
        + `${firstDifference(old, g.abi)}: a datum or redeemer encoding changed, which strands deployed instances; if the change is `
        + 'intended, run joust build --accept-abi and commit the new manifest'
      : `${g.jscFile}:1:1: error[JSC0003]: no ABI manifest ${relative(root, committed)}: the encoding of its datum and redeemers must be `
        + 'committed; create it with joust build --accept-abi, review it and commit it');
  }
  return errors;
}
