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
| JOUST: Build | the project's `joust build` or `./gradlew build`, as a task (trusted workspaces) |
| JOUST: Show compiler and mode | which compiler analyses the current file, and why |

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
