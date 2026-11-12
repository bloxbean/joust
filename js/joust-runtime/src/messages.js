// ADR-0018 D12 (r12): a .jsc 2 script's messages hold no file name, line or name. A generated check traces a key built
// from ABI facts (#input:spend1.0, #pay:spend2.1); an author's require traces their text. `joust build` ships each
// contract's message map ({ source, messages: { message: [{ line, col, kind, in, what? }] } }) in contracts.json, and a
// refusal is shown with the .jsc position(s) that raise it.

const describe = (s) => {
  switch (s.kind) {
    case 'input': return `the parameter ${s.what} of ${s.in} is not a well-formed input`;
    case 'pay': return `a payout in ${s.in} is negative`;
    case 'require': return `a require in ${s.in}`;
    default: return `in ${s.in}`;
  }
};

/**
 * The sites of a traced message in the contracts' message maps: `File.jsc:line:col (what)`. A key matches anywhere in
 * the text (it cannot be an author's message: JSC0430); an author's or an operation's message matches the whole text or
 * its end, after a runtime's `context: ` prefix.
 */
export function messageSites(text, contracts) {
  const t = String(text ?? '');
  const out = [];
  for (const c of contracts ?? []) {
    const m = c?.messages;
    if (!m?.messages) continue;
    for (const [msg, sites] of Object.entries(m.messages)) {
      if (!msg) continue;
      const hit = t === msg || t.endsWith(': ' + msg) || (/^#[a-z]+:/.test(msg) && t.includes(msg));
      if (!hit) continue;
      for (const s of sites) out.push(`${m.source}:${s.line}:${s.col} (${describe(s)})`);
    }
  }
  return [...new Set(out)];
}

/** A refusal's text with the .jsc position(s) of its message, when a contract's message map knows it. */
export function explainRefusal(text, contracts) {
  const sites = messageSites(text, contracts);
  return sites.length ? `${text} [${sites.join('; ')}]` : String(text);
}
