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

/** How to build the project at the folder's root: `{ kind, command, args, cwd, env }`, or null. */
function buildCommand(folderPath, platform = process.platform, execPath = process.execPath) {
  const folder = realOrNull(folderPath);
  if (!folder) return null;
  const pkg = smallJson(path.join(folder, 'package.json'));
  const npm = fs.existsSync(path.join(folder, 'joust.config.json'))
    || (pkg && ['dependencies', 'devDependencies'].some((k) => pkg[k] && typeof pkg[k] === 'object' && '@julclang/joust' in pkg[k]));
  if (npm) {
    const dir = realOrNull(path.join(folder, 'node_modules', '@julclang', 'joust'));
    const meta = dir && smallJson(path.join(dir, 'package.json'));
    const rel = meta && (typeof meta.bin === 'string' ? meta.bin : meta.bin && meta.bin.joust);
    const bin = typeof rel === 'string' ? realOrNull(path.join(dir, rel)) : null;
    if (!dir || !bin || !inside(dir, folder) || !inside(bin, dir)) return null;
    // The project's CLI on the extension host's Node (an absolute path), run as Node.
    return { kind: 'npm', command: execPath, args: [bin, 'build'], cwd: folder, env: { ELECTRON_RUN_AS_NODE: '1' } };
  }
  const gradlew = realOrNull(path.join(folder, platform === 'win32' ? 'gradlew.bat' : 'gradlew'));
  if (gradlew && inside(gradlew, folder)) return { kind: 'gradle', command: gradlew, args: ['build'], cwd: folder, env: {} };
  return null;
}

function createTaskProvider(vscode) {
  const task = (folder) => {
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

module.exports = { createTaskProvider, buildCommand, inside };
