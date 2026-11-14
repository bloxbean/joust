// The ABI gate of .jsc 2 (ADR-0018 D12; review F1, S3): each .jsc 2 file's manifest (<Contract>.abi.json beside it)
// is committed. A build compares the lowering's manifest with it: equal passes; a different or missing manifest fails
// (JSC0002, JSC0003) unless the build accepts it (joust build --accept-abi, joust dev --accept-abi), which writes it,
// to be reviewed and committed. Nothing else accepts. Needs nothing but Node.

import { createHash } from 'node:crypto';
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

export const sha256 = (text) => createHash('sha256').update(text).digest('hex');

/**
 * ADR-0021 D7a, ADR-0018 D12 (amended by ADR-0021 r7): `--accept-abi=<Contract>@<committed-sha256>..<new-sha256>`
 * accepts the one contract, bound to the manifest the user reviewed (committed: its SHA-256, or `none` when nothing is
 * committed) and to the new one. `{ contract, committed, now }`, or null when `arg` is not of that form.
 */
export function parseBoundAcceptance(arg) {
  const m = /^([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)@(none|[0-9a-f]{64})\.\.([0-9a-f]{64})$/.exec(String(arg ?? ''));
  return m ? { contract: m[1], committed: m[2], now: m[3] } : null;
}

/** The committed manifest path of a generated file (the engine's { jscFile, javaFile }), under `srcDir`. */
export function manifestPath(g, srcDir, root = process.cwd()) {
  return join(resolve(root, srcDir), dirname(g.jscFile), g.javaFile.split('/').pop().replace(/\.java$/, '.abi.json'));
}

/** The contract a generated file is (its Java class's simple name). */
export const contractOf = (g) => g.javaFile.split('/').pop().replace(/\.java$/, '');

/** Its full identity (#99 S5): package plus name, `app.A` for app/A.java. */
export const qualifiedOf = (g) => g.javaFile.replace(/\.java$/, '').split('/').join('.');

/**
 * The one .jsc 2 contract `name` names (#99 S5): its qualified name, or a simple name that only one contract has.
 * `{ g }`, or `{ error }` (none, or ambiguous: the qualified candidates with their files).
 */
export function resolveContract(generated, name) {
  const all = generated.filter((x) => x.abi);
  const exact = all.filter((x) => qualifiedOf(x) === name);
  if (exact.length === 1) return { g: exact[0] };
  const simple = name.includes('.') ? [] : all.filter((x) => contractOf(x) === name);
  if (simple.length === 1) return { g: simple[0] };
  if (simple.length > 1) {
    return { error: `${name} is ambiguous: ${simple.map((x) => `${qualifiedOf(x)} (${x.jscFile})`).join(', ')}; name it by package` };
  }
  const known = all.map(qualifiedOf);
  return { error: `no .jsc 2 contract ${name} in this build${known.length ? ` (contracts: ${known.join(', ')})` : ''}` };
}

/**
 * `joust abi diff <Contract>` (D7a): the committed manifest and the one the build computes now, each with its SHA-256
 * (`none` when nothing is committed). `{ contract, path, committed: { sha256, text }, now: { sha256, text } }`, or
 * `{ error }`.
 */
export function abiDiff({ generated = [], srcDir, root = process.cwd(), contract }) {
  const { g, error } = resolveContract(generated, contract);
  if (error) return { error };
  const path = manifestPath(g, srcDir, root);
  const old = existsSync(path) ? readFileSync(path, 'utf8') : null;
  return { contract: qualifiedOf(g), file: g.jscFile, path: relative(root, path),
    committed: { sha256: old === null ? 'none' : sha256(old), text: old }, now: { sha256: sha256(g.abi), text: g.abi } };
}

/**
 * Checks (and with acceptAbi writes) the manifests of `generated` (the engine's: { jscFile, javaFile, abi }), the
 * .jsc files being under `srcDir`. Returns the errors, as lines; logs what it wrote. Adds the javaFile of each refused
 * manifest to `refused`: the build does not write that Java (security S9). `acceptAbi`: `true` accepts every changed
 * manifest (joust build --accept-abi); a bound acceptance ({ contract, committed, now }, ADR-0021 D7a) accepts only
 * that contract, and only when its committed manifest is still `committed` and the new one is `now`.
 */
export function abiGate({ generated = [], srcDir, root = process.cwd(), acceptAbi = false, log = console.log,
  refused = new Set() }) {
  const errors = [];
  const bound = acceptAbi && typeof acceptAbi === 'object' ? acceptAbi : null;
  // #99 S5: the bound names one contract (qualified, or a unique simple name); else nothing is accepted.
  const target = bound ? resolveContract(generated, bound.contract) : null;
  if (target?.error) errors.push(`error[JSC0002]: --accept-abi=${bound.contract}@…: ${target.error}; nothing accepted`);
  for (const g of generated) {
    if (!g.abi) continue;   // .jsc v1: no manifest
    const committed = manifestPath(g, srcDir, root);
    const exists = existsSync(committed);
    const old = exists ? readFileSync(committed, 'utf8') : null;
    if (old === g.abi) continue;
    if (acceptAbi === true) {   // only an explicit true accepts (a string "false" is not acceptance)
      writeFileSync(committed, g.abi);
      log(`${relative(root, committed)}: ${exists ? 'accepted the changed' : 'created the'} ABI manifest; commit it beside the .jsc file`);
      continue;
    }
    if (bound && target.g === g) {
      const was = old === null ? 'none' : sha256(old);
      const now = sha256(g.abi);
      if (was !== bound.committed) {
        refused.add(g.javaFile);
        errors.push(`${g.jscFile}:1:1: error[JSC0002]: not accepted: the committed manifest ${relative(root, committed)} is ${was === 'none' ? 'gone'
          : `now ${was}`}, not the ${bound.committed === 'none' ? 'absent one' : bound.committed} that was reviewed (it changed, a git pull say);`
          + ' review the diff again (Accept ABI…, joust abi diff)');
        continue;
      }
      if (now !== bound.now) {
        refused.add(g.javaFile);
        errors.push(`${g.jscFile}:1:1: error[JSC0002]: not accepted: the manifest the build computes now is ${now}, not the reviewed ${bound.now}`
          + ' (a source changed since the diff); review the diff again (Accept ABI…, joust abi diff)');
        continue;
      }
      writeFileSync(committed, g.abi);
      log(`${relative(root, committed)}: ${exists ? 'accepted the changed' : 'created the'} ABI manifest (${bound.committed} -> ${now}); commit it beside the .jsc file`);
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
