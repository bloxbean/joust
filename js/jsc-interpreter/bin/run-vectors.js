#!/usr/bin/env node
// jsc-run-vectors <Name.ir.json> <calls.json> <answers.json>
//
// The JavaScript leg of the differential tests (ADR-0018 J3, D15): evaluates every call of <calls.json> (a JSON array
// in the form of docs/reference/jsc-2-ir.md, "Values in vectors") on the program of <Name.ir.json>, and writes the
// answers, in the same order, to <answers.json>. The Gradle test compares them with the other four legs.

import { readFileSync, writeFileSync } from 'node:fs';
import { load, runCall } from '../src/index.js';

const [irFile, callsFile, answersFile] = process.argv.slice(2);
if (!answersFile) {
  console.error('usage: jsc-run-vectors <Name.ir.json> <calls.json> <answers.json>');
  process.exit(2);
}
const program = load(readFileSync(irFile, 'utf8'));
const calls = JSON.parse(readFileSync(callsFile, 'utf8'));
const t = Date.now();
const answers = calls.map((c) => runCall(program, c));
writeFileSync(answersFile, JSON.stringify(answers) + '\n');
const refused = answers.filter((a) => 'refused' in a).length;
console.log(`[jsc-interpreter] ${program.name}: ${calls.length} calls (${refused} refused) in ${Date.now() - t} ms`);
