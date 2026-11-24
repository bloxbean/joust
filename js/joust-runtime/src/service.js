import { load, serve } from '#service-mode';
import pkg from '../package.json' with { type: 'json' };

/** This runtime's version: contracts built by an incompatible toolchain are refused (ADR-0015 I7). */
export const RUNTIME_VERSION = pkg.version;

/** The part of a version that must match: the major, or major.minor while the major is 0 (semver's 0.x rule). */
const compatibility = (v) => {
  const [major, minor] = String(v).split('.');
  return major === '0' ? `0.${minor}` : major;
};

/** The program manifest format this runtime's engine reads (ADR-0020 D8). */
export const MANIFEST_VERSION = 2;

const REBUILD = 'build the contracts again with joust build, with the @julclang/joust that matches this runtime';

/**
 * I7: a contract's description format and the toolchain that built it must be ones this runtime reads; a contract with
 * transition programs must carry their manifest in the format this engine checks (ADR-0020 D8). Checked when the
 * runtime is created, so a stale bundle fails before any call.
 */
export function checkCompatible(c) {
  const d = typeof c.description === 'string' ? JSON.parse(c.description) : c.description;
  const title = d?.preamble?.title ?? '(a contract)';
  const v = d?.['x-joust']?.version;
  if (v !== 2) throw new Error(`${title}: description version ${v}; this runtime reads version 2 (rebuild with joust build)`);
  const built = c.built?.joust;
  if (built && compatibility(built) !== compatibility(RUNTIME_VERSION)) {
    throw new Error(`${title}: built by JOUST ${built}; this runtime is ${RUNTIME_VERSION} (rebuild with a matching @julclang/joust)`);
  }
  const programs = Object.keys(c.transitions ?? {}).length;
  const m = typeof c.manifest === 'string' ? JSON.parse(c.manifest) : c.manifest;
  if (programs && !m) throw new Error(`${title}: its transition programs have no manifest (ADR-0020 D8): ${REBUILD}`);
  if (m && m.manifest !== MANIFEST_VERSION) {
    throw new Error(`${title}: program manifest version ${m.manifest}; this runtime reads version ${MANIFEST_VERSION}: ${REBUILD}`);
  }
}

/**
 * The contracts as the engine takes them: the description as text (an object is serialised: the manifest binds the
 * description's canonical JSON, so either form matches), the script, the programs and their manifest (text).
 */
export function engineContracts(list) {
  return list.map((c) => ({
    description: typeof c.description === 'string' ? c.description : JSON.stringify(c.description),
    script: c.script, transitions: c.transitions ?? {},
    ...(c.messages && { messages: JSON.stringify(c.messages) }),
    manifest: c.manifest == null ? null : typeof c.manifest === 'string' ? c.manifest : JSON.stringify(c.manifest),
  }));
}

/**
 * The JuLC version the programs were compiled with must be the engine's (ADR-0020 D8): checked once the engine is
 * loaded, for every contract at once, so the remedy comes before the first call fails.
 */
export function checkEngine(engine, list) {
  const info = engine.joust.info ? engine.joust.info({}) : null;
  if (!info) return;
  for (const c of list) {
    if (!c.manifest) continue;
    const m = JSON.parse(c.manifest);
    if (m.julc !== info.julc) {
      throw new Error(`${m.contract}: transition programs compiled by JuLC ${m.julc}; this engine evaluates with JuLC ${info.julc}: ${REBUILD}`);
    }
  }
}

/** The engine service shared by provider prepares and the stdio worker. Check the exact
 * programs once per engine before dispatch. Providers load lazily and hosts call load at initialize. */
export function engineService(options) {
  const { contracts } = options;
  contracts.forEach(checkCompatible);
  const programs = engineContracts(contracts);
  let checked;
  return serve(options, {
    programs,
    async load() {
      const engine = await load(options);
      if (checked !== engine) {
        checkEngine(engine, programs);
        checked = engine;
      }
      return engine;
    },
  });
}
