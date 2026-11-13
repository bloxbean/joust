// Wasm exception references (exnref), which JOUST's engines (GraalVM Web Images) need. Node 22 has them behind
// --experimental-wasm-exnref; newer Node has them on by default and prints "unrecognized flag" to stderr when the flag
// is set (Node 24.22, VS Code 1.146's extension host; nothing else changes). Hardening: the flag is set only when a
// module using exnref does not validate, so newer Node gets no stderr noise.
import v8 from 'node:v8';

// (module (func (result exnref) ref.null exn)): validates only where exnref is enabled.
export const EXNREF_PROBE = new Uint8Array([
  0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // magic, version 1
  0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x69, // type section: one type, () -> (exnref)
  0x03, 0x02, 0x01, 0x00, // function section: one function of type 0
  0x0a, 0x06, 0x01, 0x04, 0x00, 0xd0, 0x69, 0x0b, // code section: no locals; ref.null exn; end
]);

/** Whether this Node validates a module that uses exnref. */
export function exnrefEnabled() {
  return WebAssembly.validate(EXNREF_PROBE);
}

/**
 * Enables exnref if this Node does not already have it: sets --experimental-wasm-exnref only then, and fails with a
 * clear message if it is still unavailable. Returns how: 'native' (already on) or 'flag' (set here).
 */
export function enableExnref({ validate = exnrefEnabled, setFlags = (f) => v8.setFlagsFromString(f) } = {}) {
  if (validate()) return 'native';
  setFlags('--experimental-wasm-exnref');
  if (!validate()) {
    throw new Error(`this Node (${process.version}) has no Wasm exception references (exnref), which JOUST's engines need: use Node 22 or newer`);
  }
  return 'flag';
}
