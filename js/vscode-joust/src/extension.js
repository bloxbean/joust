// The JOUST VS Code extension (ADR-0021 D9): a thin client. It starts its own bundled language server (dist/server.js, by
// absolute path, on the extension host's Node: D3b), which chooses the compiler (the project's two engine files when the
// workspace is trusted and they are confined to a folder, else the bundled engine, advisory) and says which. Here: the
// status bar (compiler, mode, trust), the commands (Restart analysis, Restart server, Build, Show compiler and mode),
// the verified workspace/applyEdit handler (D4a), the build task and its problem matcher, Restricted Mode (D10).
const vscode = require('vscode');
const { CloseAction, ErrorAction, LanguageClient, State, TransportKind } = require('vscode-languageclient/node');
const { createHandleApplyEdit } = require('./apply-edit');
const { createTaskProvider } = require('./tasks');

let client;
let context;
let statusItem;
const statusByUri = new Map();
let lastStatus;
let unavailableNotified = false;
let serverGaveUp = false;

function workspaceFilePath() {
  const f = vscode.workspace.workspaceFile;
  return f && f.scheme === 'file' ? f.fsPath : undefined;
}

function allowedRoots() {
  // machine scope: a workspace cannot set it (D10); inspect() so that only the user's own value counts.
  const v = vscode.workspace.getConfiguration('joust').inspect('allowedProjectRoots');
  const user = v && v.globalValue;
  return Array.isArray(user) ? user.filter((p) => typeof p === 'string') : [];
}

function render() {
  if (!statusItem) return;
  const editor = vscode.window.activeTextEditor;
  const s = (editor && statusByUri.get(editor.document.uri.toString())) || lastStatus;
  if (serverGaveUp) {
    statusItem.text = '$(error) JOUST analysis unavailable';
    statusItem.tooltip = 'The JOUST language server stopped. Run JOUST: Restart server.';
    statusItem.command = 'joust.restartServer';
  } else if (s && s.state === 'unavailable') {
    statusItem.text = '$(error) JOUST analysis unavailable';
    statusItem.tooltip = 'The engine failed repeatedly on this project. Run JOUST: Restart analysis.';
    statusItem.command = 'joust.restartAnalysis';
  } else if (s) {
    const trust = vscode.workspace.isTrusted ? '' : 'restricted: ';
    statusItem.text = `${s.mode === 'project-matched' ? '$(check)' : '$(info)'} JOUST ${s.compiler} · ${trust}${s.source}${s.mode === 'advisory' ? ', advisory' : ''}`;
    statusItem.tooltip = `${s.source === 'project' ? 'The project\'s own compiler' : 'The extension\'s bundled compiler'}, ${s.mode}: ${s.reason || ''}`;
    statusItem.command = 'joust.showCompiler';
  } else {
    statusItem.text = '$(sync~spin) JOUST';
    statusItem.command = 'joust.showCompiler';
  }
  const lang = editor && editor.document.languageId === 'jsc';
  if (lang || serverGaveUp || (s && s.state === 'unavailable')) statusItem.show();
  else statusItem.hide();
}

function onStatus(s) {
  if (!s) return;
  if (s.uri) statusByUri.set(s.uri, s);
  lastStatus = s;
  if (s.state === 'unavailable' && !unavailableNotified) {
    unavailableNotified = true;
    vscode.window.showWarningMessage('JOUST analysis unavailable: the engine failed repeatedly on this project.', 'Restart analysis')
      .then((pick) => { if (pick) vscode.commands.executeCommand('joust.restartAnalysis'); });
  }
  if (s.state === 'ok') unavailableNotified = false;
  render();
}

