// ADR-0018 D12 (r12): a .jsc 2 script's messages hold no file name, line or name. A generated check traces a key built
// from ABI facts (#input:spend1.0, #pay:spend2.1); an author's require traces their text (which never contains a key's
// form, JSC0430). `joust build` ships each contract's message map in contracts.json ({ source, scriptHash, messages:
// { message: [{ line, col, kind, in, what? }] } }), and a refusal is shown with the .jsc position(s) that raise it, from
// the map of the contract that refused only: a key is unique within its script, not across scripts.

const describe = (s) => {
  switch (s.kind) {
    case 'input': return `the parameter ${s.what} of ${s.in} is not a well-formed input`;
    case 'pay': return `a payout in ${s.in} is negative`;
    case 'require': return `a require in ${s.in}`;
    default: return `in ${s.in}`;
  }
};

/**
 * D12's matching rule, the same as the JVM's (JscV2.matching): of the messages the trace is, or ends with after ': '
 * (a runtime's context), the longest. So #pay:spend0.1 never matches #pay:spend0.10, and an author's whole message
 * wins over an operation's that it ends with. Null when none matches.
 */
export function matchingMessage(trace, messages) {
  const t = String(trace ?? '');
  let best = null;
  for (const m of messages) {
    if (!m || !(t === m || t.endsWith(': ' + m))) continue;
    if (best === null || m.length > best.length) best = m;
  }
  return best;
}

const titleOf = (c) => {
  try {
    const d = typeof c.description === 'string' ? JSON.parse(c.description) : c.description;
    return d?.preamble?.title ?? null;
  } catch {
    return null;
  }
};

/**
 * The bundle entry of the contract that refused: by script hash when given, else by title (the engine's `refusedBy`
 * or the call's `contract`). Null when it is not one entry: a key is never looked up across contracts.
 */
export function refusingContract(contracts, { scriptHash, title } = {}) {
  const all = contracts ?? [];
  const found = all.filter((c) => (scriptHash ? c.messages?.scriptHash === scriptHash : title != null && titleOf(c) === title));
  return found.length === 1 ? found[0] : null;
}

/** The sites of a traced message in one contract's message map: `File.jsc:line:col (what)`. */
export function messageSites(text, contract) {
  const m = contract?.messages;
  if (!m?.messages) return [];
  const best = matchingMessage(text, Object.keys(m.messages));
  if (best === null) return [];
  return [...new Set(m.messages[best].map((s) => `${m.source}:${s.line}:${s.col} (${describe(s)})`))];
}

/** A refusal's text with the .jsc position(s) of its message, from the refusing contract's map when it has one. */
export function explainRefusal(text, contract) {
  const sites = messageSites(text, contract);
  return sites.length ? `${text} [${sites.join('; ')}]` : String(text);
}
