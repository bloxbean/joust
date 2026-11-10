// Generates the site's diagrams (src/assets/diagrams/*.svg): block diagrams in a C4 style, one visual language.
//   node scripts/diagrams.mjs
// Each diagram is drawn on its own light panel, so it reads the same in the docs' light and dark themes.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/assets/diagrams');
const C = {
  bg: '#f7f8fc', line: '#d4d7ef', text: '#151729', muted: '#5b6074', white: '#ffffff',
  indigo: '#4f4ce0', indigoSoft: '#eceeff', indigoLine: '#b9b8f6',
  amber: '#c46a06', amberSoft: '#fff4e2', amberLine: '#f2c47c',
  green: '#0f7a55', greenSoft: '#e6f6ef', greenLine: '#9fd9c0',
  slate: '#3a3f5c', slateSoft: '#eef0f6', slateLine: '#c3c7da',
};
const TONES = {
  indigo: [C.indigoSoft, C.indigoLine, C.indigo],
  amber: [C.amberSoft, C.amberLine, C.amber],
  green: [C.greenSoft, C.greenLine, C.green],
  slate: [C.slateSoft, C.slateLine, C.slate],
  white: [C.white, C.line, C.slate],
};
const FONT = `Inter, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif`;
const MONO = `'JetBrains Mono', ui-monospace, Menlo, monospace`;
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** Words into lines of at most {@code max} characters. */
function wrap(text, max) {
  const lines = [];
  let cur = '';
  for (const w of String(text).split(' ')) {
    if ((cur + ' ' + w).trim().length > max && cur) {
      lines.push(cur);
      cur = w;
    } else cur = (cur + ' ' + w).trim();
  }
  if (cur) lines.push(cur);
  return lines;
}

