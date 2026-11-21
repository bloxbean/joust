// Node's module loader runs these hooks before evaluating a runtime dependency.
import { realpathSync } from 'node:fs';
import { relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

let project;
export function initialize(root) { project = root; }

export async function resolve(specifier, context, nextResolve) {
  const result = await nextResolve(specifier, context);
  if (result.url.startsWith('node:')) return result;
  if (result.url.startsWith('file:')) {
    const path = relative(project, realpathSync(fileURLToPath(result.url)));
    if (path !== '..' && !path.startsWith('..' + sep) && !isAbsolute(path)) return result;
  }
  throw Object.assign(new Error('runtime import outside project'), { code: 'outsideProject' });
}
