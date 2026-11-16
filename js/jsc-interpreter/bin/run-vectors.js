#!/usr/bin/env node
// jsc-run-vectors <Name.ir.json> <calls.json> <answers.json>
//
// The JavaScript leg of the differential tests (ADR-0018 J3, D15): evaluates every call of <calls.json> (a JSON array
// in the form of docs/reference/jsc-2-ir.md, "Values in vectors") on the program of <Name.ir.json>, and writes the
// answers, in the same order, to <answers.json>.
//
// The calls run in a worker whose stack is STACK_MB (semantics §2.4, review S1): Node's default stack ends a recursive
// fn near depth 500, far below what a transaction's budget allows (thousands), and a call this interpreter cannot finish
// is answered { limit }, never as a refusal.

import { readFileSync, writeFileSync } from 'node:fs';
import { Worker, isMainThread, workerData, parentPort } from 'node:worker_threads';
import { load, runCall } from '../src/index.js';

export const STACK_MB = 512;

if (isMainThread) {
  const [irFile, callsFile, answersFile] = process.argv.slice(2);
  if (!answersFile) {
    console.error('usage: jsc-run-vectors <Name.ir.json> <calls.json> <answers.json>');
    process.exit(2);
  }
  const worker = new Worker(new URL(import.meta.url), { workerData: { irFile, callsFile, answersFile }, resourceLimits: { stackSizeMb: STACK_MB } });
  worker.on('message', (line) => console.log(line));
  worker.on('error', (e) => {
    console.error(e);
    process.exitCode = 1;
  });
} else {
  const { irFile, callsFile, answersFile } = workerData;
  const program = load(readFileSync(irFile, 'utf8'));
  const calls = JSON.parse(readFileSync(callsFile, 'utf8'));
  const t = Date.now();
  const answers = calls.map((c) => runCall(program, c));
  writeFileSync(answersFile, JSON.stringify(answers) + '\n');
  const refused = answers.filter((a) => 'refused' in a).length, limits = answers.filter((a) => 'limit' in a).length;
  parentPort.postMessage(`[jsc-interpreter] ${program.name}: ${calls.length} calls (${refused} refused, ${limits} over a limit) in `
    + `${Date.now() - t} ms, stack ${STACK_MB} MiB`);
}