function svg(w, h, title, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" role="img" aria-label="${esc(title)}" font-family="${FONT}">
  <title>${esc(title)}</title>
  <defs>
    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 10 5 0 10z" fill="${C.slate}"/></marker>
    <marker id="arrow-indigo" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 10 5 0 10z" fill="${C.indigo}"/></marker>
    <marker id="arrow-amber" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 10 5 0 10z" fill="${C.amber}"/></marker>
    <filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#1b1d3a" flood-opacity="0.08"/></filter>
  </defs>
  <rect width="${w}" height="${h}" rx="18" fill="${C.bg}"/>
${body}
</svg>
`;
}

/** A C4-style element: title, [technology], description lines. */
function box(x, y, w, h, { title, tech, text, tone = 'white', mono = false, size = 15 }) {
  const [fill, stroke, ink] = TONES[tone];
  let out = `  <g filter="url(#shadow)"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="12" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/></g>\n`;
  let ty = y + 26;
  out += `  <text x="${x + w / 2}" y="${ty}" text-anchor="middle" font-size="${size}" font-weight="700" fill="${C.text}"${mono ? ` font-family="${MONO}"` : ''}>${esc(title)}</text>\n`;
  if (tech) {
    ty += 19;
    out += `  <text x="${x + w / 2}" y="${ty}" text-anchor="middle" font-size="11.5" font-weight="600" fill="${ink}">[${esc(tech)}]</text>\n`;
  }
  if (text) {
    ty += 8;
    for (const line of wrap(text, Math.floor((w - 24) / 6.4))) {
      ty += 17;
      out += `  <text x="${x + w / 2}" y="${ty}" text-anchor="middle" font-size="12.5" fill="${C.muted}">${esc(line)}</text>\n`;
    }
  }
  return out;
}

/** A labelled arrow along {@code pts} ([[x, y], ...]); the label sits at {@code at} (or the middle). */
function arrow(pts, { label, at, color = 'slate', dashed = false, anchor = 'middle' } = {}) {
  const stroke = color === 'indigo' ? C.indigo : color === 'amber' ? C.amber : C.slate;
  const marker = color === 'indigo' ? 'arrow-indigo' : color === 'amber' ? 'arrow-amber' : 'arrow';
  let out = `  <polyline points="${pts.map((p) => p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.6"${dashed ? ' stroke-dasharray="5 4"' : ''} marker-end="url(#${marker})"/>\n`;
  if (label) {
    const [lx, ly] = at ?? [(pts[0][0] + pts.at(-1)[0]) / 2, (pts[0][1] + pts.at(-1)[1]) / 2];
    const lines = Array.isArray(label) ? label : [label];
    const width = Math.max(...lines.map((l) => l.length)) * 6.3 + 14;
    const x0 = anchor === 'start' ? lx : anchor === 'end' ? lx - width : lx - width / 2;
    out += `  <rect x="${x0}" y="${ly - 12}" width="${width}" height="${lines.length * 15 + 8}" rx="6" fill="${C.bg}" stroke="${C.line}"/>\n`;
    lines.forEach((l, i) => {
      out += `  <text x="${x0 + width / 2}" y="${ly + 3 + i * 15}" text-anchor="middle" font-size="11.5" font-weight="600" fill="${stroke}">${esc(l)}</text>\n`;
    });
  }
  return out;
}

const heading = (x, y, text, sub) =>
  `  <text x="${x}" y="${y}" font-size="17" font-weight="800" fill="${C.text}">${esc(text)}</text>\n` +
  (sub ? `  <text x="${x}" y="${y + 20}" font-size="12.5" fill="${C.muted}">${esc(sub)}</text>\n` : '');

function person(cx, y, label, sub) {
  return `  <circle cx="${cx}" cy="${y + 16}" r="14" fill="${C.indigoSoft}" stroke="${C.indigoLine}" stroke-width="1.5"/>
  <path d="M${cx - 28} ${y + 64} q0 -30 28 -30 q28 0 28 30 z" fill="${C.indigoSoft}" stroke="${C.indigoLine}" stroke-width="1.5"/>
  <text x="${cx}" y="${y + 84}" text-anchor="middle" font-size="14" font-weight="700" fill="${C.text}">${esc(label)}</text>
${sub ? `  <text x="${cx}" y="${y + 101}" text-anchor="middle" font-size="12" fill="${C.muted}">${esc(sub)}</text>\n` : ''}`;
}

/** A UTxO card: a coin with a datum and value lines. */
function utxo(x, y, w, { title, lines = [], tone = 'indigo', token }) {
  const [fill, stroke, ink] = TONES[tone];
  const h = 44 + lines.length * 17 + (token ? 26 : 0);
  let out = `  <g filter="url(#shadow)"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="12" fill="${C.white}" stroke="${stroke}" stroke-width="1.5"/></g>
  <circle cx="${x + 22}" cy="${y + 22}" r="9" fill="${fill}" stroke="${ink}" stroke-width="1.5"/>
  <text x="${x + 38}" y="${y + 27}" font-size="13.5" font-weight="700" fill="${C.text}">${esc(title)}</text>\n`;
  lines.forEach((l, i) => {
    out += `  <text x="${x + 16}" y="${y + 50 + i * 17}" font-size="12" fill="${C.muted}" font-family="${MONO}">${esc(l)}</text>\n`;
  });
  if (token) {
    const ty = y + 40 + lines.length * 17 + 4;
    out += `  <rect x="${x + 14}" y="${ty}" width="${token.length * 7 + 18}" height="20" rx="10" fill="${C.amberSoft}" stroke="${C.amberLine}"/>
  <text x="${x + 23}" y="${ty + 14}" font-size="11" font-weight="600" fill="${C.amber}" font-family="${MONO}">${esc(token)}</text>\n`;
  }
  return { svg: out, h };
}

const label = (x, y, text, { color = C.muted, size = 12, weight = 600, anchor = 'middle', mono = false } = {}) =>
  `  <text x="${x}" y="${y}" text-anchor="${anchor}" font-size="${size}" font-weight="${weight}" fill="${color}"${mono ? ` font-family="${MONO}"` : ''}>${esc(text)}</text>\n`;

// ---------------------------------------------------------------------------------------------------------------
// 1. What JOUST generates (C4 container view).
function overview() {
  let b = heading(28, 40, 'What JOUST generates from one contract', 'Java with Gradle: the JOUST annotation processor runs inside javac');
  b += person(92, 200, 'You', 'write one class');
  b += box(170, 175, 220, 150, { title: 'Counter.java', tech: 'Java, or .jsc lowered to Java', text: 'A state record plus pure static methods: @Init, @Action, @View, @Destroy', tone: 'indigo', mono: true });
  b += arrow([[128, 248], [166, 248]], {});
  b += box(440, 175, 200, 150, { title: 'JOUST processor', tech: 'julc-state-processor', text: 'Reads the contract into a State IR and generates what follows', tone: 'slate' });
  b += arrow([[390, 250], [436, 250]], { label: 'javac', at: [413, 222] });
  b += box(700, 80, 232, 120, { title: 'CounterValidator', tech: 'JuLC: UPLC, Plutus V3', text: 'Re-runs your method on chain and enforces the result', tone: 'amber', mono: true });
  b += box(700, 220, 232, 110, { title: 'Description', tech: 'CIP-57 + x-joust JSON', text: 'For typed JavaScript clients (npm, experimental)', tone: 'white' });
  b += box(700, 360, 232, 120, { title: 'CounterClient', tech: 'Java on cardano-client-lib', text: 'Runs the method on the JVM, then builds, submits and confirms', tone: 'green', mono: true });
  b += arrow([[640, 215], [670, 215], [670, 140], [696, 140]], {});
  b += arrow([[640, 270], [696, 270]], {});
  b += arrow([[640, 300], [670, 300], [670, 420], [696, 420]], {});
  b += box(440, 380, 200, 80, { title: 'Your app', tech: 'Java', text: 'Calls the generated client', tone: 'indigo' });
  b += arrow([[640, 440], [696, 440]], { label: 'calls', at: [668, 462] });
  b += box(170, 520, 762, 70, { title: 'Cardano', tech: 'each instance is a UTxO with an inline datum and a thread token', tone: 'slate' });
  b += arrow([[816, 480], [816, 516]], { label: 'transactions', at: [878, 502], color: 'indigo' });
  b += arrow([[932, 140], [972, 140], [972, 560], [936, 560]], { label: ['reference', 'script'], at: [972, 290], color: 'amber' });
  return svg(1020, 612, 'What JOUST generates from one contract', b);
}

// 2. One method, run twice.
function sameMethod() {
  let b = heading(28, 40, 'One method, run twice', 'The rule you write is the rule the chain enforces');
  b += box(350, 74, 280, 92, { title: 'increment(state, by)', tech: 'your method', text: 'The state and the call in, the next state out', tone: 'indigo', mono: true });
  b += label(190, 214, 'OFF CHAIN: the JVM', { color: C.green, size: 12.5, weight: 800 });
  b += label(790, 214, 'ON CHAIN: UPLC', { color: C.amber, size: 12.5, weight: 800 });
  b += box(60, 230, 260, 74, { title: '1. Run the method', text: 'A failing Require costs nothing: no transaction is built', tone: 'green' });
  b += box(60, 324, 260, 74, { title: '2. Build the transaction', text: 'cardano-client-lib: the new datum is the result', tone: 'green' });
  b += box(60, 418, 260, 74, { title: '3. Sign and submit', text: 'The caller signs; the client waits for confirmation', tone: 'green' });
  b += box(660, 230, 260, 74, { title: '1. Compile it with JuLC', text: 'The same Java method becomes part of the validator', tone: 'amber' });
  b += box(660, 324, 260, 74, { title: '2. Re-run it', text: 'On the spent state and the call in the redeemer', tone: 'amber' });
  b += box(660, 418, 260, 74, { title: '3. Enforce the result', text: 'New datum equals the result; exact value; signer; token', tone: 'amber' });
  b += arrow([[350, 120], [190, 120], [190, 226]], { color: 'indigo' });
  b += arrow([[630, 120], [790, 120], [790, 226]], { color: 'indigo' });
  b += arrow([[190, 304], [190, 320]], {});
  b += arrow([[190, 398], [190, 414]], {});
  b += arrow([[790, 304], [790, 320]], {});
  b += arrow([[790, 398], [790, 414]], {});
  b += box(380, 418, 220, 74, { title: 'The transaction', text: 'Accepted only if both sides agree', tone: 'white' });
  b += arrow([[320, 455], [376, 455]], {});
  b += arrow([[656, 455], [604, 455]], { label: 'validates', at: [630, 430] });
  return svg(980, 520, 'One method, run twice: off chain on the JVM and on chain in the validator', b);
}

// 3. An instance's life as UTxOs.
function lifecycle() {
  let b = heading(28, 40, "An instance's life on chain", 'One UTxO at a time, tied together by a one-shot thread token');
  const xs = [36, 276, 516, 756];
  const cards = [
    { title: 'Counter #1', lines: ['count: 0', 'owner: alice'], token: 'thread token' },
    { title: 'Counter #1', lines: ['count: 5', 'owner: alice'], token: 'thread token' },
    { title: 'Counter #1', lines: ['count: 7', 'owner: alice'], token: 'thread token' },
  ];
  const y = 150;
  cards.forEach((c, i) => {
    const u = utxo(xs[i] + 30, y, 180, { ...c, tone: 'indigo' });
    b += u.svg;
  });
  const pay = utxo(xs[3] + 30, y, 180, { title: "alice's wallet", lines: ['the deposit back'], tone: 'green' });
  b += pay.svg;
  const txs = [
    ['create', '@Init: mints the token'],
    ['increment(5)', '@Action'],
    ['increment(2)', '@Action'],
    ['close', '@Destroy: burns it'],
  ];
  // Transactions as pills under the cards, arrows between the cards.
  b += `  <rect x="36" y="${y + 150}" width="330" height="1" fill="${C.line}"/><rect x="614" y="${y + 150}" width="330" height="1" fill="${C.line}"/>\n`;
  [[0], [1], [2], [3]].forEach(([i]) => {
    b += `  <g transform="translate(${xs[i] + 10} ${y + 170})"><rect width="210" height="58" rx="12" fill="${C.white}" stroke="${C.slateLine}"/>
  <text x="105" y="24" text-anchor="middle" font-size="13" font-weight="700" fill="${C.text}" font-family="${MONO}">${esc(txs[i][0])}</text>
  <text x="105" y="43" text-anchor="middle" font-size="12" fill="${C.muted}">${esc(txs[i][1])}</text></g>\n`;
  });
  for (let i = 0; i < 3; i++) b += arrow([[xs[i] + 214, y + 50], [xs[i + 1] + 26, y + 50]], { color: 'indigo' });
  b += label(126, y - 26, 'transaction 1 creates', { size: 12 });
  b += label(366, y - 26, 'each action spends one UTxO', { size: 12 });
  b += label(606, y - 26, 'and creates the next', { size: 12 });
  b += label(846, y - 26, 'destroy pays out', { size: 12 });
  b += label(490, y + 140, 'the transaction that made each one', { size: 11.5 });
  b += label(490, y + 268, 'Same script address, same token, exact value: the validator checks every step', { size: 12.5, weight: 600, color: C.slate });
  return svg(980, 450, "An instance's life on chain: create, actions, destroy", b);
}

// 4. The six kinds of state.
function stateKinds() {
  let b = heading(28, 40, 'Six kinds of state', 'Start with one UTxO; grow into maps, objects, batching, assets and keys with the same model');
  const panel = (x, y, w, h, n, title, sub) =>
    `  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="14" fill="${C.white}" stroke="${C.line}" stroke-width="1.5"/>
  <text x="${x + 18}" y="${y + 28}" font-size="12" font-weight="700" fill="${C.amber}" font-family="${MONO}">${n}</text>
  <text x="${x + 44}" y="${y + 28}" font-size="15" font-weight="800" fill="${C.text}">${esc(title)}</text>
  <text x="${x + 18}" y="${y + 48}" font-size="12" fill="${C.muted}">${esc(sub)}</text>\n`;
  const W = 300, H = 250;
  const X = [20, 340, 660], Y1 = 72, Y2 = 340;

  // 01 Inline
  b += panel(X[0], Y1, W, H, '01', 'Inline state', 'The record is the UTxO\'s inline datum');
  b += utxo(X[0] + 50, Y1 + 80, 200, { title: 'Counter', lines: ['count: 7', 'owner: alice'], token: 'thread token' }).svg;

  // 02 MerkleMap
  b += panel(X[1], Y1, W, H, '02', 'MerkleMaps', 'Any number of keys behind a 32-byte root');
  b += utxo(X[1] + 18, Y1 + 72, 150, { title: 'Token', lines: ['supply: 900', 'root: 9f3a…'] }).svg;
  const tx = X[1] + 222, ty = Y1 + 92;
  b += `  <g stroke="${C.indigoLine}" stroke-width="1.5" fill="${C.indigoSoft}">
    <line x1="${tx}" y1="${ty}" x2="${tx - 26}" y2="${ty + 40}"/><line x1="${tx}" y1="${ty}" x2="${tx + 26}" y2="${ty + 40}"/>
    <line x1="${tx - 26}" y1="${ty + 40}" x2="${tx - 44}" y2="${ty + 80}"/><line x1="${tx - 26}" y1="${ty + 40}" x2="${tx - 10}" y2="${ty + 80}"/>
    <line x1="${tx + 26}" y1="${ty + 40}" x2="${tx + 42}" y2="${ty + 80}"/>
    <circle cx="${tx}" cy="${ty}" r="8"/><circle cx="${tx - 26}" cy="${ty + 40}" r="7"/><circle cx="${tx + 26}" cy="${ty + 40}" r="7"/>
    <circle cx="${tx - 44}" cy="${ty + 80}" r="6"/><circle cx="${tx - 10}" cy="${ty + 80}" r="6"/><circle cx="${tx + 42}" cy="${ty + 80}" r="6"/></g>\n`;
  b += arrow([[X[1] + 168, Y1 + 115], [tx - 12, ty + 2]], { dashed: true, color: 'indigo' });
  b += label(tx, ty + 108, 'mirror, off chain', { size: 11.5 });
  b += label(X[1] + W / 2, Y1 + 228, 'each call proves its keys; checked on chain', { size: 11.5, color: C.indigo });

  // 03 Owned objects
  b += panel(X[2], Y1, W, H, '03', 'Owned objects', 'One UTxO per user object: users act in parallel');
  b += utxo(X[2] + 20, Y1 + 70, 120, { title: 'Bank', lines: ['paused: no'] }).svg;
  b += utxo(X[2] + 158, Y1 + 60, 124, { title: "Alice's", lines: ['balance: 40'], tone: 'green', token: 'object' }).svg;
  b += utxo(X[2] + 158, Y1 + 156, 124, { title: "Bob's", lines: ['balance: 12'], tone: 'green', token: 'object' }).svg;
  b += label(X[2] + 80, Y1 + 160, 'shared state,', { size: 11.5 });
  b += label(X[2] + 80, Y1 + 176, 'read by reference', { size: 11.5 });

  // 04 Batched intents (drawn at x = 160, shifted to the first column)
  b += `  <g transform="translate(${X[0] - 160} 0)">\n`;
  b += panel(160, Y2, W, H, '04', 'Batched intents', 'Users lock intents; a batcher applies them');
  [0, 1, 2].forEach((i) => {
    b += `  <rect x="${180}" y="${Y2 + 70 + i * 50}" width="96" height="38" rx="9" fill="${C.amberSoft}" stroke="${C.amberLine}"/>
  <text x="${228}" y="${Y2 + 94 + i * 50}" text-anchor="middle" font-size="12" font-weight="600" fill="${C.amber}">intent ${i + 1}</text>\n`;
    b += arrow([[276, Y2 + 89 + i * 50], [318, Y2 + 140]], { color: 'amber' });
  });
  b += utxo(322, Y2 + 104, 122, { title: 'Pool', lines: ['tickets: 41'] }).svg;
  b += label(383, Y2 + 200, 'one batch transaction', { size: 11.5, color: C.amber });
  b += `  </g>\n`;

  // 05 Native assets (drawn at x = 500, shifted to the second column)
  b += `  <g transform="translate(${X[1] - 500} 0)">\n`;
  b += panel(500, Y2, W, H, '05', 'Native assets', 'Hold tokens, mint your own, send them anywhere');
  b += utxo(520, Y2 + 72, 150, { title: 'Dex', lines: ['ADA: 2,000', 'HOSKY: 9.1M'], tone: 'indigo' }).svg;
  b += `  <rect x="694" y="${Y2 + 76}" width="88" height="30" rx="15" fill="${C.amberSoft}" stroke="${C.amberLine}"/><text x="738" y="${Y2 + 96}" text-anchor="middle" font-size="12" font-weight="600" fill="${C.amber}">LP tokens</text>\n`;
  b += arrow([[670, Y2 + 92], [690, Y2 + 92]], { label: 'mint', at: [680, Y2 + 70] , color: 'amber'});
  b += `  <rect x="694" y="${Y2 + 150}" width="88" height="30" rx="15" fill="${C.greenSoft}" stroke="${C.greenLine}"/><text x="738" y="${Y2 + 170}" text-anchor="middle" font-size="12" font-weight="600" fill="${C.green}">any address</text>\n`;
  b += arrow([[620, Y2 + 150], [620, Y2 + 165], [690, Y2 + 165]], { label: 'send', at: [652, Y2 + 186] });
  b += `  </g>\n`;

  // 06 Keyed objects (ADR-0016): a sorted on-chain list; an insert relinks its predecessor.
  b += panel(X[2], Y2, W, H, '06', 'Keyed objects', 'At most one object per key, in a sorted list');
  const node = (x, y, text, tone = 'white', dashed = false) => {
    const [fill, stroke, ink] = TONES[tone];
    return `  <rect x="${x}" y="${y}" width="72" height="38" rx="9" fill="${fill}" stroke="${stroke}" stroke-width="1.5"${dashed ? ' stroke-dasharray="5 4"' : ''}/>
  <text x="${x + 36}" y="${y + 24}" text-anchor="middle" font-size="12" font-weight="600" fill="${ink}" font-family="${MONO}">${esc(text)}</text>\n`;
  };
  const kx = [X[2] + 16, X[2] + 114, X[2] + 212], ky = Y2 + 76;
  b += node(kx[0], ky, 'head', 'slate');
  b += node(kx[1], ky, 'ann', 'green');
  b += node(kx[2], ky, 'eve', 'green');
  b += arrow([[kx[0] + 72, ky + 19], [kx[1] - 4, ky + 19]]);
  b += arrow([[kx[1] + 72, ky + 19], [kx[2] - 4, ky + 19]]);
  b += node(X[2] + 163, Y2 + 160, 'bob', 'green', true);
  b += arrow([[kx[1] + 36, ky + 38], [X[2] + 180, Y2 + 156]], { color: 'amber' });
  b += arrow([[X[2] + 218, Y2 + 160], [kx[2] + 36, ky + 42]], { color: 'amber' });
  b += label(X[2] + 72, Y2 + 182, 'insert after', { size: 11.5, color: C.amber });
  b += label(X[2] + 72, Y2 + 198, 'its neighbour', { size: 11.5, color: C.amber });
  b += label(X[2] + W / 2, Y2 + 228, 'one UTxO per key; the neighbours prove it is unique', { size: 11.5, color: C.green });
  return svg(980, 612, 'The six kinds of state JOUST supports', b);
}

// 5. The npm path in a browser (experimental).
function browser() {
  let b = heading(28, 40, 'A JOUST page, from the browser to the chain', 'The npm path (experimental): the engine is WebAssembly built with GraalVM Web Image');
  b += `  <rect x="24" y="76" width="636" height="430" rx="16" fill="none" stroke="${C.indigoLine}" stroke-width="1.6" stroke-dasharray="7 5"/>
  <text x="44" y="100" font-size="12.5" font-weight="800" fill="${C.indigo}">BROWSER</text>\n`;
  b += box(52, 120, 250, 112, { title: 'Your page', tech: 'JS + generated client', text: 'counter.at(id).increment(wallet, args)', tone: 'indigo' });
  b += box(380, 120, 250, 112, { title: 'Engine worker', tech: 'Wasm: JOUST runtime, UPLC VM', text: 'Runs the method, builds the transaction from the chain', tone: 'slate' });
  b += box(52, 300, 250, 112, { title: 'Page verifier', tech: '@julclang/state-client', text: 'Checks the call, script, balance and risk against the pinned contract', tone: 'green' });
  b += box(380, 300, 250, 112, { title: 'CIP-30 wallet', tech: 'Eternl, Lace, ...', text: 'Signs only what the page verified', tone: 'white' });
  b += `  <path d="M430 448 v34 a50 12 0 0 0 100 0 v-34" fill="${C.indigoSoft}" stroke="${C.indigoLine}" stroke-width="1.5"/><ellipse cx="480" cy="448" rx="50" ry="12" fill="${C.white}" stroke="${C.indigoLine}" stroke-width="1.5"/>
  <text x="480" y="481" text-anchor="middle" font-size="11.5" font-weight="700" fill="${C.indigo}">IndexedDB</text>
  <text x="540" y="466" font-size="11.5" fill="${C.muted}">the worker's</text>
  <text x="540" y="482" font-size="11.5" fill="${C.muted}">map mirrors</text>\n`;
  b += arrow([[302, 160], [376, 160]], { label: 'prepare(call)', at: [339, 138], color: 'indigo' });
  b += arrow([[376, 200], [302, 200]], { label: 'unsigned tx', at: [339, 222] });
  b += arrow([[177, 232], [177, 296]], { label: 'check', at: [205, 266] });
  b += arrow([[302, 356], [376, 356]], { label: 'sign if valid', at: [339, 334], color: 'green' });
  b += box(720, 120, 236, 112, { title: 'Static host', tech: 'dist/ from vite build', text: 'The page and the engine, pinned by SRI hashes', tone: 'white' });
  b += box(720, 270, 236, 92, { title: 'Chain API', tech: 'Blockfrost, Yaci Store', text: 'At /chain: keys stay server-side', tone: 'slate' });
  b += box(720, 410, 236, 92, { title: 'Cardano', tech: 'validator re-runs the method', text: 'The final check, on chain', tone: 'amber' });
  b += arrow([[716, 176], [634, 176]], { dashed: true, label: 'loads', at: [676, 154] });
  b += arrow([[630, 210], [680, 210], [680, 316], [716, 316]], { label: 'chain reads', at: [680, 262] });
  b += arrow([[630, 380], [690, 380], [690, 456], [716, 456]], { label: 'submit', at: [690, 420], color: 'green' });
  return svg(980, 530, 'A JOUST page from the browser to the chain (npm path, experimental)', b);
}

fs.mkdirSync(OUT, { recursive: true });
const all = { 'joust-overview': overview(), 'same-method': sameMethod(), lifecycle: lifecycle(), 'state-kinds': stateKinds(), 'browser-path': browser() };
for (const [name, content] of Object.entries(all)) fs.writeFileSync(path.join(OUT, `${name}.svg`), content);
console.log(`diagrams: ${Object.keys(all).join(', ')} -> ${path.relative(process.cwd(), OUT)}`);
