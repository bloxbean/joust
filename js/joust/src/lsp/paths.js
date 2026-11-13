// ADR-0021 D3b, D4b: path rules the language server applies before it touches the filesystem.
//
// - lexicalSegments(text): a path taken from configuration or a symlink target, checked as TEXT first (S14): absolute,
//   UNC (\\host\share, //host/share), device and extended (\\.\…, \\?\…), drive-relative (C:x) and drive-absolute paths
//   are refused, and so is any path whose '..' segments leave its base. No filesystem call is made for a refused path.
// - inside(child, parent): whether a real path lies in another, compared by path segment (never by string prefix:
//   /u/repo does not contain /u/repo-evil), exactly (the canonical case the real paths have).
import { resolve, sep } from 'node:path';

/**
 * The normalised segments of a relative path, or null when the text is not a plain relative path that stays below its
 * base. `base` is the segments it is relative to (a symlink's directory); '..' may climb within them, never above.
 */
export function lexicalSegments(text, base = []) {
  if (typeof text !== 'string' || text.length === 0 || text.length > 4096 || text.includes('\0')) return null;
  // UNC, device and extended paths, and POSIX or Windows absolute paths.
  if (/^[\\/]/.test(text)) return null;
  // Drive-absolute (C:\x, C:/x) and drive-relative (C:x) paths; also an NTFS stream or a colon anywhere in a segment.
  if (/^[A-Za-z]:/.test(text) || text.includes(':')) return null;
  const out = [...base];
  for (const s of text.split(/[\\/]+/)) {
    if (s === '' || s === '.') continue;
    if (s === '..') {
      if (out.length === 0) return null;
      out.pop();
      continue;
    }
    // Windows ignores trailing dots and spaces in a segment ("..." and ".. " name the parent there).
    if (/^\.+[ .]*$/.test(s) || /[ .]$/.test(s) && process.platform === 'win32') return null;
    out.push(s);
  }
  return out;
}

/** Whether `child` (a real path) is `parent` or lies inside it, by segment. */
export function inside(child, parent) {
  if (typeof child !== 'string' || typeof parent !== 'string' || !child || !parent) return false;
  const c = resolve(child).split(sep).filter(Boolean);
  const p = resolve(parent).split(sep).filter(Boolean);
  if (p.length > c.length) return false;
  for (let i = 0; i < p.length; i++) if (c[i] !== p[i]) return false;
  // On Windows the drive is the first segment; on POSIX both start at the root.
  return true;
}

/** The innermost of `folders` (real paths) that contains `p` (a real path), or undefined. */
export function folderOf(p, folders) {
  let best;
  for (const f of folders) if (inside(p, f) && (!best || f.length > best.length)) best = f;
  return best;
}
