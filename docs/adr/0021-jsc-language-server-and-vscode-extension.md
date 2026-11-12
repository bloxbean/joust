# ADR-0021: A language server and VS Code extension for `.jsc` 2

**Status:** Proposed (r2)
**Date:** 2026-11-13
**Builds on:** ADR-0018 (`.jsc` 2), **r9 as amended on `feat/jsc-2-m1a`** (ADR text at `3de519d`, code at `dfa7025`):
D3 (names), D9 (libraries and `java:` imports, built in M4), D12 (stable codes, source maps, generated Java, ABI
manifest), D13 (tree-sitter and TextMate grammars), D14 (npm projects), D16 (deployed scripts stay rebuildable), §8
(M5 is named there but not designed). ADR-0015 (the toolchain engine: JOUST's processor and JuLC in a GraalVM Web
Image, run in Node). ADR-0017 D4, D9 (the native `joust` defers project commands to the project's own
`@julclang/joust`), D6 (release assets) and its threat model.
**Depends on:** ADR-0018 being accepted (it is Proposed, r9, on #59/#63), and on ADR-0018's milestone **M1e**
(`feat/jsc-2-m1e`, in progress): a catalogued nesting limit, identical on the JVM and in Wasm, and on-chain messages
that do not depend on layout or names (§3a). §3a lists the amendments to ADR-0018 that this ADR needs; they land in
ADR-0018's own revision history.
**Design only:** no implementation in this PR. Every snippet is illustrative.
**Risk:** medium. Nothing here changes a validator, a wire format or what the build accepts: the build stays the
authority (D1). But the extension runs code inside the developer's editor on every repository they open, including
hostile clones, so trust and the choice of which code runs are security decisions (D3b, D10). And editor operations
can change what the build produces without the author meaning to: a rename can change the ABI manifest (D7), an
accepted ABI change strands deployed instances if it is not the one the author reviewed (D7a), and, until M1e lands,
any edit that moves a line changes the script hash (F12, D8).

## 1. Context

ADR-0018 makes `.jsc` 2 the language JavaScript developers start with, and promises editor support in two steps:
grammars now (D13, being built in M1d) and a language server later (M5). Since the front end already has stable
diagnostic codes, positions on both build paths, source maps and an ABI manifest (M1a), the question is no longer
whether a language server is possible but where it runs, which compiler it uses, how it stays responsive and safe on
hostile input, and how much of the front end it needs to change. Facts this design relies on (JOUST `main` at
`0e4ad41`; the `.jsc` 2 front end on `feat/jsc-2-m1a` at `dfa7025`; JuLC `v0.1.0-pre21-rc1`):

| # | Fact | Source |
|---|---|---|
| F1 | The `.jsc` 2 front end is `Lexer` → `Parser` (a `Syntax` tree) → `Checker` (the typed `Ir`) → `JavaEmitter`, with `Abi` for the manifest and source map. `JscV2.lower(source, fileName)` runs all of it on one file and returns the Java, the warnings, the source map and the ABI manifest | `julc-state-dsl/src/main/java/org/julclang/state/dsl/v2/JscV2.java:73-79` |
| F2 | **The lexer, the parser and the checker stop at the first error**: each rule throws a `JscException` (line, column, code, message); the parser has 55 `throw` sites, the checker 195, the lexer 14 (e.g. JSC0101, an unterminated comment, `Lexer.java:120`). Warnings are collected in a private set and returned only when checking succeeds; the exception carries none | `Parser.java:88`, `:94`; `Checker.java:99`, `:110-124`, `:604`; `org/julclang/state/dsl/JscException.java` |
| F3 | A position is a start only: `Syntax.Pos(int line, int col)`, 1-based. Columns count Java `char`s, i.e. UTF-16 code units, LSP's default position encoding. **The stored start is often not the name**: contracts, `fn`s, methods and `const`s record their keyword's position, a field access its receiver's, and an import one position for the whole statement with its names as strings | `Syntax.java:15`; `Lexer.java:108-126`; `Parser.java:155-166`, `:221-234`, `:254-256`, `:291-310`, `:359-366`, `:643-650` (review C4, probe) |
| F4 | **The lexer drops comments**; the `Syntax` tree holds no trivia, so a formatter cannot be written over it | `Lexer.java:114-126` |
| F5 | The checked IR resolves every name: a `LocalRef` points at its `Local`, which carries its declaration position; `CallFn` names a `fn`; every expression carries its type, time taint (D11) and position. The IR classes are package-private | `Ir.java:11-17`, `:99-107`, `:169`, `:193` |
| F6 | Diagnostics have **stable codes**: the catalogue lists 243 (238 errors, 5 warnings) in 12 sections, checked against the sources by `JscV2DiagnosticsTest` | `docs/reference/jsc-2-diagnostics.md` |
| F7 | The npm toolchain engine exposes `joust.build` (a whole file set: processor, JuLC, descriptions, generated Java, ABI) and `joust.run` (transition programs); there is no check-only entry. Calls are synchronous and run to completion on the calling JavaScript thread; a Java failure that escapes reaches JavaScript as a generic `TypeError` ("type incompatibility when transforming from/to JS"), not as a diagnostic | `julc-state-toolchain/src/wasm/java/.../JoustToolchainWasm.java:57-58`, `:84-95`; review F1, F8, S2 |
| F8 | The engine needs Wasm exception references: `loadImage` sets `--experimental-wasm-exnref` (needed on Node 22, on by default from Node 24), and loads the image with `createRequire(…)(file)`, which caches the module: **a second load in the same thread never completes** (pending after 20 s). `@julclang/joust` requires Node ≥ 22; the published `0.1.0-pre1-rc9` is 30,370,292 bytes unpacked and depends on `@julclang/joust-runtime` (→ `level` → `classic-level`, a native addon) and `@julclang/state-client` | `js/joust-runtime/src/load.js:9-11`; `js/joust/src/toolchain.js`; `npm view` (2026-11-13); review S1, F5 |
| F9 | The two build paths **print diagnostics in different shapes**: Gradle's `JscMain` prints `org/acme/File.jsc:12:5: error[JSC0502]: …` (as the catalogue says for both paths), `joust build` prints `error[JSC0502]: app/File.jsc:12:5: …`; each names the file relative to its source directory (`src/main/jsc/`, `contracts/`) | `JscException.format`; `JscMain.java:181-185`, `:211`; `js/joust/src/build.js:49` |
| F10 | On Gradle, javac's and the processor's messages on generated Java are not yet remapped to `.jsc` positions (the npm path remaps them, `Toolchain.java:143`); the toolchain reports an internal lowering failure at line −1 and catches only `JscException` and `RuntimeException` (as does `JscMain`) | M1a report, "Not in M1a"; `Toolchain.java:203-206`; `JscMain.java:210-216` |
| F11 | The npm CLI finds its project and the project's own `@julclang/joust` by ADR-0017 D4's rules, walking up to ancestors of the working directory; and **every project command of the npm launcher dispatches to the project's package before command dispatch** (ADR-0017 D5): run from a workspace with a planted package, the launcher executed the planted `bin` for `lsp --stdio` | `js/joust/src/project.js:1-30`; `js/joust/bin/joust.js:68-104`, `:112`; ADR-0017 D4, D5, I1, I5; review C2 probe |
| F12 | **The script hash depends on line numbers, the file name and some parameter names** at this pin. Generated `Require` messages embed `File.jsc:<line>` (`JavaEmitter.where`) and, for a `Key` parameter, its name; `Require` messages stay on-chain (ADR-0008). On `Ledger.jsc`: a blank line after `package`, an added `import` line, renaming the file, or renaming the `Key` parameters `to`/`who` each change the hash; re-indenting, CRLF line ends, a trailing comment, and renaming locals, state fields, actions, enums or variants do not. **M1e removes this** (§3a) | `JavaEmitter.java:161-163`, `:212-215`, `:497`, `:543`, `:594`, `:1027`; §1 measurements; review F4, S5, S6 (reproduced on the JVM at `dfa7025` and in Wasm) |
| F13 | **The front end has no nesting limit**: 1,000 nested parentheses overflow the JVM stack in the parser; in the Web Image `joust.lower` fails at 252 levels and `joust.build` at about 250, as a `TypeError`, and the outcome depends on the engine's warmth; the Gradle path accepts 500. **M1e adds a catalogued limit**, identical on both paths and well below the Web Image's depth, and handles operator chains iteratively | review S2, F8 (probes at `03c594b` and `dfa7025`) |
| F14 | Elci (same organisation) ships a tree-sitter grammar with highlight, locals, fold and indent queries, a corpus and CI, not published to npm; it has **no language server or VS Code extension**. Its ADR keeps "editor services separate from the compiler core: the CLI and later language server call the same parser, resolver, and type checker" | `github.com/bloxbean/elci-lang` at `a3ec190`: `README.md:62-63`, `tree-sitter-elci/README.md`, `adr/elci-language-syntax-compiler-design-adr.md` §8 |
| F15 | No editor files exist in the repository or the templates; the docs highlight `.jsc` with Kotlin's grammar. M1d (in progress) adds the tree-sitter and TextMate grammars; their paths are not fixed yet | `www/astro.config.mjs:26-27`; `js/create-joust/template/`; ADR-0018 D13 |
| F16 | VS Code: the Marketplace accepts only `major.minor.patch` extension versions ("semver pre-release tags are not supported"); pre-releases use `vsce publish --pre-release`; extensions can be platform-specific (`--target`) or universal. Workspace Trust offers `capabilities.untrustedWorkspaces: { supported: "limited", restrictedConfigurations }`, and Restricted Mode disables workspace tasks; settings of scope `machine` or `application` cannot be set by a workspace. `editor.codeActionsOnSave` and `editor.formatOnSave` are editor settings a workspace can set. Current stable is 1.146.0 | code.visualstudio.com: "Publishing extensions", "Workspace Trust", "Contribution points" (fetched 2026-11-13); `update.code.visualstudio.com/api/releases/stable` (2026-11-13) |
| F17 | Current versions: LSP 3.18 is the latest specification (versioned `documentChanges` in `WorkspaceEdit`, `version` on `publishDiagnostics`); `vscode-languageserver` and `vscode-languageclient` 11.0.0 (protocol 3.18.5; the client needs VS Code ≥ 1.91.0); `@vscode/test-electron` 3.1.0; `@vscode/vsce` 4.1.0; `ovsx` 1.4.0; `web-tree-sitter` 0.27.2; LSP4J 1.0.0 | microsoft.github.io/language-server-protocol; `npm view` and Maven Central metadata (2026-11-13) |
| F18 | Names others hold: the unscoped npm package **`joust`** belongs to an unrelated maintainer (0.0.0, no `bin` today), and `npx` assumes `--yes` when stdin is not a TTY (as for a language server); the Open VSX namespaces `julclang` and `bloxbean` do not exist (HTTP 404), so anyone can create them | `npm view joust`; `npm help exec` (npm 10.9.0); `open-vsx.org/api/julclang`, `/api/bloxbean` (2026-11-13) |

### Measurements

Measured for this ADR on 2026-11-13: Apple M4 Max (16 cores, 128 GiB), macOS 26.0.1, Node 22.12.0, JDK 25.0.2,
GraalVM 25.3.4.1 Web Image. The machine was shared (load average about 14), so the numbers are indicative. The
toolchain image was built from `feat/jsc-2-m1a` at `03c594b` with one measurement-only entry added, `joust.lower` (the
`JscV2.lower` call of F1, timed inside the engine; not committed). The reviewers reproduced the engine figures and the
hashes at `dfa7025`. Inputs: the M1a examples (`examples/dsl/src/main/jsc/.../`), and two synthetic files made from
`Ledger.jsc` with 70 and 300 extra block-bodied `fn`s (509 and 1,889 lines). Each figure is the range over three runs;
"warm" is the median of the 29 calls after the first in the engines, and of 40 calls after 10 warm-up calls on the
JVM. Scripts: a Node driver for the engines and a Java `main` in the `v2` package for the JVM (scratch, not
committed).

| | Toolchain image (as shipped, + `joust.lower`) | Front-end-only image (scratch) | JVM, JDK 25 (timings: default heap; memory: `-Xmx64m`, Serial GC) |
|---|---|---|---|
| Wasm size, raw / gzip -9 | 31,153,511 / 11,157,442 B | 14,038,597 / 5,030,381 B | — |
| Engine load in Node | 385–426 ms | 187–203 ms | — |
| Process start to the first lowering of `Ledger.jsc` (wall) | 0.53–0.54 s; also 0.53–0.54 s in VS Code 1.109.0's extension-host runtime (Electron 39.3.0, Node 22.21.1), which loads the image without the exnref flag (Node 22.12.0 refuses it without the flag) | 0.29–0.31 s | 0.08–0.09 s (the front end only; no LSP library) |
| Resident memory after load | 357–361 MB | 227–228 MB | 228 MB peak for the whole run |
| First lowering in a fresh engine (`Counter2`) | 76–79 ms | 41–42 ms | 39–50 ms |
| Warm lowering: `Counter2` 26 lines / `Ledger` 89 / `Shelf` 69 / `Pledges` 71 | 0.8 / 2.7 / 1.8 / 2.5 ms | 0.8 / 2.6–2.8 / 1.8 / 2.5 ms | 0.43–0.45 / 1.03–1.16 / 0.67–0.74 / 0.65–0.70 ms |
| Warm lowering: 509 / 1,889 lines | 6.9–7.1 / 21.6 ms | 7.0–7.2 / 21.2–22.0 ms | 1.87–1.98 / 4.50–4.85 ms |
| Warm parse + check only (no emitter, no ABI) | not exposed (estimate: about 35–45% of lowering, from the JVM ratio) | — | `Ledger` 0.37–0.42 ms; 1,889 lines 1.47–1.67 ms |
| A file whose first error is a type error (line 31) / a syntax error (line 61) | 0.3 / 0.2–0.3 ms | 0.3 ms | 0.07 / 0.05 ms |
| `joust.build` of one file, first / warm | `Counter2` 764–776 / 450–476 ms; `Ledger` 1,621–1,631 / 1,545–1,602 ms | — | — |
| Resident memory after six builds | 600–603 MB | — | — |

**r2: the engine in workers** (same image and machine; two runs each):

| | Result |
|---|---|
| Analysis in a `worker_thread`, the protocol thread holding no engine: round trip of one lowering (post, engine, reply) | `Ledger` median 2.8–2.9 ms, p95 3.6–3.7 ms; 1,889 lines median 21.5–21.9 ms, p95 23.2–23.4 ms (about 0.1–0.3 ms over the in-thread figures); process RSS 388–391 MB |
| A watchdog terminating a worker in the middle of a synchronous `joust.build` (the main thread ticking every 10 ms) | `worker.terminate()` returned in 9 ms; the main thread ran 140 ticks during the 1.5 s the worker was busy; the process continued |
| `resourceLimits.maxOldGenerationSizeMb` on the engine's worker (the image uses WasmGC, so Java objects live in V8's heap) | 64 MB: the first `Ledger` build ends the worker with `ERR_WORKER_OUT_OF_MEMORY`, the process survives; 256 MB and 1,024 MB: three builds succeed |
| Two engines, one per thread (r1: checks on the main thread, builds in a worker) | 504–505 MB with both loaded, 725 MB after three builds; a main-thread lowering every 20 ms during the builds: median 2.7 ms, max 193–250 ms (the stall r2's design removes) |
| A minimal server payload: the engine files plus `vscode-languageserver` 11.0.0 and `vscode-languageserver-textdocument` 1.0.16, zipped at `-9` (a `.vsix` is a zip) | 11,537,164 B; no `.node` file |

What follows from them: re-checking a whole file on every edit is cheap (milliseconds even at 1,889 lines, about 20
times the size of the largest example), so the server needs **no incremental checker** (D4). Running the engine in a
worker costs almost nothing and makes it killable and memory-bounded (D2a). Memory, not speed, is the cost of the Wasm
engine: about 360–390 MB for analysis, and about 365 MB more while a build worker lives. The JVM front end is about
2–5 times faster warm, which does not matter at these sizes, and needs a JDK, which npm users do not have.

## 2. Threat model and trust assumptions

- **Untrusted:** the contents of every folder the editor opens, **and of its ancestors**: `.jsc` and `.java` sources,
  `joust.config.json`, `package.json`, `node_modules` (including a planted `@julclang/joust`, `@julclang/joust-runtime`
  or `vscode-languageserver`), symlinks, FIFOs and devices, Gradle files and wrappers, `.vscode/` workspace settings
  (including `editor.codeActionsOnSave`) and tasks, `joust.lock`, and library sources a `java:` import names (D9 of
  ADR-0018). A repository may be a hostile clone. Untrusted files are **inputs** to the analysis (parsed, checked,
  compiled), never code to run, until the user trusts the folder that contains them.
- **Trusted:** the extension as installed from the marketplace (its own code and bundled engine), the editor itself,
  the user's own settings (user scope, not workspace scope), a server the user installed and names by absolute path
  (other editors, D3b), and, **once the user trusts the workspace**, a project's own `@julclang/joust` whose real path
  lies inside a trusted folder (the code `npm run build` would run anyway: ADR-0017's argument), and the commands the
  user explicitly invokes.
- **What the language tooling must not do:** run workspace code in an untrusted workspace, or code from outside the
  trusted folders in a trusted one (no `node_modules` code, no Gradle, no executable named by a workspace setting);
  write to the workspace on its own (only edits the user applies and commands the user invokes, D10); open the network
  (no telemetry, no downloads; Q3(b) is the one open exception, and only on explicit consent); read outside the
  workspace in restricted mode (D4b); let a hostile file hang, crash or exhaust the server (D2a, D4b); change a deployed
  script by an edit the user did not mean as a code change (L12); let an ABI change be accepted that the user did not
  review (D7a).
- **The build remains the authority.** An editor diagnostic is advice. A false "no error" in the editor never
  reaches the chain: the build runs the same front end and, unlike the editor, the processor, JuLC and the ABI gate.
  But a false error, or a quick fix that changes meaning, misleads the least experienced users (ADR-0018 §2), so the
  editor runs the build's own front end (D2), says when it is not the project's compiler (D3a), and checks every edit
  it proposes (L6).

## 3. Decision

### D1. One front end: the editor runs the compiler's checker, and the build stays the authority

The language server answers from the **same lexer, parser and checker** that `joust build` and Gradle's
`generateJsc` run. It does not have a second parser or type checker for semantics. Its diagnostics as you type are
the front end's (the `JSC` codes of F6); diagnostics from later stages (processor, JuLC, ABI gate, script size) come
from running the build (D4). When the server runs the project's own compiler over the project's inputs
("project-matched", D3a), it agrees with the build's front end (L1); otherwise it is advisory and says so.

### D2. Architecture: a Java language core, a JavaScript protocol layer, one Wasm engine

- **The language core** is Java, in `julc-state-dsl` beside the checker (same package, since the IR is
  package-private, F5). It turns a project snapshot (D4a) into a **document model** per file: diagnostics, document
  symbols, declarations and references (each a name span, a kind and a declaration id; D6), scopes with their ranges,
  the type and time taint of each expression with its range, folding ranges, and the source map. The model is plain
  records, versioned (`modelVersion`), with golden tests; it carries no `Syntax` or `Ir` objects. Edit operations
  (rename checks, quick fixes, formatting) are separate core entries that return text edits for a stated snapshot.
- **The engine:** the core is compiled into the **existing toolchain Web Image** as new entries next to
  `joust.build` (`joust.lang.analyze`, `joust.lang.rename`, `joust.lang.fixes`, later `joust.lang.format`;
  illustrative names). One image, one version, the build's own code. Every `joust.lang.*` entry catches any
  `Throwable` and returns it as one internal-error result (F7's `TypeError` never reaches the protocol layer as an
  unexplained failure). It must be the full toolchain image rather than a front-end-only one, because `java:` imports
  (ADR-0018 D9) are typed by javac attribution, which only the full image contains; the front-end-only image's
  measured saving (about 130 MB and 200 ms, §1) does not pay for a second artifact.
- **The protocol layer** is JavaScript: `vscode-languageserver` (pinned exactly, as the JS packages pin
  cardano-js-sdk). It keeps open buffers, debounces, and answers most requests (hover, definition, references,
  symbols, semantic tokens, completion lists) by lookups in the document model. **It holds no engine**: every engine
  runs in a worker (D2a).
- **Editors** talk LSP to the server. The VS Code extension is a thin client that finds and starts the right server
  (D3, D3b); Neovim and Helix start a server the user installed (D3b, D9).

**Alternatives considered:**
- **(a) A Java server with LSP4J, reusing the checker on the JVM.** The fastest analysis (§1: about 2–5 times the
  Wasm engine warm, about 0.1 s to start without the LSP library), the most natural code. Rejected as the only host:
  npm users have no JDK (ADR-0015's premise), and the VS Code extension would need to find or ship one. Running LSP4J
  itself inside the Web Image (its JSON-RPC launcher dispatches on Java threads) is untested and not proposed. The
  language core is plain Java, so a JVM host stays possible later (Q8).
- **(b) The Wasm engine in Node** (this decision, with the core in Java). No JDK; VS Code's extension host already
  provides a Node runtime that loads the engine (§1); one engine for checks and builds; the same `JscV2` code as the
  build.
- **(c) Both hosts from the start.** Rejected for M5: two hosts double the protocol tests and the packaging, for
  users (Gradle developers in non-VS Code editors without Node) who are not the audience of `.jsc` (Q8).
- **(d) A native-image Java server** (as the native `joust` of ADR-0017). Fast start, no JDK, but one binary per
  platform and a platform-specific `.vsix` per target (F16); ADR-0017's CLI ships no Windows build. The Wasm engine is
  platform-neutral, so one universal `.vsix` serves every platform. Rejected.
- **(e) A JavaScript checker** (or tree-sitter plus JavaScript analysis). Rejected: a second implementation of the
  typing, time and BigMap rules would disagree with the build exactly where the rules are subtle (D11's dataflow, J10's
  names). ADR-0018 M1b's JavaScript interpreter is deliberately independent as a test oracle; an editor needs the
  opposite, identity with the build.

### D2a. Engine hosting: workers, a watchdog, memory bounds, quarantine (new in r2)

A synchronous engine call cannot be interrupted on its own thread (F7), and an engine cannot be re-loaded in the
thread that loaded it (F8). So:

- **Every engine runs in its own `worker_thread`**: one analysis worker per project root and compiler version, and a
  build worker started on demand (D4). The protocol thread holds no engine and stays responsive (§1: 0.1–0.3 ms per
  round trip).
- **A watchdog on the protocol thread** gives each call a time cap (illustrative: 2 s for analysis and edit
  operations, 120 s for a build; tuned in M5a) and terminates the worker when it is exceeded (§1: 9 ms). `$/cancelRequest`
  for a request in an engine call terminates that worker too when the call is still running after a grace period. A
  **restart is always a new worker**, never a re-load in an existing thread.
- **Memory:** each worker gets `resourceLimits` (`maxOldGenerationSizeMb`, illustrative 512 for analysis and 768 for a
  build), which bounds the engine's heap (§1: the image's objects live in V8's heap; a worker over its limit ends with
  `ERR_WORKER_OUT_OF_MEMORY` and the process survives). M5a measures the worst-case total against L10's bounds.
- **Quarantine:** a call that times out, exceeds memory or fails internally reports one internal-error diagnostic on
  the file (code `JSC-INTERNAL`, not a catalogue code, with "analysis stopped: time/memory limit" or the failure's
  class), and the **input that caused it** (the SHA-256 of the snapshot's contents plus the compiler version) is
  quarantined: the same input is not analysed again; the next edit is analysed after a backoff (illustrative: 1 s,
  doubling to 30 s while failures repeat).
- **Restart limits and "analysis unavailable":** after three worker failures within five minutes for one project, the
  server stops analysing it, keeps the last diagnostics marked stale, shows "JOUST analysis unavailable" (status bar
  and one notification) with a **Restart analysis** command, and keeps answering requests from the last model. If the
  server process itself exits, `vscode-languageclient`'s restart policy applies; when it gives up, the extension shows
  the same state with a **Restart server** command.

### D3. Which compiler runs: the project's own, else the extension's, said aloud

- **npm project, trusted workspace:** the extension finds the project and its `@julclang/joust` by ADR-0017 D4's
  rules (F11), then applies D3b's confinement. If the package is eligible, it starts that package's **LSP entry** (D3b)
  with the editor's Node runtime. The server's compiler is then the project's compiler, by construction. A package that
  predates the LSP entry (any release before M5a's) is not eligible.
- **Otherwise** (untrusted workspace; npm project not installed or not eligible; Gradle project; a loose `.jsc` file):
  the extension starts its **bundled** server (the extension pins one exact `@julclang/joust` version). The status bar
  and the server's `serverInfo` show which compiler is running ("JOUST 0.1.0-pre1-rc9, bundled, advisory"), and each
  diagnostic's `source` says so. For a Gradle project the extension reads the `org.julclang.joust` plugin version from
  the build files **as text** (never by running Gradle) and says when it differs. It does not download a matching
  engine (Q3).
- ADR-0017 I1 says the CLI never falls back to an implementation of its own. The editor's fallback differs on purpose:
  it only reads and reports (no artifacts, no commands, no ABI acceptance, D7a), and it is labelled as advisory
  wherever it speaks. Commands that act on a project (D7) always go through the project's own CLI or Gradle.
- **Trust granted mid-session:** on `onDidGrantWorkspaceTrust` the extension re-runs selection and restarts into the
  project's server when it is now eligible.
- The extension's own version is separate from JOUST's: the Marketplace accepts only `major.minor.patch` (F16), and
  the extension is a thin client. Compatibility is the LSP capabilities plus an `experimental.joust` capability that
  carries a protocol version for the custom requests (generated Java, ABI diff, mode); the extension refuses a server
  whose protocol version it does not support and falls back to the bundled one, saying why.

### D3a. Two modes: project-matched and advisory (new in r2)

- **Project-matched:** the server runs the project's own compiler (D3), and the analysis of the file is complete for
  its snapshot (D4a): every file and library the compiler resolves for it is in the snapshot, the configuration is the
  project's, and the file uses only what the server's milestone supports. The front-end diagnostics then equal the
  build's (L1), and quick fixes, renames and **Accept ABI** (D7a) are offered.
- **Advisory:** everything else, including the bundled server in any project, a Gradle project, restricted mode, and a
  project-matched server whose analysis of a file is incomplete (a `java:` import it cannot resolve, D4b). Diagnostics
  carry an "advisory" label (in `source` and the status bar); edit validation is still done (L6) but never presented as
  evidence that the project's build accepts the result; Accept ABI is not offered.
- **Imports before M5c:** at the pinned revision the compiler accepts only `std/*` imports; from ADR-0018 M4 on, it
  resolves `.jsc` libraries, contracts and `java:` libraries itself, and the snapshot includes them (D4a). So
  project-matched analysis of an importing file is available as soon as the compiler supports the import; M5c adds
  navigation and rename **across** files, not correctness of single-file analysis.

### D3b. Launch paths: never through the CLI's dispatch, never from outside the trusted folder (new in r2)

- **The bundled server** has its own entry module inside the extension, a single bundled file (the server code and
  its dependencies resolved at build time, so no `require` resolves from the workspace), started by the extension with
  an absolute path and the extension host's Node (`process.execPath`). It never calls project discovery or the npm
  CLI's launcher (F11), so a planted or old project package cannot be reached through it, in any case: untrusted,
  uninstalled, Gradle, loose file.
- **A project's server** is started through the package's **LSP entry**, a module exported for that purpose
  (illustrative: `@julclang/joust/lsp`, also installed as a second bin `joust-lsp`), never through `bin/joust.js`, so
  no second project dispatch happens. It is eligible only if the workspace is trusted, the package's `package.json` (a
  regular file of at most 64 KiB) declares the LSP entry, and **the real paths of the package directory and of the
  entry file lie inside a folder VS Code reports as trusted**. A package found in an ancestor of the trusted folders
  (a hostile monorepo root), or through a symlink leaving them, is not started; the extension uses the bundled server
  and says "compiler found outside the trusted folder".
- **Other editors** have no workspace trust, so the documented default is a **user-installed server named by absolute
  path**: a global `@julclang/joust` at a pinned version, started as `<prefix>/bin/joust-lsp --stdio` (advisory unless
  the project's version matches). The project-local variant (`./node_modules/.bin/joust-lsp --stdio`) is documented
  with a warning that it runs the repository's code. **`npx joust` is never documented** (F18); a docs test refuses it
  in the editor configurations. The native `joust` of ADR-0017 does not serve as a language server: it would dispatch
  `lsp` to the project's package (ADR-0017 D9).
- Every executable is spawned by absolute path, never a bare name.

### D4. Checking model: whole files on every edit, builds in a worker

- **As you type:** after a debounce (about 150 ms; tuned in M5a), the server re-analyses the edited file from scratch
  through `joust.lang.analyze` on its analysis worker, over the current snapshot (D4a). No incremental parser or
  checker: at the measured cost (§1) it would add complexity without a user-visible gain. When dependencies exist
  (ADR-0018 M4), it re-analyses the edited file's dependents too, and caches what is expensive and stable: library
  signatures from javac attribution, **keyed by the SHA-256 of the bytes it actually read**, in per-workspace storage,
  re-verified on use; a `joust.lock` mismatch is reported as the build reports it (ADR-0018 J12), and the lock's recorded
  hashes are never a cache key. Attribution cost in the engine is not measured yet (an M5c entry gate).
- **On save (trusted, project-matched npm projects):** the server runs `joust.build` over the snapshot, in memory, on a
  build worker, and adds the later stages' diagnostics: processor and JuLC errors remapped to `.jsc` positions (with
  the generated location as related information), the ABI comparison against the committed `<Contract>.abi.json`
  (JSC0002/JSC0003), script size. It **writes nothing**: no `build/joust/`, no generated Java on disk, no accepted
  manifest. The worker starts on the first save and stops after an idle period (illustrative: 5 minutes), returning its
  memory. Its results belong to the snapshot generation they were computed for (D4a). **In restricted mode and advisory
  mode there are no on-save builds**; the command **JOUST: Check build (in memory)** runs one on request, on the
  bundled engine, with the same caps (D10). Gradle projects get front-end diagnostics only; their build output reaches
  the Problems panel through a task (D9).
- **How many servers and workers:** one server process per VS Code window; inside it, one analysis worker per (project
  root, compiler version) with open `.jsc` files, at most two alive at once (least recently used stopped first), each
  stopped after 10 minutes without an open file of its project; at most one build worker per window. Other editors
  start one server per editor session.
- **Limits:** a per-file size cap (illustrative: 1 MiB), the watchdog caps (D2a), a cap on files per snapshot, and
  M1e's nesting limit (F13), which turns deep input into a catalogued error on every path. The toolchain's internal
  error at line −1 (F10) is reported at 1:1.

### D4a. Snapshots and versioned edits (new in r2)

- **A snapshot** is a generation number over everything an analysis or build reads: the open buffers with their LSP
  versions, the contents of every other file read (by hash), `joust.config.json`, dependency contents (libraries,
  `joust.lock`) and the compiler version. Any change to any of them starts a new generation.
- **Results are discarded when stale:** an asynchronous analysis or build result is published only if no input it read
  has changed since; otherwise it is dropped and the analysis re-run. A library edit therefore invalidates a pending
  build of its importers even when their buffers did not change.
- **Diagnostics carry versions:** `publishDiagnostics` includes the document's `version`; the server never publishes for
  a version older than one it already published for that document. Between an edit and its analysis the editor shows
  the previous version's diagnostics, as LSP intends; build diagnostics for a saved generation are withdrawn as soon as
  a newer generation exists (L8).
- **Edits are bound to versions:** rename and quick-fix edits are `WorkspaceEdit.documentChanges` with the
  `OptionalVersionedTextDocumentIdentifier` versions the server validated, so the client refuses an edit whose
  document changed in between. A client without `documentChanges` support gets only single-document edits computed at
  `codeAction/resolve` (or rename) time on the current text, and no cross-file rename. Completion uses the last model
  only to decide what kind of item to offer (D5); every text edit a completion item carries is computed against the
  current text.

### D4b. One input policy for every file the server reads (new in r2)

- Every path is resolved by real path. Only regular files are read (never a FIFO, device or socket), at most cap + 1
  bytes (anything longer is "too large"). Directory symlinks are not followed when enumerating; file symlinks are
  followed only to a real path inside the workspace folders.
- From `joust.config.json` the server takes only `contracts` (where the sources are), confined to the workspace folder
  (otherwise ignored, with a diagnostic on the config); it never reads `networks`, URLs or `out`.
- Library roots are enumerated explicitly by the toolchain's resolution rules (ADR-0018 D9), never from import text.
  In restricted mode a library root must have its real path inside the workspace folders; in trusted mode a linked
  package outside them (`npm link`) is read under the same file rules.
- **Gradle projects:** the bundled server does not run Gradle and does not read `~/.gradle` or `~/.m2`, so a `java:`
  import in a Gradle project is reported as unresolved, and that file's analysis is advisory (D3a).
- The virtual documents (`joust-gen:` generated Java, `joust-lib:` library sources) are served by opaque ids from the
  current model, never by a path taken from the URI.

### D5. Error recovery, in stages; tree-sitter stays in the editor

The front end stops at the first error, and its warnings are lost when it does (F2). An editor needs more: several
errors at once, and a tree to complete in while the user is half way through a line.

- **M5a: none.** A file's analysis returns **either** its first fatal error (exactly the build's) **or**, when checking
  succeeds, its warnings. No compiler change.
- **M5b: recovery in the lexer, the parser and the checker.** The lexer turns a bad token into an error token and
  continues (today it throws, e.g. JSC0101); the parser synchronises at declaration, contract member and statement
  boundaries, records the error and inserts an error node; the checker checks each declaration, method and `fn`
  separately, gives an erroneous expression an error type that suppresses follow-on errors, and continues; warnings
  from declarations without errors are kept. Recovery runs only in the language core's mode; `JscV2.lower` keeps
  stopping at the first error, and the recovering mode must agree with it (L5) and must not invent errors elsewhere
  (L13).
- **Completion in a broken buffer** uses the last model that analysed, plus the tokens around the cursor from the
  recovering lexer (M5b), to decide what is being completed (D7).
- **Tree-sitter is not in the server.** M1d's grammar serves editors' highlighting, folding, indentation and
  structural selection; but it is a second parser, and the M1a report already asks that the two agree on a shared
  corpus. Using its tree for semantics would need a third component (CST to `Syntax`) and would make the editor
  disagree with the build on exactly the malformed inputs. Rejected for M5; reconsider only if the parser's recovery
  proves inadequate in M5b (Q6). This supersedes ADR-0018 D13's "later the language server's syntax layer" (§3a).

### D6. Positions, name spans and references (revised in r2)

- Positions cross the protocol as LSP's default UTF-16 code units: the lexer already counts Java `char`s (F3); the
  server subtracts one from line and column. Tested with non-BMP characters in strings and comments before a
  diagnostic on the same line (L11).
- **Today's positions stay as they are.** Diagnostic positions and the positions the emitter writes into source maps
  and, until M1e, messages (F3, F12) are unchanged, so lowering is unchanged (L5).
- **New, separate span data** (recorded by the parser and checker for the language core only): a **name span** (start
  and end) for every declaration name (contract, state field, method, `fn`, parameter, `const`, lambda, loop and
  `match` binding, type, enum, variant, record field), for every name in an `import { … }` list, for member names in
  `a.b` and in object literals (including `{ goal }` shorthand, which is both a field and a reference), and for every
  type use; a **node extent** for every declaration, block and expression; and an explicit **reference → declaration
  id** mapping for every name use the checker resolves, including built-ins.
- **Milestones:** declaration name spans and declaration extents are M5a (symbols, selection ranges and folding need
  them); expression extents, member and type-use spans and the full reference mapping are M5b. Both are tested with
  hand-written range expectations, including comments between a keyword and its name, and lowering is compared
  separately (L5).

### D7. Features

| Feature | How | Milestone |
|---|---|---|
| Diagnostics as you type | the front end's, with `code` = `JSC0502` and `codeDescription.href` to the catalogue's row on the docs site (a fixed `https` base, never configurable by a workspace); warnings with their codes; the lexer's character rules (JSC0145–JSC0148) underline the offending character | M5a (first error, or the warnings), M5b (recovered) |
| Build diagnostics on save | D4: processor and JuLC errors at `.jsc` positions with the generated location as related information; ABI and size findings; project-matched, trusted npm projects only | M5b |
| Document symbols, outline, folding | from the parse and D6's M5a spans: contract, state fields, `init`/actions/destroys/views, `fn`s, `type`s, `enum`s and variants | M5a |
| Hover | the type of a name or expression; "time value (D11)" when tainted, and why; a `std` row's `.jsc` signature, domain and Java target (D8); a method's audience and effects. Plain text or untrusted markdown with workspace text escaped (D10) | M5b |
| Go to definition, find references | within a file from D6's reference mapping: locals, parameters, state fields, `fn`s, types, enums, variants, built-ins (to their docs); across `.jsc` files and libraries, `import contract`; into `java:` imports as **read-only** documents of the library's on-chain sources (a `joust-lib:` URI; positions from javac attribution) | M5b (file), M5c (across) |
| Completion | in scope: locals, parameters, state fields, built-ins (`caller`, `received`, `balance`, `validFrom`, `validTo`, `state`, `params`, `funds`), `fn`s and the prelude; `std` rows inside `import { … } from "std/…"`; a record's fields and a `List`/`Map`/`BigMap`/`Option`'s operations after `.`; enum variants and `Some`/`None` where a value of that type is expected; **all arms** of a `match` on an enum or `Option`, as a snippet; modifiers (`only`, `authorized`, `after`, `before`, `payable`, `open`) after a method's parameters; types in type positions. **Completion elsewhere that adds an import line is held back until M1e** (F12; review S6). Items are filtered so that no name is offered that the checker refuses at that position (D3's shadowing rules); M5b's tests insert every offered item at the corpus positions and re-check | M5b |
| Rename | of a local, parameter, `fn`, type, enum, variant or field, within the file (M5b) and the project (M5c). The new name is checked against every D3 and J10 rule (no shadowing of state fields or built-ins, no binding named like a declaration, ASCII, not a Java keyword or a processor-generated name), and the renamed snapshot is re-analysed: the rename is offered only if no new diagnostic appears and **every other reference still resolves to the same declaration** (L6). A name the ABI manifest records (state fields, fields reachable from the datum or a redeemer, action and destroy names and their parameters) is renamed only after a warning that the manifest, the generated client and the description change, and that the ABI gate will ask for acceptance at the next build; after M1e the warning also says that the script does not change (L12). **Before M1e, a rename that would change the script hash (F12: a `Key` parameter, which reaches a message) is not offered.** Never edits `.java` files, library packages, or files outside the project's contracts | M5b, M5c |
| Semantic tokens | from the IR: state field vs local vs parameter vs `fn` vs `std` function vs type, enum, variant; modifiers for `readonly` (every binding), `defaultLibrary` (prelude, `std`), and a custom `timeValue` modifier for D11-tainted expressions | M5b |
| Code actions | kind **`quickfix` only**, never `source.*` (so `editor.codeActionsOnSave` cannot apply them; F16), and only **machine-applicable fixes**, each re-checked before it is offered (L6): `let`/`var` → `const` (JSC0128), `===`/`!==` → `==`/`!=` (JSC0133), delete an invisible format or control character (JSC0145, JSC0148; never `isPreferred`, since it can change a string's content), and, **after M1e**, add the missing `std` import (JSC0704; it inserts a line, F12). No fix moves lines before M1e. Diagnostics whose fix needs restructuring (JSC0115's `function` → a typed `fn`, JSC0130's spread update, JSC0129's early return) link to the catalogue instead of guessing | M5b |
| Generated Java | "Show generated Java" opens the lowering as a read-only virtual document at the line the source map gives for the cursor; "peek" shows it inline. From the model's source map (the as-you-type lowering produces it, F1) | M5b |
| ABI manifest | D7a | M5b |
| Commands | **JOUST: Build** (the project's `joust build` or `./gradlew build`), **Accept ABI…** (D7a), **Migrate to .jsc 2** (`joust migrate`, ADR-0018 D16, once M1d ships it), **Show generated Java**, **Check build (in memory)** (D4), **Restart analysis**, **Show compiler and mode**. Commands that run anything run it as a VS Code task through the project's own CLI or `gradlew`, only in a trusted workspace. Commands are reachable from the command palette and code lenses, never from links inside markdown (D10) | M5a (Build), M5b (rest) |
| Formatting | D8 | M5d |

Not in M5: inlay hints, signature help beyond hover, call hierarchy, workspace symbols across libraries,
debugging (traces are `joust build --traces`), budget figures in hovers (ADR-0018 J19's build report could feed
them later), VS Code for the Web (D9).

### D7a. Accept ABI: one contract, the manifest the user saw, the compiler that accepts (new in r2)

Acceptance tells ADR-0018 D12's gate that a datum or redeemer change is intended, so the accepted manifest must be the
reviewed one.

- **The diff comes from the tool that accepts.** **Accept ABI…** runs, as a visible task, the project's own
  `joust abi diff <Contract>` (npm) or `gradlew jscAbiDiff --contract <C>` (Gradle; illustrative names), which prints
  the committed and the new manifest and the SHA-256 of the new one; the extension shows that output as a diff. The
  server's on-save comparison only *raises* JSC0002/JSC0003; it is never the diff that is accepted.
- **Acceptance is per contract and bound to the hash shown:** after a modal confirmation naming the one contract and
  its change, the extension runs `joust build --accept-abi=<Contract>@<sha256>` (Gradle: `acceptJscAbi --contract <C>
  --sha256 <h>`, task options, never a property), which accepts only that contract and **refuses** when the manifest it
  computes now differs from `<sha256>` (the file changed between the diff and the acceptance). Today `--accept-abi`
  accepts every changed manifest in the build (`js/joust/src/abi.js:28-46`) and Gradle's task the whole source
  directory; narrowing it is an ADR-0018 D12 amendment (§3a).
- **Never offered** in advisory mode, in restricted mode, as a code action, or through a markdown link (D10).

### D8. A formatter, and the hash-stability contract (revised in r2)

`.jsc` 2 has no formatter, and one cannot be written over today's tree (comments are dropped, F4). At the pinned
revision it also could not be safe: a moved line, a file name and some parameter names reach the script hash (F12).

- **M1e decides it in ADR-0018** (§3a): generated on-chain messages become independent of layout, comments, file
  names and **all** names, using name-free keys (for example the constructor index of the method plus a fixed ordinal
  of the construct in source order, not the emitter's helper counter), unique within a script, with a source map from
  each key back to `File.jsc:line` for traces, JVM `RequireException`s and audits. **Gate:** M5d starts after M1e is
  merged.
- **The hash-stability contract** this ADR relies on, once M1e lands: the script hash, the ABI manifest's encodings
  and the description's data shapes depend only on the checked program, never on layout, comments, blank lines, line
  ends, the file's name or any name. Names reach the ABI manifest, the description and the generated client (so an
  ABI-recorded rename changes those, D7), never the script. Reordering declarations changes the checked program and
  counts as a code change; the formatter never reorders.
- **Design:** one fixed style with no options (as `gofmt`), over a trivia-preserving syntax tree (the lexer keeps
  comments and blank lines as trivia attached to tokens; the existing `Syntax` tree is built from the same tokens).
  `joust fmt` (both CLIs) and the server's `textDocument/formatting` call the same core entry.
- **Invariants (L7, L12):** idempotent; every comment survives, in order, attached to the same token; the checked IR is
  unchanged except for positions; the script hash, the ABI manifest and the description are unchanged, and the
  generated Java differs only in source-position comments. Tested over the whole corpus (D12).

### D9. Packaging

- **The VS Code extension** (`js/vscode-joust/`, beside the other JS packages; never published to npm):
  - M1d's TextMate grammar and a language configuration (comments, brackets, auto-closing pairs, indentation, word
    pattern) for `.jsc`; snippets (contract, action, `match`, `fn`, `enum`); any JSON schema it contributes is bundled,
    so it fetches nothing;
  - the client: server selection and start (D3, D3b), status bar (compiler version, mode, trust state), commands (D7),
    the ABI diff view (D7a), read-only virtual documents (`joust-gen:`, `joust-lib:`; D4b);
  - **Problems-panel integration**: a problem matcher `$joust` and task definitions for `joust build` and
    `./gradlew build`, so build output lands in the Problems panel with clickable positions. This needs one output
    shape: `joust build` adopts the catalogue's `File.jsc:12:5: error[JSC0502]: …`, the shape Gradle already prints
    (F9); both keep naming files relative to their source directory, which each task definition gives the matcher.
    That change to `js/joust/src/build.js` is part of M5a;
  - **the bundled server: only its entry, bundled into one file with `vscode-languageserver`, plus the toolchain
    engine files** (`joust-toolchain.js`, `.wasm`, and `load.js`'s logic), taken from the same release's
    `@julclang/joust` tarball. Not the package's dependency tree: `@julclang/joust-runtime` brings `classic-level`, a
    native addon, and `@julclang/state-client` (F8), which the server does not need. So the `.vsix` contains no native
    code and is **universal** (no `--target` builds). Measured payload: 11,537,164 B zipped (§1). M5a's exit adds a CI
    check that the `.vsix` contains no `.node` file and stays under 16 MB;
  - `capabilities.untrustedWorkspaces: { supported: "limited", restrictedConfigurations: […] }` (D10), and
    `extensionKind: ["workspace"]`, so in Remote Development it runs where the files are.
- **VS Code for the Web** (vscode.dev) is later work: the engine also runs in browsers (ADR-0015), but the server
  would need a browser host. Not in M5.
- **Other editors:** a user-installed `joust-lsp --stdio` by absolute path (D3b); the docs give configurations for
  Neovim (`vim.lsp.config`/nvim-lspconfig) and Helix (`languages.toml`), with M1d's tree-sitter grammar and queries for
  highlighting. Packaging the tree-sitter grammar for those editors (queries, `tree-sitter.json`) is M5e; publishing it
  to npm or crates.io is a release decision, like the extension's (D11).
- **Versions:** the extension pins the bundled `@julclang/joust` exactly and is released when JOUST is, with its own
  numeric version (F16: even minor for releases, odd minor for pre-releases, as the Marketplace recommends). The
  minimum VS Code version (`engines.vscode`) is the oldest version whose extension host loads the engine in the smoke
  test of D12; 1.109.0 is verified locally (§1), the smoke tests also run on the current stable (1.146.0 on
  2026-11-13), and `vscode-languageclient` 11.0.0 alone would allow 1.91.0 (Q5).
- **The templates** (`npm create joust`, `joust new -t node|web`) add `.vscode/extensions.json` recommending the
  extension once it is published and Q4 is resolved (D11).

### D10. Trust and security

- **Restricted mode** (an untrusted workspace): only the bundled server runs, in advisory mode; front-end analysis
  works (it reads files, it does not run them: the toolchain attributes sources with JuLC's embedded javac and calls
  JOUST's generators directly, `Toolchain.java:28-34`, so no annotation processor or other code from the workspace
  runs); **no on-save builds** (a build attributes the workspace's `.java` files and compiles with JuLC, a larger
  surface than analysis), only the explicit **Check build (in memory)** command, under the same caps; **no command that
  runs a CLI, `npm`, `node_modules` code or `gradlew`**, and VS Code itself disables workspace tasks in Restricted Mode;
  file reads confined to the workspace folders (D4b). The status bar says "restricted: bundled compiler, advisory".
- **Settings:** no setting names an executable, a path to a server, or arguments, unless its scope is `machine` (user
  settings only); every other setting the extension reads that could change what runs or what is read is listed in
  `restrictedConfigurations` (F16). No setting changes the docs base of `codeDescription` links or turns off the ABI
  gate.
- **Settings the extension cannot restrict:** `editor.codeActionsOnSave` and `editor.formatOnSave` are the editor's.
  So the server offers no code action of a `source.*` kind, and nothing it would apply on save may change meaning:
  quick fixes are `quickfix` only (D7); formatting exists only after M1e and is meaning- and hash-preserving (L7, L12).
- **Markdown:** hovers and diagnostics contain workspace text (names, `require` messages, library names). No
  `MarkdownString` is ever trusted (`isTrusted`), workspace text is escaped, and commands appear only in code lenses
  and the command palette, never as links.
- **Project discovery and launch:** D3b (confinement to trusted folders by real path; no CLI dispatch; absolute paths;
  the bundled server resolves no module from the workspace).
- **Library sources and inputs:** D4b.
- **No network, no telemetry.** The extension and the server open no connection (Q3(b) is the only open exception).
- **No writes by the server.** Everything the server changes is a versioned `WorkspaceEdit` the editor applies
  (rename, quick fixes, formatting; D4a); everything else is a user-invoked command through the project's CLI (L3).

### D11. Publishing is human-only, and the supply chain (revised in r2)

- **Build:** CI builds the `.vsix` (`vsce package`, plus `--pre-release` for pre-releases) on every pull request that
  touches the extension or the server, on `pull_request` (never `pull_request_target`), and smoke-tests it (D12). The
  packaging job references **no secret**, runs `npm ci --ignore-scripts`, uses SHA-pinned actions and a read-only
  token (ADR-0017 I10 and I15, applied to the `.vsix`; ADR-0017 F9 records that organisation secrets are readable by
  any job that references them).
- **Engine provenance:** the bundled engine is the one in the same release's `@julclang/joust` tarball; CI checks that
  the `.vsix`'s engine files have the same digests as the tarball's, and the release notes record them.
- **Release:** the release workflow attaches the `.vsix` to the GitHub release with its SHA-256 and a build-provenance
  attestation, as ADR-0017 D6 does for the CLI archives, never replacing an asset.
- **Publishing to the VS Code Marketplace and to Open VSX is done by a maintainer, by hand**, from that attached
  `.vsix` (`vsce publish --packagePath`, `ovsx publish`), after **`gh attestation verify <vsix> -R bloxbean/joust`** (a
  hash on the same release page alone would be circular). No Marketplace or Open VSX token is stored in the repository
  or in CI. Automating it later needs its own decision, like the Homebrew tap's in ADR-0017 D7.
- **Token custody:** a Marketplace PAT scoped to Marketplace (Manage), one organisation, a short expiry, kept outside
  CI, held by at least two named maintainers; an Open VSX token likewise. **Incident procedure:** unpublish or
  deprecate the version on both registries, revoke and rotate the tokens, publish a fixed version, and say so in the
  release notes (Marketplace auto-update reaches every user at once).
- **Namespaces (maintainer actions, before any template recommends an extension ID):** create the Marketplace
  publisher and **claim the Open VSX namespaces `julclang` and `bloxbean` now** (both are unclaimed, F18), apply for
  verification on both, and decide Q4.

### D12. Testing

- **Core (JVM):** golden document models (JSON) over a corpus: the examples' `.jsc` files, the checker tests'
  sources and the fixtures, which is also M1d's two-parser corpus. **Hand-written expectations** in annotated test
  files (markers such as `/*@def x*/`, `/*@ref x*/`, `/*@hover Int*/`, `/*@complete …*/`, `/*@range …*/`) for
  definition, references, hover, completion and name spans (with comments between keywords and names), written
  independently of the implementation.
- **Parity:** the core's models from the JVM and from the Web Image are byte-identical over the corpus (as
  `toolchainWasmTest` checks the build today).
- **Modes (L1, L2):** a project-matched server against `joust build` of the same project; a bundled server of another
  version labelled advisory; a dependency-only change (a library edited, the importer unchanged) during a build,
  whose result is dropped; a project package outside the trusted folder; a pre-M5a package.
- **M5a errors and warnings:** a file with a warning before and after its fatal error returns the error only; the same
  file fixed returns the warning.
- **Recovery (L5, L13):** for every corpus file the build accepts, the recovering mode and the span-carrying tree give
  identical Java and ABI; for every corpus file and every single-token deletion or insertion (generated mutations),
  the server returns a model, the build's error, when there is one, is among its diagnostics at the same code and
  position, and no error is reported in a declaration that neither contains the mutation nor references it.
- **Protocol (Node):** start the server and drive it with a JSON-RPC client: initialize, open, edit (version ordering,
  out-of-order and stale versions), diagnostics with versions, hover, definition, references, completion, rename,
  code actions, cancellation, shutdown; golden transcripts; oversized, binary and deeply nested files (M1e's code on
  every path). **Watchdog (L9):** with a test-only engine module that never returns (a test build, never shipped),
  the server still answers `hover` and `shutdown`, terminates the worker at the cap, reports one internal error,
  quarantines the input, backs off, and reaches "analysis unavailable" after repeated failures; the same with a worker
  over its memory limit; cancellation during a long call.
- **Edits (L6, L12):** rename refusals for each D3/J10 rule; resolution preservation over generated renames of every
  binding in the corpus; each quick fix's output re-checked; ABI-visible renames warn; an edit made between computing
  and applying a rename or quick fix is refused by version; **for every server-proposed edit to a corpus file that
  builds before and after, the script hash is unchanged** (layout and rename mutations, imports, fixes), and, before
  M1e, no line-moving edit or hash-changing rename is offered.
- **Accept ABI (D7a):** the file edited between the diff and the acceptance is refused; acceptance of contract A leaves
  B's changed manifest unaccepted; not offered in advisory or restricted mode.
- **Formatter (L7, M5d):** idempotence, comment preservation, identical script hash, manifest, description and Java
  modulo position comments, over the corpus and generated layouts.
- **Extension smoke tests** with `@vscode/test-electron` on the minimum and the current VS Code: install the `.vsix`,
  open the `npm create joust` template, wait for diagnostics on a seeded error, hover, run "Show generated Java".
- **Hostile workspace** (the packaged launch path, restricted and trusted): planted `node_modules/@julclang/joust`
  (whose code would create a sentinel file), `@julclang/joust-runtime` and `vscode-languageserver`; a planted package
  in an ancestor of a trusted subfolder (S3); a pre-M5a project package; workspace settings naming an executable and
  setting `editor.codeActionsOnSave`; a `joust.config.json` pointing `contracts` at `/` or `..`; a `contracts/x -> /`
  symlink, a `.jsc` symlink to `/dev/zero` and a FIFO; a `joust.lock` with planted hashes. Expected: the sentinel never
  appears, the running compiler is the expected one (asserted through `serverInfo`), settings are ignored, nothing is
  applied on save, reads stay confined and bounded, and no build command is offered in restricted mode.
- **Docs:** a test refuses `npx joust` in the editor configurations (D3b).
- **Other editors:** the protocol tests cover the server; the Neovim and Helix configurations are checked by hand per
  release from a checklist.

### §3a. What this ADR needs from ADR-0018 (new in r2)

These are amendments to ADR-0018 or its milestones. They land in ADR-0018's own revision history; this list is the
request.

1. **§8 (M5's gate).** Q1 is adopted as (b) with conditions (§10): M5a after M1 exits; M5b may start then too; M5c
   after M4. From M5b on, L5's corpus test is required in every M2 and M3 front-end PR, and recovery and spans are in
   the scope of ADR-0018's independent security review.
2. **D12 and M1e (on-chain messages).** Messages independent of layout, comments, file names and all names, with
   name-free keys unique within a script, a source map from key to `File.jsc:line`, and a build-report map from message
   to construct (so a trace from a deployed script is attributable without rebuilding it). Changing the scheme
   changes every `.jsc` 2 script hash once; whether a preview deployment needs the old scheme to stay rebuildable
   (ADR-0018 D16) is ADR-0018's to state.
3. **M1e (nesting limit).** A catalogued limit on expression and block depth and chain length, identical on the JVM
   and in Wasm, well below the Web Image's depth, with operator chains handled iteratively (F13). M5a's entry gate.
4. **D12 (ABI acceptance).** Per-contract acceptance bound to a manifest hash (`--accept-abi=<Contract>@<sha256>`; the
   Gradle task's options), and a diff command (D7a).
5. **D13.** Tree-sitter serves editors, not the language server's syntax layer (D5).

## 4. Invariants

Numbered L… so they are not confused with the validator invariants I1–I55, ADR-0018's J1–J19 or ADR-0020's U….

| # | Invariant | Constrains | Checked by |
|---|---|---|---|
| L1 | **In project-matched mode** (D3a), for a completed analysis of a snapshot equal to what the build reads (same compiler, sources, dependencies and configuration), the server's front-end errors include every front-end error the build reports (same code, line, column), and a file with no front-end error from the server passes the build's front end. In advisory mode no equivalence is claimed and every diagnostic is labelled advisory | D1, D2, D3a, D5 | mode tests; corpus and mutation tests against `JscV2.lower` / `joust build` |
| L2 | The server reports its mode and the compiler it analyses with (`serverInfo`, status bar, diagnostic `source`). It is project-matched only when D3's eligibility holds (trusted, eligible package, confined real path); every other case, including a package that predates the LSP entry, is the bundled server in advisory mode | D3, D3a, D3b | protocol and smoke tests with two versions |
| L3 | The server writes no file. Changes reach the workspace only as edits the editor applies or as commands the user invokes, through the project's CLI or `gradlew` | D4, D7, D10 | protocol tests watch the workspace; smoke tests |
| L4 | In an untrusted workspace no workspace file is executed or loaded as code, and in a trusted one no code is loaded from outside the trusted folders (no `node_modules` code, no Gradle, no executable from a workspace setting, no module resolved from the workspace by the bundled server) | D3b, D10 | hostile-workspace smoke tests with sentinels |
| L5 | Recovery and spans never change the build: on every file the build accepts, the recovering mode and the span-carrying tree give byte-identical generated Java and ABI manifest | D5, D6 | corpus tests, in M5 and in every M2/M3 front-end PR (§3a) |
| L6 | Every edit the server proposes is re-analysed on the snapshot it is bound to: a quick fix removes its diagnostic and adds none; a rename adds no diagnostic, keeps every other reference resolving to the same declaration, breaks no D3/J10 rule, and warns when it changes an ABI-recorded name; the edit carries the document versions it was validated on | D4a, D7 | rename, fix and version tests |
| L7 | Formatting is idempotent, keeps every comment, and leaves the script hash, ABI manifest and description unchanged (generated Java differs only in position comments) | D8 | formatter corpus tests (M5d) |
| L8 | `publishDiagnostics` carries the document version, and the server never publishes for a version older than one already published; results computed for an older snapshot generation are dropped, and build diagnostics are withdrawn when a newer generation exists | D4, D4a | protocol tests with interleaved edits, saves and dependency changes |
| L9 | No input (hostile, huge, deeply nested, binary, a FIFO) hangs or crashes the server: every engine call runs in a worker under a time cap and a memory limit; a failure yields one internal-error diagnostic, a new worker, a quarantined input and backoff; repeated failures lead to "analysis unavailable", while the protocol thread keeps answering | D2a, D4, D4b | watchdog protocol tests; hostile-workspace tests |
| L10 | On the reference corpus: analysis p95 latency, **including the model's export** across the engine boundary, at most 50 ms for the 1,889-line file; server process RSS at most 600 MB with one analysis worker and no build worker, and at most 1.1 GB with a build worker. CI enforces looser regression bounds on its own hardware | D2a, D4 | M5a measurement job (the export is measured at M5a's exit) |
| L11 | Positions are UTF-16 code units, correct with non-BMP characters before the position on the same line | D6 | protocol tests |
| L12 | **No server-proposed edit changes a script hash except as a code change:** for every rename, quick fix, completion edit or formatting of a file that builds before and after, the script hash is unchanged; before M1e, edits that would change it (line-moving edits, renames of names that reach messages) are not offered | D7, D8 | hash tests over the corpus with layout and rename mutations (D12) |
| L13 | Recovery is precise: for a single-token mutation inside one declaration, no error is reported in a declaration that neither contains the mutation nor references it | D5 | mutation corpus (M5b) |

## 5. Alternatives considered

Beyond D2's hosts (a)–(e):

- **No language server; grammars only** (Elci's state, F14). Rejected: `.jsc` 2's rules (immutability, shadowing,
  time, audiences) are exactly what a newcomer trips over, and they are only visible to the checker. The stable codes
  exist; showing them as you type is the cheapest large gain.
- **Diagnostics from `joust dev`'s rebuild on save only** (it already watches `contracts/`). Rejected as the editor
  path: a full build per save is 0.5–1.6 s per contract (§1), errors come after saving, and positions arrive as text.
  It remains the CLI's workflow.
- **An incremental checker** (per-declaration invalidation, salsa-style). Rejected for now: whole-file re-analysis is
  milliseconds at 20 times the largest example (§1).
- **Analysis on the protocol thread** (r1). Rejected in r2: it cannot be interrupted, the engine cannot be re-loaded in
  that thread, and builds stalled it by up to 250 ms (§1, F7, F8).
- **A child process per engine instead of a worker.** Also killable, and its memory is the OS's to bound; but it costs
  a process start (about 0.5 s with the engine, §1) per restart and IPC serialisation. Workers suffice (§1: terminable
  in 9 ms, memory-bounded); kept as the fallback if a worker's limits prove insufficient.
- **Tree-sitter as the server's recovering parser** (D5). Rejected for M5; Q6.
- **A front-end-only Web Image for the server** (measured, §1: half the size, half the start-up, about 130 MB less).
  Rejected: `java:` imports need javac, and a second image is a second artifact to build, ship and keep identical.
  Kept as the fallback if the full engine's memory proves a problem.
- **`npx joust lsp` for other editors** (r1). Rejected in r2 (F18, D3b).
- **Publishing from CI with stored tokens.** Rejected for now (D11).

## 6. Consequences

**Positive:** the build's own front end in the editor, so a project-matched editor and the build cannot disagree about
the language; no JDK for anyone, and one universal `.vsix` without native code; versioning by construction in npm
projects; the stable codes and ABI gate surface where the author works; a server that survives hostile input; the
formatter question exposed F12, which M1e now removes for every edit, not only formatting.

**Negative:** memory (about 390 MB with one analysis worker, about 365 MB more while a build worker lives); a protocol
layer in JavaScript and a core in Java, so features cross a boundary and need parity tests; the compiler front end
must grow recovery, spans, trivia and a model export, all inside the trusted toolchain (L5 guards the build against
them, and they join ADR-0018's security review); Gradle projects get an advisory bundled compiler that may differ
from theirs and does not resolve their `java:` libraries (Q3, D4b); the extension adds a release artifact and a
manual publishing step with token custody; some M5b edits wait for M1e.

## 7. Compatibility

ADR-0021 itself changes no contract, script, datum, redeemer, description or ABI manifest. Its prerequisite M1e
(ADR-0018) changes every `.jsc` 2 script hash once, by its own decision (§3a). `joust build`'s diagnostic lines change
shape (D9), which scripts parsing them would notice; the npm CLI is pre-1.0. `--accept-abi` gains a per-contract,
hash-bound form (D7a, §3a). The `JscV2` API and its positions are unchanged; new entries and span data are added beside
it. The Web Image gains entries and reachability metadata for the model's records.

## 8. Milestones

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M5a | The server with diagnostics as you type (the first fatal error, or the warnings; codes and links), document symbols, selection ranges and folding (D6's M5a spans); engine hosting (D2a); modes (D3a); launch paths (D3b); snapshots and versioned diagnostics (D4a); input policy (D4b); `joust.lang.analyze`; the VS Code extension (M1d's TextMate grammar, language configuration, snippets, status bar, trust model, **JOUST: Build** task and the `$joust` problem matcher); `joust build` prints the catalogue's diagnostic shape (D9); the `.vsix` built and smoke-tested in CI | ADR-0018 accepted and M1 exited (M1d's grammars and catalogue in place; Q1(b)); **M1e merged** (nesting limit); this ADR accepted | L1 (first error), L2, L3, L4, L8, L9, L10, L11 tested; the model's export measured and within L10; the `.vsix` has no `.node` file and is under 16 MB; smoke tests green on the minimum and current VS Code; hostile-workspace tests green |
| M5b | Recovery (lexer, parser, checker) and the remaining spans and reference mapping; hover, definition and references within a file, semantic tokens, completion, rename within a file, quick fixes, generated-Java view, ABI diff and **Accept ABI…** (D7a, with ADR-0018's D12 acceptance change), build diagnostics on save (worker) | M5a exit (Q1(b): may run alongside ADR-0018 M2/M3, with L5's corpus test in their front-end PRs). Import-adding edits and fixes, and renames that reach messages, only once M1e's message scheme is merged | L1 (recovered), L5, L6, L12, L13 tested; the recovery mutation corpus green; latency still within L10 |
| M5c | Across files: `.jsc` libraries, `import contract`, `java:` imports (read-only library documents), project-wide references and rename, library signature cache | M5b exit; ADR-0018 M4 exited; javac attribution cost in the engine measured | definition and references across a `.jsc` library and into a Java library in the template's tests; L6 and L12 across files |
| M5d | Formatter: trivia-preserving lexing, `joust fmt`, `textDocument/formatting` | M5b exit; **M1e's message scheme merged** (D8) | L7 and L12 over the corpus and generated layouts |
| M5e | Other editors: Neovim and Helix configurations (a user-installed `joust-lsp`), the tree-sitter grammar packaged with its queries, docs; the `.vsix` attached to a release for maintainers to publish (D11) | M5a exit (configurations), M5c exit (docs complete); the namespaces claimed and Q4 decided (D11) | the release checklist run by hand on each editor; the docs test refusing `npx joust` green |

Recommendation: ADR-0018's recommendation gate (`.jsc` 2 recommended once M2 and M4 exit and the security review
passes) does not depend on M5; the extension is labelled preview while `.jsc` 2 is.

## 9. Verification

D12 holds the test plan. In summary: independent expectations (hand-written markers, never the implementation's own
output) for navigation, hover, completion and ranges; JVM/Wasm parity of the document model; L1, L5 and L13 checked
against the build itself over the corpus and generated mutations; L12's hash comparisons; protocol tests in Node,
including a non-returning engine call; extension smoke tests including hostile workspaces, trusted and untrusted;
formatter invariants including the script hash. None of these certify the compiler: that remains ADR-0018 §9's job.

## 10. Open questions

1. *(Closed in r2.)* **When M5 starts.** Adopted: (b) with the design review's conditions; see §3a item 1 and §8.
   It amends ADR-0018 §8.
2. *(Closed in r2.)* **Formatter and script hashes (F12).** Decided in ADR-0018 through M1e: name-free, layout-free
   message keys (§3a item 2, D8). M5d is gated on it; import-adding edits and hash-changing renames wait for it (D7).
3. **The compiler for Gradle projects.** Options: (a) the bundled one, advisory, with a version notice (lean, M5a);
   (b) on consent, download the `@julclang/joust` matching the Gradle plugin's version, accepted only with npm
   provenance or signature verification, or a digest pinned in the extension (registry integrity alone is not
   independent of the registry); (c) a JVM host started through a Gradle task. Lean (a) now, (b) if users ask; (c) runs
   Gradle, i.e. workspace code, and needs a second host.
4. **Publisher identity.** `julclang` (the npm scope and Maven group) or `bloxbean` (the GitHub organisation), on both
   the Marketplace and Open VSX; which maintainers hold the publishing credentials. Lean `julclang`, matching the
   package names, and claim both namespaces on Open VSX either way (D11). **Needs a maintainer.**
5. **Minimum VS Code version.** Lean: the oldest version whose extension host loads the engine in the smoke test
   (1.109.0 verified locally; current stable 1.146.0; older Electron versions may need the exnref flag, which
   `loadImage` sets, or lack the feature).
6. **Tree-sitter in the server.** Lean no (D5); revisit with M5b's recovery results.
7. **Build on save by default.** On in trusted, project-matched npm projects (it writes nothing); **off in restricted
   and advisory mode**, with the explicit **Check build (in memory)** command (D4, D10). A user setting turns it off.
8. **A JVM host later.** The core is plain Java; an LSP4J host is small. Lean: not in M5; only if Gradle users of
   other editors ask.
9. **The document model and M1b's IR export.** The M1a report asks M1b to choose between a versioned IR export and a
   JavaScript front end. Lean: keep the document model separate (it is editor-shaped and may change with the
   extension), whatever M1b decides.

## 11. Related findings (out of scope)

- The two CLIs print diagnostics in different shapes (F9). D9 fixes the npm shape in M5a, because the problem matcher
  needs one; it is also a plain inconsistency with the catalogue's description.
- `Toolchain.java:203-206` and `JscMain.java:210-216` catch only `JscException` and `RuntimeException`, so an `Error`
  escapes the build as an internal failure without a code (F10, F13); M1e's limit removes the known cause, and both
  boundaries could still turn any `Throwable` into a coded internal error (as D2 does for `joust.lang.*`). For #63/M1e.
- On Gradle, processor and JuLC messages on generated Java are not remapped to `.jsc` positions yet (F10, an M1d item
  of ADR-0018); the editor's build path is the npm toolchain's, which remaps.
- The toolchain reports an internal lowering failure at line −1 (F10); the server maps it to 1:1, but the toolchain
  could give the position of the construct being lowered.
- `loadConfig` and `build` in `js/joust/src/` read `joust.config.json` and walk `contracts` without the file rules of
  D4b (no regular-file check, no size cap, symlinks followed, `contracts` not confined); the server applies D4b itself,
  and the CLI could adopt the same rules.

## Revision history

- **r1** (2026-11-13): initial proposal, with the measurements of §1 (toolchain and front-end-only Web Images, JVM
  front end, VS Code 1.109.0's runtime) and F12 (line numbers reach the script hash), found while designing the
  formatter.
- **r2** (2026-11-13; responds to the reviews of `c720ae1`: Codex C1–C7, design F1–F12, security S1–S12):
  - Engine hosting (C1, F1, S1): new D2a: every engine in a worker, a watchdog, memory limits, quarantine by input
    hash, backoff, restart limits and an "analysis unavailable" state; L9 restated; watchdog tests; §1 measures the
    worker round trip, termination and memory limits; F7 and F8 record the failure shape and the in-thread reload hang.
  - Nesting (S2, F8): F13; M1e's catalogued limit is M5a's entry gate; engine boundaries catch any `Throwable`.
  - Script hash (F4, S5, S6): F12 widened (file name, `Key` parameter names, import lines); Q2 closed: decided in
    ADR-0018 through M1e's name-free, layout-free message keys; D8's hash-stability contract; new L12; import-adding
    edits and hash-changing renames held back until M1e; D7's rename warning.
  - Launch (C2, S3, S4, S12): new D3b: a non-delegating bundled entry, a project LSP entry eligible only inside a
    trusted folder by real path, a user-installed `joust-lsp` by absolute path for other editors, never `npx joust`
    (F18); absolute spawns; module resolution anchored in the extension.
  - Modes (C3, F3): new D3a (project-matched, advisory); L1 and L2 restated; imports before M5c; new L13 (recovery
    precision).
  - Spans (C4): D6 rewritten: today's positions and lowering unchanged; separate name spans, node extents and a
    reference mapping; M5a gets declaration spans.
  - Warnings (C5): M5a returns the first fatal error or the warnings; lexer recovery is an M5b deliverable (F11).
  - Snapshots and edits (C6, F12): new D4a: snapshot generations including dependencies and configuration, stale
    results dropped, versioned diagnostics (L8 restated) and versioned edits.
  - Accept ABI (F2, S8): new D7a: diff from the accepting tool, per-contract acceptance bound to the manifest's
    SHA-256, never in advisory or restricted mode, never a code action or link; an ADR-0018 D12 amendment.
  - Packaging (F5): only the server entry and the engine are bundled, measured at 11.5 MB zipped, no `.node`; a CI
    check and a size bound; `extensionKind`, web later, an `experimental.joust` protocol version (F12).
  - Q1 (F6): adopted (b) with conditions; §3a lists the ADR-0018 amendments (§8, D12, D13, M1e); "Depends on" header.
  - Budgets (F7): multiplicity rule; absolute L10 bounds including the model's export.
  - Restricted mode (F9, S12): no on-save builds there; an explicit command; Q7 consistent.
  - Code actions and markdown (S7): `quickfix` only, never `source.*`; no trusted markdown; commands only in lenses
    and the palette.
  - Inputs (S9, S10): new D4b (real paths, regular files, caps, confined config, Gradle libraries unresolved,
    opaque-id content providers); the signature cache keyed by bytes read, per workspace.
  - Supply chain (S11): D11 adds the job's hygiene, engine provenance, `gh attestation verify`, token custody, an
    incident procedure, and claiming the Open VSX namespaces as a maintainer action.
  - Citations (C7, F10): re-pinned to ADR-0018 r9 (`3de519d`, code `dfa7025`): 243 codes, 14 lexer throws,
    `JoustToolchainWasm.java:57-58`, JSC0148 present; VS Code current stable named; D13's supersession recorded (§3a).
