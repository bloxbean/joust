// Node's module loader runs these hooks before evaluating any host or runtime module, in each thread. They are
// registered from the bytes the entry read (a data: URL), and serve every module from the same hashed bytes (I7).
import { realpathSync } from 'node:fs';
import { relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

let project, manifest;
export function initialize(data) { ({ root: project, manifest } = data); }
const outside = message => Object.assign(new Error(message), { code: 'outsideProject' });

export async function resolve(specifier, context, nextResolve) {
  const result = await nextResolve(specifier, context);
  if (result.url.startsWith('node:')) return result;
  if (result.url.startsWith('file:')) {
    const path = relative(project, realpathSync(fileURLToPath(result.url)));
    if (path !== '..' && !path.startsWith('..' + sep) && !isAbsolute(path)) return result;
  }
  throw outside('runtime import outside project');
}

export async function load(url, context, nextLoad) {
  if (url.startsWith('node:')) return nextLoad(url, context);
  const source = manifest[url];
  if (!source) throw outside('module outside the host closure the fingerprint hashes');
  // Decoded here as Node decodes a file it reads (Node 22 mishandles byte sources returned across the hooks thread).
  const text = new TextDecoder('utf-8', { fatal: true }).decode(source);
  return { format: url.endsWith('.json') ? 'json' : 'module', source: text, shortCircuit: true };
}