function makeClient() {
  const serverModule = context.asAbsolutePath('dist/server.js');
  const run = { module: serverModule, transport: TransportKind.ipc, options: { cwd: context.extensionPath, execArgv: [] } };
  let restarts = [];
  // The selection's inputs, the project's two engine files included (review C6 on #83: replaced engine bytes with an
  // unchanged package.json must reach the server; Restart analysis also selects again).
  const watchers = ['**/joust.config.json', '**/package.json', '**/node_modules/@julclang/joust/package.json',
    '**/node_modules/@julclang/joust/engine/joust-toolchain.js', '**/node_modules/@julclang/joust/engine/joust-toolchain.js.wasm']
    .map((g) => vscode.workspace.createFileSystemWatcher(g));
  watchers.forEach((w) => context.subscriptions.push(w));
  const c = new LanguageClient('joust', 'JOUST language server', { run, debug: run }, {
    documentSelector: [{ language: 'jsc', scheme: 'file' }, { language: 'jsc', scheme: 'untitled' }],
    initializationOptions: {
      client: 'vscode-joust',
      trusted: vscode.workspace.isTrusted,
      storageDir: context.globalStorageUri.fsPath,
      workspaceFile: workspaceFilePath(),
      allowedRoots: allowedRoots(),
    },
    synchronize: { fileEvents: watchers },
    // D10: no markdown is ever trusted.
    markdown: { isTrusted: false, supportHtml: false },
    middleware: { workspace: { handleApplyEdit: createHandleApplyEdit(vscode, () => c) } },
    errorHandler: {
      error: () => ({ action: ErrorAction.Continue }),
      // The client's restart policy: up to four restarts in three minutes, then "analysis unavailable" (D2a).
      closed: () => {
        const t = Date.now();
        restarts = restarts.filter((x) => t - x < 3 * 60 * 1000);
        restarts.push(t);
        if (restarts.length <= 4) return { action: CloseAction.Restart };
        serverGaveUp = true;
        render();
        vscode.window.showErrorMessage('The JOUST language server stopped repeatedly: JOUST analysis unavailable.', 'Restart server')
          .then((pick) => { if (pick) vscode.commands.executeCommand('joust.restartServer'); });
        return { action: CloseAction.DoNotRestart };
      },
    },
  });
  c.onDidChangeState((e) => {
    if (e.newState === State.Running) {
      serverGaveUp = false;
      render();
    }
  });
  return c;
}

async function startClient() {
  client = makeClient();
  await client.start();
  client.onNotification('joust/status', onStatus);
  const s = await client.sendRequest('joust/status', {});
  onStatus({ ...s, uri: undefined });
}

async function restartServer() {
  serverGaveUp = false;
  statusByUri.clear();
  lastStatus = undefined;
  render();
  if (client) {
    try {
      await client.stop();
    } catch {
      // already stopped
    }
  }
  await startClient();
}

function activate(ctx) {
  context = ctx;
  statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  ctx.subscriptions.push(statusItem);
  ctx.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(render));
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.restartAnalysis', async () => {
    if (!client || client.state !== State.Running) return restartServer();
    unavailableNotified = false;
    await client.sendRequest('joust/restartAnalysis');
  }));
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.restartServer', restartServer));
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.showCompiler', async () => {
    const editor = vscode.window.activeTextEditor;
    const s = client && client.state === State.Running
      ? await client.sendRequest('joust/status', { uri: editor ? editor.document.uri.toString() : undefined }) : lastStatus;
    if (!s) return vscode.window.showInformationMessage('The JOUST language server is not running.');
    const trust = vscode.workspace.isTrusted ? 'trusted workspace' : 'Restricted Mode';
    vscode.window.showInformationMessage(`JOUST ${s.compiler} (${s.source === 'project' ? 'the project\'s compiler' : 'bundled'}), `
      + `${s.mode}; ${trust}. ${s.reason || ''}`);
    return s;
  }));
  // Returns what it did (the messages are not awaited: a notification stays until dismissed).
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.build', async () => {
    if (!vscode.workspace.isTrusted) {
      vscode.window.showWarningMessage('JOUST: Build runs the project\'s own CLI: trust the workspace first.');
      return { ran: false, reason: 'restricted' };
    }
    const tasks = await vscode.tasks.fetchTasks({ type: 'joust' });
    if (!tasks.length) {
      vscode.window.showWarningMessage('JOUST: no npm project with @julclang/joust installed, or gradlew, in a workspace folder.');
      return { ran: false, reason: 'no project CLI' };
    }
    await vscode.tasks.executeTask(tasks[0]);
    return { ran: true };
  }));
  // For the extension's own tests: what the server said at initialize, and the latest status. Hidden from the palette.
  ctx.subscriptions.push(vscode.commands.registerCommand('joust._status', () => ({
    serverInfo: client && client.initializeResult && client.initializeResult.serverInfo,
    capabilities: client && client.initializeResult && client.initializeResult.capabilities,
    state: client ? client.state : undefined,
    statuses: [...statusByUri.values()],
    trusted: vscode.workspace.isTrusted,
  })));
  ctx.subscriptions.push(vscode.tasks.registerTaskProvider('joust', createTaskProvider(vscode)));
  // Trust granted mid-session: the server selects again (D3).
  ctx.subscriptions.push(vscode.workspace.onDidGrantWorkspaceTrust(() => {
    if (client) client.sendNotification('joust/trust', { trusted: true });
  }));
  // A folder added or removed changes the confinement boundaries: a new server.
  ctx.subscriptions.push(vscode.workspace.onDidChangeWorkspaceFolders(() => restartServer()));
  render();
  return startClient();
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
