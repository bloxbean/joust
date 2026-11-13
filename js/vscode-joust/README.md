# JOUST `.jsc` for VS Code

JOUST's contract language, `.jsc` 2, in VS Code (ADR-0021, milestone M5a). Language preview, like `.jsc` 2 itself.

- **Diagnostics as you type**, from the build's own front end: the first error, or, when the file checks, its warnings,
  each with its stable code (`JSC0502`) linking its row in the
  [diagnostics catalogue](https://github.com/bloxbean/joust/blob/main/docs/reference/jsc-2-diagnostics.md). Recovery
  (several errors at once) comes in M5b.
- **The outline, folding and selection ranges**: contract, state fields, init, actions, destroys, views, fns, types,
  enums and variants.
- **Highlighting** (the TextMate grammar, which the docs site's Shiki also loads), a language configuration and snippets
  (`contract`, `action`, `match`, `fn`, `enum`).
- **JOUST: Build** runs the project's own `joust build` (npm) or `./gradlew build` as a task; its errors land in the
  Problems panel through the `$joust` problem matcher.
- Two quick fixes (`let`/`var` → `const`, `===` → `==`), offered only when you ask (Ctrl+.), never on save.

## Which compiler runs

The status bar says it. In a **trusted** workspace whose npm project has `@julclang/joust` installed inside the workspace
folder, the server runs **the project's own compiler** (its two engine files, copied by their checked bytes into the
extension's private storage), and the project's contracts are **project-matched**: the editor reports what the build's
front end reports. Everywhere else (Restricted Mode, a Gradle project, a project outside the folder, an older
`@julclang/joust`) the extension's **bundled** compiler runs and every diagnostic says **advisory**. The build stays the
authority.

## Trust

**Trusting a workspace runs its compiler.** When a trusted folder holds an npm project with `@julclang/joust` installed,
the language server loads that package's engine (`engine/joust-toolchain.js` and its `.wasm`) as soon as the folder
opens, with your privileges: it is JavaScript and WebAssembly from the repository, as `npm run build` would run.
VS Code also trusts a folder whose parent folder you trusted, so a repository cloned into a trusted `~/work` runs its
engine on open. **Review a repository you do not know in Restricted Mode** (don't trust it, or open it outside your
trusted folders).

- **Restricted Mode**: only the bundled compiler, advisory; no task and no project code; reads stay inside the
  workspace folders.
- No setting names an executable or a server path. `joust.allowedProjectRoots` (user settings only, `machine` scope)
  lets a folder that a `.code-workspace` file lists outside its own directory use its project's compiler.
- No network, no telemetry. The server writes nothing into the workspace.

## Commands

| Command | |
|---|---|
| JOUST: Restart analysis | a new engine worker, after "JOUST analysis unavailable" |
| JOUST: Restart server | a new language server |
| JOUST: Build | the project's `joust build` or `./gradlew build`, as a task (trusted workspaces): the project at a workspace folder's root only; the npm CLI runs on VS Code's own Node (an absolute path), not the `node` on your PATH |
| JOUST: Show compiler and mode | which compiler analyses the current file, and why |

## Known issue: saves that hang with `codeActionsOnSave` "quickfix"

With VS Code's built-in chat on (the default) and no chat model configured, a workspace or user setting
`"editor.codeActionsOnSave": { "quickfix": "explicit" }` (or `"always"`) makes saving a file that has diagnostics hang:
chat's own quick-fix provider never answers the save's request. This is VS Code's behaviour, in any language (the
JOUST server answers such a request at once, with nothing: it never applies a fix on save). Remove `quickfix` from
`codeActionsOnSave`, or set `"chat.disableAIFeatures": true`. Seen on VS Code 1.109.0 and 1.146.0.

## Building and testing

```sh
cd js/vscode-joust
npm ci --ignore-scripts
npm test                       # the grammar, the applyEdit handler, the task provider (Node only)
node scripts/build.mjs         # dist/ and engine/ (from ../joust/engine: build it first, see js/joust)
npm run package                # vscode-joust.vsix, then the checks: no .node file, under 16 MB, the engine's digests
npm run test:electron          # @vscode/test-electron on VS Code 1.109.0 and stable: smoke and hostile workspaces
```

**Not published.** Publishing to the Visual Studio Marketplace and Open VSX is done by a maintainer, by hand, from the
`.vsix` attached to a release (ADR-0021 D11). The `publisher` in `package.json` is a placeholder, `joust-q4-undecided`,
until ADR-0021 Q4 (the publisher identity) is decided.

To try it locally: `code --install-extension vscode-joust.vsix`.
