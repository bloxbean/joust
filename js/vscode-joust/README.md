# JOUST `.jsc` for VS Code

JOUST's contract language, `.jsc`, in VS Code (ADR-0021, milestones M5a–M5e). Language preview, like `.jsc` itself.

- **Diagnostics as you type**, from the build's own front end. Every error in a file is reported at once, each with
  its stable code (`JSC0502`) linking its row in the
  [diagnostics catalogue](https://github.com/bloxbean/joust/blob/main/docs/reference/jsc-2-diagnostics.md).
- **Hover** (types, the standard library's documentation), **go to definition**, **find references** and **semantic
  highlighting**, within a file and across files (below).
- **Completion**: names in scope, fields, the standard library, and names from other files (choosing one adds its
  import).
- **Quick fixes**: `let`/`var` → `const`, `===` → `==`, and adding a missing standard-library import. They are offered
  only when you ask (Ctrl+.), never on save.
- **JOUST: Rename Symbol** (F2), across files (below).
- **Formatting**: Format Document, Format Selection, and format on save if you turn it on, with the same formatter as
  `joust fmt`. It changes whitespace only, never a token, a comment or the script hash. The style is the compiler's
  version 1, which keeps spacing within a line as written. A file that does not parse is left as it was. With the
  bundled compiler (advisory), it formats as that compiler does. A project's own compiler formats only when the bundled
  compiler confirms the result is the same program; otherwise the file is left as it was, and you are told. An npm
  package's library is never formatted.
- **The build on save**, in a trusted project that uses its own compiler: an in-memory build, which writes nothing,
  adds the processor's and JuLC's errors, the ABI comparison with each committed `<Contract>.abi.json`, and the
  `joust.lock` check (JSC0731/JSC0732, as `joust build` reports them). **JOUST: Check build (in memory)** runs it on
  request anywhere.
- **JOUST: Show generated Java** and **Peek generated Java** open the Java a `.jsc` file lowers to, at the cursor's line.
- **JOUST: Accept ABI…** shows the diff between the committed ABI manifest and the build's, then accepts that contract
  (trusted workspaces).
- **JOUST: Remove invisible characters…** removes bidirectional controls and other format characters the compiler
  refuses, after you confirm.
- **The outline, folding and selection ranges**: contract, state fields, init, actions, destroys, views, fns, types,
  enums and variants.
- **Highlighting** (the TextMate grammar, which the docs site's Shiki also loads), a language configuration and snippets
  (`contract`, `action`, `match`, `fn`, `enum`).
- **JOUST: Build** runs the project's own `joust build` (npm) or `./gradlew build` as a task; its errors land in the
  Problems panel through the `$joust` problem matcher.

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
| JOUST: Rename Symbol | renames a name across the files that use it (F2 in `.jsc` files) |
| JOUST: Show generated Java | opens the Java the current file lowers to, read-only, at the cursor's line |
| JOUST: Peek generated Java | the same, in a peek view |
| JOUST: Check build (in memory) | builds the project in memory, writing nothing, and shows the later stages' errors, the ABI comparison and the `joust.lock` check |
| JOUST: Accept ABI… | shows the ABI manifest's diff, then accepts that contract through the project's CLI (trusted workspaces) |
| JOUST: Remove invisible characters… | removes invisible and bidirectional control characters from the file, after you confirm |
| JOUST: Build | the project's `joust build` or `./gradlew build`, as a task (trusted workspaces): the project at a workspace folder's root only; the npm CLI runs on VS Code's own Node (an absolute path), not the `node` on your PATH |
| JOUST: Restart analysis | a new engine worker, after "JOUST analysis unavailable"; it also reads the packages' libraries again |
| JOUST: Restart server | a new language server |
| JOUST: Show compiler and mode | which compiler analyses the current file, and why |

## Across files

- **Go to definition and Find references** follow imports between `.jsc` files: a library's fns, types and enums, a
  contract imported with `import contract`, and an npm package's libraries (read-only).
- **JOUST: Rename Symbol** (F2) renames a library's fn, type or enum in the library and in every file that imports it,
  started from the declaration or from a use. It edits open files only: when it needs others, the extension opens them
  (without an editor) and asks again. A contract's names, and a library's variants and fields, are renamed in their
  file only while no file imports it; an npm package's libraries are never edited.
- **`java:` imports** resolve as the build checks them in npm projects (the packages' `joust-lib/*.java` sources, read
  and described once, then cached); in Gradle projects they are advisory. Go to definition on a name a `java:` import
  brings, or on an npm package's `.jsc` library, opens its source as a read-only `joust-lib:` document. An open
  `joust-lib/` Java file's unsaved text is what the analysis reads; JOUST adds no features to Java files.

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
