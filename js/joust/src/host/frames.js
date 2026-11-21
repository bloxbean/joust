// D2. Keep at most one bounded partial frame, even when a body arrives a byte at a time.
export const MAX_BODY = 4 * 1024 * 1024;
const MAX_HEADER = 1024;
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

// The JVM host's Jackson parser refuses a document nested more than this many levels, and so ends the session.
export const MAX_DEPTH = 1000;

// JSON.parse validates the grammar; scan() bounds the nesting depth (#154 review M2-2) and refuses duplicate keys.
// One linear pass (#154 final review F-N1, review r2 M2-13): a complete string token, optionally a key, a structural
// character, or a lone quote (an unterminated string, refused at once: retrying the string alternative at every later
// quote would be quadratic). No token array is kept.
const TOKEN = /"(?:[^"\\]|\\.)*"\s*:?|[{}\[\]]|"/g;
function scan(text) {
  const stack = [];
  TOKEN.lastIndex = 0;
  for (let m; (m = TOKEN.exec(text)) !== null; ) {
    const token = m[0];
    if (token === '"') throw Error('unterminated JSON string');
    if (token === '{' || token === '[') {
      if (stack.length >= MAX_DEPTH) throw Error('JSON nested more than ' + MAX_DEPTH + ' levels');
      stack.push(token === '{' ? new Set() : null);
    } else if (token === ']' || token === '}') stack.pop();
    else if (token.endsWith(':')) {
      const keys = stack.at(-1);
      if (!keys) throw Error('malformed JSON');
      const key = JSON.parse(token.slice(0, -1));
      if (keys.has(key)) throw Error('duplicate JSON key');
      keys.add(key);
    }
  }
}

function message(text) {
  // Depth and duplicate keys before JSON.parse recurses, which otherwise also silently replaces duplicate keys
  // (including keys with different escape spellings).
  scan(text);
  const msg = JSON.parse(text, (key, value, context) =>
    typeof value === 'number' && (!Number.isSafeInteger(value) || !/^-?\d+$/.test(context.source)) ? JSON.rawJSON(context.source) : value);
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
