// ADR-0021 D7, D9: JOUST: Build as a VS Code task, through the project's own CLI (npm) or its gradlew (Gradle), with the
// $joust problem matcher. Only in a trusted workspace, and only when that CLI's package (or gradlew) lies inside the
// workspace folder by real path (D3b's segment comparison). Executables by absolute path, never a bare name (D3b).
const fs = require('fs');
const path = require('path');

function inside(child, parent) {
  const c = path.resolve(child).split(path.sep).filter(Boolean);
  const p = path.resolve(parent).split(path.sep).filter(Boolean);
  return p.length <= c.length && p.every((s, i) => c[i] === s);
}

function realOrNull(p) {
  try {
    return fs.realpathSync.native(p);
  } catch {
    return null;
  }
}

function smallJson(p) {
  try {
    const fd = fs.openSync(p, fs.constants.O_RDONLY | (fs.constants.O_NONBLOCK || 0));
    try {
      const st = fs.fstatSync(fd);
      if (!st.isFile() || st.size > 64 * 1024) return null;
      return JSON.parse(fs.readFileSync(fd, 'utf8'));
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return null;
  }
}

/** A local absolute path as text (S4 on #83): no UNC, device or extended form, before any filesystem call. */
function localPath(p) {
  if (typeof p !== 'string' || !p || /^[\\/]{2}/.test(p)) return null;
  return process.platform === 'win32' ? (/^[A-Za-z]:[\\/]/.test(p) ? p : null) : (p.startsWith('/') ? p : null);
}

/**
 * How to build the project at the folder's ROOT: `{ kind, command, args, cwd, env }`, or null (a monorepo's nested projects
 * get diagnostics, not this task: README, ADR-0021 §12 note 13).
 */
function buildCommand(folderPath, platform = process.platform, execPath = process.execPath) {
  const folder = localPath(folderPath) && realOrNull(folderPath);
  if (!folder) return null;
  const pkg = smallJson(path.join(folder, 'package.json'));
  const npm = fs.existsSync(path.join(folder, 'joust.config.json'))
    || (pkg && ['dependencies', 'devDependencies'].some((k) => pkg[k] && typeof pkg[k] === 'object' && '@julclang/joust' in pkg[k]));
  if (npm) {
    const dir = realOrNull(path.join(folder, 'node_modules', '@julclang', 'joust'));
    // Inside the folder before anything there is read (S4 on #83).
    if (!dir || !inside(dir, folder)) return null;
    const meta = smallJson(path.join(dir, 'package.json'));
    const rel = meta && (typeof meta.bin === 'string' ? meta.bin : meta.bin && meta.bin.joust);
    const bin = typeof rel === 'string' ? realOrNull(path.join(dir, rel)) : null;
    if (!bin || !inside(bin, dir)) return null;
    // The project's CLI on the extension host's Node (an absolute path), run as Node.
    return { kind: 'npm', command: execPath, args: [bin, 'build'], cwd: folder, env: { ELECTRON_RUN_AS_NODE: '1' } };
  }
  const gradlew = realOrNull(path.join(folder, platform === 'win32' ? 'gradlew.bat' : 'gradlew'));
  if (gradlew && inside(gradlew, folder)) return { kind: 'gradle', command: gradlew, args: ['build'], cwd: folder, env: {} };
  return null;
}

/**
 * D7a: the project's own tool for Accept ABI…, at the folder's root (as buildCommand finds it): `{ kind, diff(contract,
 * out), accept(contract, committedSha, newSha) }`, each giving `{ command, args, cwd, env }`, or null. npm: `joust abi
 * diff <C> --out <file>` and `joust build --accept-abi=<C>@<committed>..<new>`; Gradle: `jscAbiDiff --contract <C>
 * --out <file>` and `acceptJscAbi --contract <C> --committed-sha256 <h1> --sha256 <h2>` (task options, never properties).
 */
function abiCommands(folderPath, platform = process.platform, execPath = process.execPath) {
  const b = buildCommand(folderPath, platform, execPath);
  if (!b) return null;
  if (b.kind === 'npm') {
    const bin = b.args[0];
    return {
      kind: 'npm',
      diff: (c, out) => ({ ...b, args: [bin, 'abi', 'diff', c, '--out', out] }),
      accept: (c, h1, h2) => ({ ...b, args: [bin, 'build', `--accept-abi=${c}@${h1}..${h2}`] }),
    };
  }
  return {
    kind: 'gradle',
    diff: (c, out) => ({ ...b, args: ['jscAbiDiff', '--contract', c, '--out', out] }),
    accept: (c, h1, h2) => ({ ...b, args: ['acceptJscAbi', '--contract', c, '--committed-sha256', h1, '--sha256', h2] }),
  };
}

function createTaskProvider(vscode) {
  const task = (folder) => {
    if (folder.uri.scheme !== 'file' || folder.uri.authority) return null;
    const b = buildCommand(folder.uri.fsPath);
    if (!b) return null;
    const exec = new vscode.ProcessExecution(b.command, b.args, { cwd: b.cwd, env: b.env });
    const t = new vscode.Task({ type: 'joust', task: 'build' }, folder, 'build', 'joust', exec, ['$joust']);
    t.group = vscode.TaskGroup.Build;
    t.detail = b.kind === 'npm' ? 'joust build (the project\'s own CLI)' : './gradlew build';
    return t;
  };
  return {
    provideTasks() {
      // VS Code disables workspace tasks in Restricted Mode; the provider offers none there either (D10).
      if (!vscode.workspace.isTrusted) return [];
      return (vscode.workspace.workspaceFolders || []).map(task).filter(Boolean);
    },
    resolveTask(t) {
      // A tasks.json entry of type joust gets the provider's own execution, never one the workspace describes.
      if (!vscode.workspace.isTrusted || !t.definition || t.definition.task !== 'build' || !t.scope || !t.scope.uri) return undefined;
      return task(t.scope) || undefined;
    },
  };
}

module.exports = { createTaskProvider, buildCommand, abiCommands, inside };
