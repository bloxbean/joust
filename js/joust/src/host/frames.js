// D2. Keep at most one bounded partial frame, even when a body arrives a byte at a time.
export const MAX_BODY = 4 * 1024 * 1024;
const MAX_HEADER = 1024;
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

// JSON.parse validates the grammar. Visit the structural tokens separately because JSON.parse
// otherwise silently replaces duplicate keys (including keys with different escape spellings).
function message(text) {
  const msg = JSON.parse(text, (key, value, context) =>
    typeof value === 'number' && (!Number.isSafeInteger(value) || !/^-?\d+$/.test(context.source)) ? JSON.rawJSON(context.source) : value);
  const stack = [];
  for (const match of text.matchAll(/"(?:[^"\\]|\\.)*"\s*:?|[{}\[\]]/g)) {
    const token = match[0];
    if (token === '{') stack.push(new Set());
    else if (token === '[') stack.push(null);
    else if (token === ']' || token === '}') stack.pop();
    else if (token.endsWith(':')) {
      const key = JSON.parse(token.slice(0, -1));
      const keys = stack.at(-1);
      if (keys.has(key)) throw Error('duplicate JSON key');
      keys.add(key);
    }
  }
  if (msg?.jsonrpc !== '2.0' || Array.isArray(msg)) throw Error('expected a JSON-RPC 2.0 object');
  const integer = v => Number.isSafeInteger(v) || (JSON.isRawJSON(v) && /^-?\d+$/.test(v.rawJSON));
  const id = v => typeof v === 'string' || integer(v);
  if ('method' in msg) {
    if (typeof msg.method !== 'string' || 'result' in msg || 'error' in msg || ('id' in msg && !id(msg.id)))
      throw Error('malformed request');
  } else if (!('id' in msg) || (msg.id !== null && !id(msg.id)) || ('result' in msg) === ('error' in msg)
      || ('error' in msg && (!integer(msg.error?.code) || typeof msg.error?.message !== 'string'))) {
    throw Error('malformed response');
  }
  return msg;
}

export function frameReader(accept, stop, stall = 10_000) {
  const header = Buffer.alloc(MAX_HEADER);
  let h = 0, b = 0, body, timer, started;
  function clear() {
    clearTimeout(timer);
    timer = undefined;
    h = b = 0;
    body = undefined;
  }
  function length() {
    const lines = header.toString('latin1', 0, h - 4).split('\r\n');
    let n;
    for (const line of lines) {
      if (/[^ -~]/.test(line)) throw Error('non-ASCII byte in frame header');
      const at = line.indexOf(':');
      if (at < 1) throw Error('malformed frame header');
      const name = line.slice(0, at).trim().toLowerCase();
      const value = line.slice(at + 1).trim();
      if (name === 'content-type') continue;
      if (name !== 'content-length' || n !== undefined || !/^\d{1,10}$/.test(value)) throw Error('invalid Content-Length');
      n = Number(value);
    }
    if (n === undefined || n > MAX_BODY) throw Error('missing or excessive Content-Length');
    return n;
  }
  return {
    chunk(bytes) {
      for (let i = 0; i < bytes.length;) {
        if (!timer) {
          started = performance.now();
          timer = setTimeout(() => stop('stalled frame'), stall);
        }
        if (!body) {
          header[h++] = bytes[i++];
          if (h >= 4 && header.readUInt32BE(h - 4) === 0x0d0a0d0a) body = Buffer.allocUnsafe(length());
          else if (h === MAX_HEADER) throw Error('frame header exceeds 1024 bytes');
          else continue;
        }
        const copied = bytes.copy(body, b, i, i + body.length - b);
        i += copied;
        b += copied;
        if (b === body.length) {
          if (performance.now() - started >= stall) throw Error('stalled frame');
          const text = utf8.decode(body);
          clear();
          accept(message(text), text);
        }
      }
    },
    end() { const partial = !!timer; clear(); stop(partial ? 'end of input inside a frame' : null); },
    clear,
  };
}

export function encode(text) {
  const n = Buffer.byteLength(text);
  if (n > MAX_BODY) return null;
  return Buffer.from('Content-Length: ' + n + '\r\n\r\n' + text);
}
