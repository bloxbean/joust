# ADR-0021: A language server and VS Code extension for `.jsc` 2

**Status:** Proposed (r1)
**Date:** 2026-11-13
**Builds on:** ADR-0018 (`.jsc` 2; r8 as amended on `feat/jsc-2-m1a` at `03c594b`): D3 (names), D9 (libraries and `java:`
imports, built in M4), D12 (stable codes, source maps, generated Java, ABI manifest), D13 (tree-sitter and TextMate
grammars; "a language server is later work (M5)"), D14 (npm projects), §8 (M5 is named there but not designed).
ADR-0015 (the toolchain engine: JOUST's processor and JuLC in a GraalVM Web Image, run in Node). ADR-0017 D4 (the
native `joust` defers to the project's own `@julclang/joust`) and its threat model.
**Design only:** no implementation in this PR. Every snippet is illustrative.
**Risk:** medium. Nothing here changes a validator, a wire format or what the build accepts: the build stays the
authority (D1). But the extension runs code inside the developer's editor on every repository they open, including
hostile clones, so trust and the choice of which code runs are security decisions (D10). And two editor operations
can change what the build produces without the author meaning to: a rename can change the ABI manifest (D7), and a
formatter that moves lines changes the script hash today (F12, D8).

## 1. Context

ADR-0018 makes `.jsc` 2 the language JavaScript developers start with, and promises editor support in two steps:
grammars now (D13, being built in M1d) and a language server later (M5). Since the front end already has stable
diagnostic codes, positions on both build paths, source maps and an ABI manifest (M1a), the question is no longer
whether a language server is possible but where it runs, which compiler it uses, and how much of the front end it
needs to change. Facts this design relies on (JOUST `main` at `0e4ad41`; the `.jsc` 2 front end on `feat/jsc-2-m1a` at
`03c594b`, r3 of M1a's review; JuLC `v0.1.0-pre21-rc1`):

| # | Fact | Source |
|---|---|---|
| F1 | The `.jsc` 2 front end is `Lexer` → `Parser` (a `Syntax` tree) → `Checker` (the typed `Ir`) → `JavaEmitter`, with `Abi` for the manifest and source map. `JscV2.lower(source, fileName)` runs all of it on one file and returns the Java, the warnings, the source map and the ABI manifest | `julc-state-dsl/src/main/java/org/julclang/state/dsl/v2/JscV2.java:73-79` |
| F2 | **Both the parser and the checker stop at the first error**: each rule throws a `JscException` (line, column, code, message); the parser has 55 `throw` sites, the checker 195, the lexer 13. Only warnings are collected | `Parser.java:88`, `:94` (`expect`, `ident`); `Checker.java:99`, `:596` (warnings); `org/julclang/state/dsl/JscException.java` |
| F3 | A position is a start only: `Syntax.Pos(int line, int col)`, 1-based. Columns count Java `char`s, i.e. UTF-16 code units, which is LSP's default position encoding; names are ASCII by D3, so a name's length is its UTF-16 length | `Syntax.java:15`; `Lexer.java:100-117` (columns, comments) |
| F4 | **The lexer drops comments**; the `Syntax` tree holds no trivia, so a formatter cannot be written over it | `Lexer.java:106-117` |
| F5 | The checked IR resolves every name: a `LocalRef` points at its `Local`, which carries its declaration position; `CallFn` names a `fn`; every expression carries its type, time taint (D11) and position. The IR classes are package-private | `Ir.java:11-17`, `:99-107`, `:169`, `:193` |
| F6 | Diagnostics have **stable codes**: the catalogue lists 242 (237 errors, 5 warnings) in 12 sections, checked against the sources by `JscV2DiagnosticsTest` | `docs/reference/jsc-2-diagnostics.md` |
| F7 | The npm toolchain engine exposes `joust.build` (a whole file set: processor, JuLC, descriptions, generated Java, ABI) and `joust.run` (transition programs); there is no check-only entry. Calls are synchronous and run to completion on the calling JavaScript thread | `julc-state-toolchain/src/wasm/java/.../JoustToolchainWasm.java:72-73` |
| F8 | The engine needs Wasm exception references: `loadImage` sets `--experimental-wasm-exnref` (needed on Node 22, on by default from Node 24). `@julclang/joust` requires Node ≥ 22 and ships the engine in `engine/`; the published `0.1.0-pre1-rc9` is 30,370,292 bytes unpacked | `js/joust-runtime/src/load.js:9-11`; `js/joust/package.json`; `npm view @julclang/joust` (2026-11-13) |
| F9 | The two build paths **print diagnostics in different shapes**: Gradle's `JscMain` prints `org/acme/File.jsc:12:5: error[JSC0502]: …` (as the catalogue says for both paths), `joust build` prints `error[JSC0502]: app/File.jsc:12:5: …`; each names the file relative to its source directory (`src/main/jsc/`, `contracts/`) | `JscException.format`; `JscMain.java:181-185`, `:211`; `js/joust/src/build.js:49` |
| F10 | On Gradle, javac's and the processor's messages on generated Java are not yet remapped to `.jsc` positions (the npm path remaps them); the toolchain reports an internal lowering failure at line −1 | M1a report, "Not in M1a"; `Toolchain.java:181`, `:205-206` |
| F11 | The npm CLI finds its project and the project's own `@julclang/joust` by ADR-0017 D4's rules (marker files, an ownership stop rule, manifests read only as regular files of at most 64 KiB) | `js/joust/src/project.js:1-30`; ADR-0017 D4, I1, I5 |
| F12 | **The script hash depends on line numbers.** Generated `Require` messages embed `File.jsc:<line>` (`JavaEmitter.where`), e.g. for `if`, `for`, `match` helpers and payout checks, and `Require` messages stay on-chain (ADR-0008). Measured on `Ledger.jsc` with the toolchain engine: one blank line inserted after `package` changes the hash (`927e1a0e…` → `96d0a656…`); re-indenting every line, or adding a comment at the end, does not. The file name is embedded too | `JavaEmitter.java:161-163`, `:215`, `:497`, `:543`, `:594`, `:1027`; §1, measurements |
| F13 | Elci (same organisation) ships a tree-sitter grammar with highlight, locals, fold and indent queries, a corpus and CI, not published to npm; it has **no language server or VS Code extension**. Its ADR keeps "editor services separate from the compiler core: the CLI and later language server call the same parser, resolver, and type checker" | `github.com/bloxbean/elci-lang` at `a3ec190`: `README.md:62-63`, `tree-sitter-elci/README.md`, `adr/elci-language-syntax-compiler-design-adr.md` §8 |
| F14 | No editor files exist in the repository or the templates; the docs highlight `.jsc` with Kotlin's grammar. M1d (in progress) adds the tree-sitter and TextMate grammars; their paths are not fixed yet | `www/astro.config.mjs:26-27`; `js/create-joust/template/`; ADR-0018 D13, M1a report |
| F15 | The VS Code Marketplace accepts only `major.minor.patch` extension versions ("semver pre-release tags are not supported"); pre-releases use `vsce publish --pre-release`. Extensions can be platform-specific (`--target`) or universal. Workspace Trust offers `capabilities.untrustedWorkspaces: { supported: "limited", restrictedConfigurations }`; settings of scope `machine` or `application` cannot be set by a workspace | code.visualstudio.com: "Publishing extensions", "Workspace Trust", "Contribution points" (fetched 2026-11-13) |
| F16 | Current versions: LSP 3.18 is the latest specification; `vscode-languageserver` and `vscode-languageclient` 11.0.0 (protocol 3.18.5; the client needs VS Code ≥ 1.91.0); `@vscode/test-electron` 3.1.0; `@vscode/vsce` 4.1.0; `ovsx` 1.4.0; `web-tree-sitter` 0.27.2; LSP4J 1.0.0 | microsoft.github.io/language-server-protocol; `npm view` and Maven Central metadata (2026-11-13) |

### Measurements

Measured for this ADR on 2026-11-13: Apple M4 Max (16 cores, 128 GiB), macOS 26.0.1, Node 22.12.0, JDK
25.0.2, GraalVM 25.3.4.1 Web Image. The machine was shared (load average about 14), so the numbers are indicative.
The toolchain image was built from `feat/jsc-2-m1a` at `03c594b` with one measurement-only entry added,
`joust.lower` (the `JscV2.lower` call of F1, timed inside the engine; not committed). Inputs: the M1a examples
(`examples/dsl/src/main/jsc/.../`), and two synthetic files made from `Ledger.jsc` with 70 and 300 extra block-bodied
`fn`s (509 and 1,889 lines). Each figure is the range over three runs; "warm" is the median of the 29 calls after the
first in the engines, and of 40 calls after 10 warm-up calls on the JVM. Scripts: a Node driver for the engines and a Java `main` in the `v2` package for the JVM (scratch, not
committed).

| | Toolchain image (as shipped, + `joust.lower`) | Front-end-only image (scratch) | JVM, JDK 25 (timings: default heap; memory: `-Xmx64m`, Serial GC) |
|---|---|---|---|
| Wasm size, raw / gzip -9 | 31,153,511 / 11,157,442 B | 14,038,597 / 5,030,381 B | — |
| Engine load in Node | 385–426 ms | 187–203 ms | — |
| Process start to the first lowering of `Ledger.jsc` (wall) | 0.53–0.54 s; **also 0.53–0.54 s in VS Code 1.109.0's extension-host runtime** (Electron 39.3.0, Node 22.21.1), which loads the image without the exnref flag (Node 22.12.0 refuses it without the flag) | 0.29–0.31 s | 0.08–0.09 s (the front end only; no LSP library) |
| Resident memory after load | 357–361 MB | 227–228 MB | 228 MB peak for the whole run |
| First lowering in a fresh engine (`Counter2`) | 76–79 ms | 41–42 ms | 39–50 ms |
| Warm lowering: `Counter2` 26 lines / `Ledger` 89 / `Shelf` 69 / `Pledges` 71 | 0.8 / 2.7 / 1.8 / 2.5 ms | 0.8 / 2.6–2.8 / 1.8 / 2.5 ms | 0.43–0.45 / 1.03–1.16 / 0.67–0.74 / 0.65–0.70 ms |
| Warm lowering: 509 / 1,889 lines | 6.9–7.1 / 21.6 ms | 7.0–7.2 / 21.2–22.0 ms | 1.87–1.98 / 4.50–4.85 ms |
| Warm parse + check only (no emitter, no ABI) | not exposed (estimate: about 35–45% of lowering, from the JVM ratio) | — | `Ledger` 0.37–0.42 ms; 1,889 lines 1.47–1.67 ms |
| A file whose first error is a type error (line 31) / a syntax error (line 61) | 0.3 / 0.2–0.3 ms | 0.3 ms | 0.07 / 0.05 ms |
| `joust.build` of one file, first / warm | `Counter2` 764–776 / 450–476 ms; `Ledger` 1,621–1,631 / 1,545–1,602 ms | — | — |
| Resident memory after six builds | 600–603 MB | — | — |
| Two engines, one per thread (checks on the main thread, builds in a `worker_thread`) | 504–505 MB with both loaded, 725 MB after three builds; a `Ledger` lowering every 20 ms on the main thread during the builds: median 2.7 ms, max 193–250 ms (n = 259) | — | — |

What follows from them: re-checking a whole file on every edit is cheap (milliseconds even at 1,889 lines, about 20
times the size of the largest example), so the server needs **no incremental checker** (D4). Start-up is about half a
second. Memory, not speed, is the cost of the Wasm engine: about 360 MB for checking, and another 365 MB while a
build worker is alive. The JVM front end is about 2–5 times faster warm, which does not matter at these sizes, and
needs a JDK, which npm users do not have.

## 2. Threat model and trust assumptions

- **Untrusted:** the contents of every folder the editor opens: `.jsc` and `.java` sources, `joust.config.json`,
  `package.json`, `node_modules` (including a planted `@julclang/joust`), Gradle files and wrappers, `.vscode/`
  workspace settings and tasks, and library sources a `java:` import names (D9 of ADR-0018). A repository may be a
  hostile clone. Untrusted files are **inputs** to the analysis (parsed, checked, compiled), never code to run, until
  the user trusts the workspace.
- **Trusted:** the extension as installed from the marketplace (its own code and bundled engine), the editor itself,
  the user's own settings (user scope, not workspace scope), and, **once the user trusts the workspace**, the
  project's own `@julclang/joust` (the code `npm run build` would run anyway: ADR-0017's argument) and the commands the
  user explicitly invokes.
- **What the language tooling must not do:** run workspace code in an untrusted workspace (no `node_modules` code,
  no Gradle, no executable named by a workspace setting); write to the workspace on its own (only edits the user
  applies and commands the user invokes, D10); open the network (no telemetry, no downloads; Q3 is the one open
  exception, and only on explicit consent); let a hostile file hang or crash the server (size and time caps, D4).
- **The build remains the authority.** An editor diagnostic is advice. A false "no error" in the editor never
  reaches the chain: the build runs the same front end and, unlike the editor, the processor, JuLC and the ABI gate.
  But a false error, or a quick fix that changes meaning, misleads the least experienced users (ADR-0018 §2), so the
  editor runs the build's own front end (D2) and checks every edit it proposes (L6).

## 3. Decision

### D1. One front end: the editor runs the compiler's checker, and the build stays the authority

The language server answers from the **same lexer, parser and checker** that `joust build` and Gradle's
`generateJsc` run, at the same version (D3). It does not have a second parser or type checker for semantics. Its
diagnostics as you type are the front end's (the `JSC` codes of F6); diagnostics from later stages (processor, JuLC,
ABI gate, script size) come from running the build (D4). A file the server shows as free of front-end errors passes
the build's front end, and every front-end error the build reports for a file is shown, at the same code and
position (L1).

### D2. Architecture: a Java language core, a JavaScript protocol layer, one Wasm engine

- **The language core** is Java, in `julc-state-dsl` beside the checker (same package, since the IR is
  package-private, F5). It turns one or more `.jsc` files into a **document model**: diagnostics, document symbols,
  declarations and references (each a name, a kind, a position and a declaration id), scopes with their ranges, the
  type and time taint of each expression with its range, folding ranges, and the source map. The model is plain
  records, versioned (`modelVersion`), with golden tests; it carries no `Syntax` or `Ir` objects. Edit operations
  (rename checks, quick fixes, formatting) are separate core entries that return text edits.
- **The engine:** the core is compiled into the **existing toolchain Web Image** as new entries next to
  `joust.build` (`joust.lang.analyze`, `joust.lang.rename`, `joust.lang.fixes`, later `joust.lang.format`;
  illustrative names). One image, one version, the build's own code. It must be the full toolchain image rather than
  a front-end-only one, because `java:` imports (ADR-0018 D9) are typed by javac attribution, which only the full image
  contains; the front-end-only image's measured saving (about 130 MB and 200 ms, §1) does not pay for a second
  artifact.
- **The protocol layer** is JavaScript: `vscode-languageserver` (pinned exactly, as the JS packages pin
  cardano-js-sdk) in `@julclang/joust`, started as **`joust lsp --stdio`**. It keeps open buffers, debounces, calls the
  engine, and answers most requests (hover, definition, references, symbols, semantic tokens, completion lists) by
  lookups in the document model, without calling the engine again. Builds run in a `worker_thread` with its own engine
  instance (D4).
- **Editors** talk LSP to `joust lsp`. The VS Code extension is a thin client that finds and starts the right server
  (D3); Neovim and Helix start it by command (D9).

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
  platform and a platform-specific `.vsix` per target (F15); ADR-0017's CLI ships no Windows build. The Wasm engine is
  platform-neutral, so one universal `.vsix` serves every platform. Rejected.
- **(e) A JavaScript checker** (or tree-sitter plus JavaScript analysis). Rejected: a second implementation of the
  typing, time and BigMap rules would disagree with the build exactly where the rules are subtle (D11's dataflow, J10's
  names). ADR-0018 M1b's JavaScript interpreter is deliberately independent as a test oracle; an editor needs the
  opposite, identity with the build.

### D3. Which compiler runs: the project's own, else the extension's, said aloud

- **npm project, trusted workspace:** the extension finds the project and its `@julclang/joust` by ADR-0017 D4's
  rules (F11; the same `project.js`), and starts that package's `joust lsp --stdio` with the editor's Node runtime
  (VS Code: the extension host's; other editors: `node` on `PATH`). The server's compiler is then the project's
  compiler, by construction: versioning is tied to the compiler without a version table. A project whose package
  predates `joust lsp` (any release before M5a's) gets the bundled server, with a notice naming both versions.
- **Otherwise** (untrusted workspace; npm project not installed; Gradle project; a loose `.jsc` file): the extension
  starts its **bundled** `@julclang/joust` (the extension pins one exact version). The status bar and the server's
  `serverInfo` show which compiler is running ("JOUST 0.1.0-pre1-rc9, bundled"), and each diagnostic's `source` says
  so. For a Gradle project the extension reads the `org.julclang.joust` plugin version from the build files **as
  text** (never by running Gradle) and says when it differs. It does not download a matching engine (Q3).
- ADR-0017 I1 says the CLI never falls back to an implementation of its own. The editor's fallback differs on purpose:
  it only reads and reports (no artifacts, no commands), and it is labelled as the bundled compiler wherever it
  speaks. Commands that act on a project (D7) always go through the project's own CLI or Gradle.
- The extension's own version is separate from JOUST's: the Marketplace accepts only `major.minor.patch` (F15), and
  the extension is a thin client. Compatibility is negotiated by LSP capabilities plus `serverInfo.version`; the
  extension declares the lowest server version it supports and says so when a project's is older.

### D4. Checking model: whole files on every edit, builds in a worker

- **As you type:** after a debounce (about 150 ms; tuned in M5a), the server re-analyses the edited file from scratch
  through `joust.lang.analyze`, with open buffers overlaid on the files on disk. No incremental parser or checker: at
  the measured cost (§1) it would add complexity without a user-visible gain. With libraries (M5c), it re-analyses the
  edited file's dependents too, and caches what is expensive and stable: library signatures from javac attribution,
  keyed by the hash of the library's on-chain sources (the hash `joust.lock` records, ADR-0018 D9). Attribution cost
  in the engine is not measured yet (an M5c entry gate).
- **On save (npm projects):** the server runs `joust.build` over the project, in memory, in a worker thread with its
  own engine, and adds the later stages' diagnostics: processor and JuLC errors remapped to `.jsc` positions (with the
  generated location as related information), the ABI comparison against the committed `<Contract>.abi.json`
  (JSC0002/JSC0003), script size. It **writes nothing**: no `build/joust/`, no generated Java on disk, no accepted
  manifest. The worker starts on the first save and stops after an idle period, returning its memory (§1: about 365 MB
  while alive). Build diagnostics belong to the saved version: they are withdrawn as soon as the document changes
  (L8). Gradle projects get front-end diagnostics only; their build output reaches the Problems panel through a task
  (D9).
- **Limits:** a per-file size cap (illustrative: 1 MiB), a per-call time cap, and a cap on files per workspace. A
  call that fails (an internal error, a trap, a time-out) yields one internal-error diagnostic on the file, restarts
  the engine, and never takes the server down (L9). The toolchain's internal error at line −1 (F10) is reported at
  1:1.

### D5. Error recovery, in stages; tree-sitter stays in the editor

The front end stops at the first error (F2). An editor needs more: several errors at once, and a tree to complete in
while the user is half way through a line.

- **M5a: none.** One error per file, exactly the build's, plus all warnings. Already useful, and needs no compiler
  change.
- **M5b: recovery in the hand-written parser and the checker.** The parser synchronises at declaration, contract
  member and statement boundaries, records the error and inserts an error node; the checker checks each declaration,
  method and `fn` separately, gives an erroneous expression an error type that suppresses follow-on errors, and
  continues. Recovery runs only in the language core's mode; `JscV2.lower` keeps stopping at the first error, and the
  recovering mode must agree with it (L5).
- **Completion in a broken buffer** uses the last model that analysed, plus the tokens around the cursor (the lexer
  recovers per line), to decide what is being completed (D7).
- **Tree-sitter is not in the server.** M1d's grammar serves editors' highlighting, folding, indentation and
  structural selection, and it recovers well; but it is a second parser, and the M1a report already asks that the two
  agree on a shared corpus. Using its tree for semantics would need a third component (CST to `Syntax`) and would make
  the editor disagree with the build on exactly the malformed inputs. Rejected for M5; reconsider only if the parser's
  recovery proves inadequate in M5b (Q6).

### D6. Positions and ranges

- Positions cross the protocol as LSP's default UTF-16 code units: the lexer already counts Java `char`s (F3); the
  server subtracts one from line and column. Tested with non-BMP characters in strings and comments before a
  diagnostic on the same line (L11).
- A name's range is its start plus its length (names are ASCII, D3). Other ranges need an end: M5b gives `Syntax.Pos`
  an end (line and column) recorded by the parser, carried into the IR. The emitter keeps using starts only, so the
  generated Java and the ABI manifest are **byte-identical** before and after (L5's corpus test).

### D7. Features

| Feature | How | Milestone |
|---|---|---|
| Diagnostics as you type | the front end's, with `code` = `JSC0502` and `codeDescription.href` to the catalogue's row on the docs site (a fixed `https` base, never configurable by a workspace); warnings with their codes; the lexer's character rules (JSC0145–JSC0148) underline the offending character | M5a (first error), M5b (recovered) |
| Build diagnostics on save | D4: processor and JuLC errors at `.jsc` positions with the generated location as related information; ABI and size findings | M5b |
| Document symbols, outline, folding | from the parse: contract, state fields, `init`/actions/destroys/views, `fn`s, `type`s, `enum`s and variants | M5a |
| Hover | the type of a name or expression; "time value (D11)" when tainted, and why; a `std` row's `.jsc` signature, domain and Java target (D8); a method's audience and effects | M5b |
| Go to definition, find references | within a file from the IR's resolution (F5): locals, parameters, state fields, `fn`s, types, enums, variants, built-ins (to their docs); across `.jsc` files and libraries, `import contract`; into `java:` imports as **read-only** documents of the library's on-chain sources (a `joust-lib:` URI; positions from javac attribution) | M5b (file), M5c (across) |
| Completion | in scope: locals, parameters, state fields, built-ins (`caller`, `received`, `balance`, `validFrom`, `validTo`, `state`, `params`, `funds`), `fn`s and the prelude; `std` rows inside `import { … } from "std/…"`, and elsewhere with an edit that adds the import (JSC0704's fix); a record's fields and a `List`/`Map`/`BigMap`/`Option`'s operations after `.`; enum variants and `Some`/`None` where a value of that type is expected; **all arms** of a `match` on an enum or `Option`, as a snippet; modifiers (`only`, `authorized`, `after`, `before`, `payable`, `open`) after a method's parameters; types in type positions. Nothing is offered that the checker would refuse at that position (no shadowing names, D3) | M5b |
| Rename | of a local, parameter, `fn`, type, enum, variant or field, within the file (M5b) and the project (M5c). The new name is checked against every D3 and J10 rule (no shadowing of state fields or built-ins, no binding named like a declaration, ASCII, not a Java keyword or a processor-generated name), and the renamed project is re-analysed: the rename is offered only if no new diagnostic appears and **every other reference still resolves to the same declaration** (L6). A name the ABI manifest records (state fields, fields reachable from the datum or a redeemer, action and destroy names and their parameters) is renamed only after a warning that the manifest, the generated client and the description change, and the ABI gate then asks for acceptance at the next build. Never edits `.java` files, library packages, or files outside the project's contracts | M5b, M5c |
| Semantic tokens | from the IR: state field vs local vs parameter vs `fn` vs `std` function vs type, enum, variant; modifiers for `readonly` (every binding), `defaultLibrary` (prelude, `std`), and a custom `timeValue` modifier for D11-tainted expressions | M5b |
| Code actions | **machine-applicable fixes**, each re-checked before it is offered (L6): `let`/`var` → `const` (JSC0128), `===`/`!==` → `==`/`!=` (JSC0133), add the missing `std` import (JSC0704), delete an invisible format or control character (JSC0145, JSC0148; never part of a fix-all, since it can change a string's content). Diagnostics whose fix needs restructuring (JSC0115's `function` → a typed `fn`, JSC0130's spread update, JSC0129's early return) link to the catalogue instead of guessing | M5b |
| Generated Java | "Show generated Java" opens the lowering as a read-only virtual document at the line the source map gives for the cursor; "peek" shows it inline. From the model's source map (the as-you-type lowering produces it, F1) | M5b |
| ABI manifest | when the build reports JSC0002/JSC0003, a code lens on the contract opens a diff (committed manifest vs this build's), and **Accept ABI…** runs the project's own acceptance (`joust build --accept-abi`; Gradle: the `acceptJscAbi` task) as a visible task, after the user has seen the diff. Never automatic, never from the server | M5b |
| Commands | **JOUST: Build** (the project's `joust build` or `./gradlew build`), **Accept ABI…**, **Migrate to .jsc 2** (`joust migrate`, ADR-0018 D16, once M1d ships it), **Show generated Java**, **Show compiler version**. Commands that run anything run it as a VS Code task through the project's own CLI or `gradlew`, only in a trusted workspace | M5a (Build), M5b (rest) |
| Formatting | D8 | M5d |

Not in M5: inlay hints, signature help beyond hover, call hierarchy, workspace symbols across libraries,
debugging (traces are `joust build --traces`), budget figures in hovers (ADR-0018 J19's build report could feed
them later).

### D8. A formatter, and why it waits

`.jsc` 2 has no formatter, and one cannot be written over today's tree (comments are dropped, F4). It also cannot be
safe today: **moving a line changes the script hash** (F12), so formatting a file whose script is deployed would
silently produce a different script. ADR-0018 D16 makes both CLIs warn when a recorded deployment's hash is not
produced by the build, but a formatter must not be the cause.

- **Gate:** before M5d, the generated `Require` messages stop depending on line numbers (an ADR-0018 D12 amendment:
  for example the method name and the construct's ordinal within it, which survive reformatting), so that layout,
  comments and blank lines never reach the script. Until then there is no formatter (Q2).
- **Design:** one fixed style with no options (as `gofmt`), over a trivia-preserving syntax tree (the lexer keeps
  comments and blank lines as trivia attached to tokens; the existing `Syntax` tree is built from the same tokens).
  `joust fmt` (both CLIs) and the server's `textDocument/formatting` call the same core entry.
- **Invariants (L7):** idempotent; every comment survives, in order, attached to the same token; the checked IR is
  unchanged except for positions; **the script hash, the ABI manifest and the description are unchanged**, and the
  generated Java differs only in its source-position comments. Tested over the whole corpus (D12).

### D9. Packaging

- **The VS Code extension** (`js/vscode-joust/`, beside the other JS packages; never published to npm):
  - M1d's TextMate grammar and a language configuration (comments, brackets, auto-closing pairs, indentation, word
    pattern) for `.jsc`; snippets (contract, action, `match`, `fn`, `enum`);
  - the client: server discovery and start (D3), status bar (compiler version, project or bundled, trust state),
    commands (D7), the ABI diff view, read-only virtual documents (`joust-gen:`, `joust-lib:`);
  - **Problems-panel integration**: a problem matcher `$joust` and task definitions for `joust build` and
    `./gradlew build`, so build output lands in the Problems panel with clickable positions. This needs one output
    shape: `joust build` adopts the catalogue's `File.jsc:12:5: error[JSC0502]: …`, the shape Gradle already prints
    (F9); both keep naming files relative to their source directory, which each task definition gives the matcher.
    That change to `js/joust/src/build.js` is part of M5a;
  - the bundled `@julclang/joust` (engine included), so one `.vsix` works offline and on every platform. The engine is
    platform-neutral Wasm, so the `.vsix` is **universal** (no `--target` builds). Size: about 11 MB of compressed
    engine (§1) plus the client;
  - `capabilities.untrustedWorkspaces: { supported: "limited", restrictedConfigurations: […] }` (D10).
- **Other editors:** the server is `npx joust lsp --stdio` in an npm project (the project's own package, D3), and the
  docs give configurations for Neovim (`vim.lsp.config`/nvim-lspconfig) and Helix (`languages.toml`), with M1d's
  tree-sitter grammar and queries for highlighting. Packaging the tree-sitter grammar for those editors (queries,
  `tree-sitter.json`) is M5e; publishing it to npm or crates.io is a release decision, like the extension's (D11).
- **Versions:** the extension pins the bundled `@julclang/joust` exactly and is released when JOUST is, with its own
  numeric version (F15: even minor for releases, odd minor for pre-releases, as the Marketplace recommends). The
  minimum VS Code version (`engines.vscode`) is the oldest version whose extension host loads the engine in the smoke
  test of D12; 1.109.0 is verified (§1); `vscode-languageclient` 11.0.0 alone would allow 1.91.0 (Q5).
- **The templates** (`npm create joust`, `joust new -t node|web`) add `.vscode/extensions.json` recommending the
  extension, once it is published.

### D10. Trust and security

- **Restricted mode** (an untrusted workspace): only the bundled server runs; every front-end feature works
  (analysis reads files, it does not run them: the toolchain attributes sources with JuLC's embedded javac and calls
  JOUST's generators directly, `Toolchain.java:28-34`, so no annotation processor or other code from the workspace
  runs); on-save builds run in the bundled engine and write nothing; **no command that runs a CLI, `npm`,
  `node_modules` code or `gradlew`** is available, and VS Code itself disables workspace tasks in Restricted Mode. The
  status bar says "restricted: bundled compiler".
- **Settings:** no setting names an executable, a path to a server, or arguments, unless its scope is `machine` (user
  settings only); every other setting the extension reads that could change what runs is listed in
  `restrictedConfigurations` (F15). No setting changes the docs base of `codeDescription` links or turns off the ABI
  gate.
- **Project discovery** reuses ADR-0017 D4's stop rule and manifest limits (F11), so a planted marker in a directory
  the user does not own is ignored, as on the command line.
- **Library sources** (`java:` imports, `.jsc` libraries in `joust-lib/`) are found only by the toolchain's
  resolution rules (ADR-0018 D9), never by a path the server builds from import text; read-only documents show them
  and cannot be edited through the extension.
- **No network, no telemetry.** The extension and the server open no connection (Q3 is the only open exception).
- **No writes by the server.** Everything the server changes is a `WorkspaceEdit` the editor applies (rename, quick
  fixes, formatting); everything else is a user-invoked command through the project's CLI (L3).

### D11. Publishing is human-only

- CI builds the `.vsix` (`vsce package`, plus `--pre-release` for pre-releases) on every pull request that touches the
  extension or the server, and smoke-tests it (D12). The release workflow attaches the release's `.vsix` to the
  GitHub release with its SHA-256 and a build-provenance attestation, as ADR-0017 D6 does for the CLI archives, never
  replacing an asset.
- **Publishing to the VS Code Marketplace and to Open VSX is done by a maintainer, by hand**, from that attached
  `.vsix` (`vsce publish --packagePath`, `ovsx publish`), after checking its hash against the release. No
  Marketplace or Open VSX token is stored in the repository or in CI. Automating it later needs its own decision
  (token custody, environments), like the Homebrew tap's in ADR-0017 D7.
- The publisher identity (a Marketplace publisher and an Open VSX namespace, which must match the `publisher` field)
  is created and owned by the maintainers (Q4).

### D12. Testing

- **Core (JVM):** golden document models (JSON) over a corpus: the examples' `.jsc` files, the checker tests'
  sources and the fixtures, which is also M1d's two-parser corpus. **Hand-written expectations** in annotated test
  files (markers such as `/*@def x*/`, `/*@ref x*/`, `/*@hover Int*/`, `/*@complete …*/`) for definition, references,
  hover and completion, written independently of the implementation.
- **Parity:** the core's models from the JVM and from the Web Image are byte-identical over the corpus (as
  `toolchainWasmTest` checks the build today).
- **Recovery (L5):** for every corpus file the build accepts, the recovering mode gives identical Java and ABI; for
  every corpus file and every single-token deletion or insertion (generated mutations), the server returns a model,
  and the build's error, when there is one, is among its diagnostics at the same code and position.
- **Protocol (Node):** start `joust lsp --stdio` and drive it with a JSON-RPC client: initialize, open, edit (version
  ordering, out-of-order and stale versions), diagnostics, hover, definition, references, completion, rename,
  code actions, cancellation, shutdown; golden transcripts; an engine failure mid-session (restart, L9); oversized
  and deeply nested files; non-BMP text (L11).
- **Edits (L6):** rename refusals for each D3/J10 rule; resolution preservation over generated renames of every
  binding in the corpus; each quick fix's output re-checked; ABI-visible renames warn.
- **Formatter (L7, M5d):** idempotence, comment preservation, identical script hash, manifest, description and Java
  modulo position comments, over the corpus and generated layouts.
- **Extension smoke tests** with `@vscode/test-electron` on the minimum and the current VS Code: install the `.vsix`,
  open the `npm create joust` template, wait for diagnostics on a seeded error, hover, run "Show generated Java";
  **restricted mode**: a workspace with a planted `node_modules/@julclang/joust` (whose code would create a sentinel
  file) and workspace settings naming an executable: the bundled server runs, the sentinel never appears, the
  setting is ignored, and no build command is offered.
- **Other editors:** the protocol tests cover the server; the Neovim and Helix configurations are checked by hand per
  release from a checklist (headless editor tests are not worth their upkeep yet).

## 4. Invariants

Numbered L… so they are not confused with the validator invariants I1–I55, ADR-0018's J1–J19 or ADR-0020's U….

| # | Invariant | Constrains | Checked by |
|---|---|---|---|
| L1 | For a document whose text equals what the build reads, the server's front-end errors include every front-end error the build reports for it (same code, line, column), and a document with no front-end error from the server passes the build's front end | D1, D2, D5 | corpus and mutation tests against `JscV2.lower` / `joust build` |
| L2 | The server analyses with the compiler whose version it reports (`serverInfo`, status bar, diagnostic `source`); in a trusted npm project with `@julclang/joust` installed, that is the project's own | D3 | protocol tests; smoke tests with two versions |
| L3 | The server writes no file. Changes reach the workspace only as edits the editor applies or as commands the user invokes, through the project's CLI or `gradlew` | D4, D7, D10 | protocol tests watch the workspace; smoke tests |
| L4 | In an untrusted workspace no workspace file is executed or loaded as code (no `node_modules` code, no Gradle, no executable from a workspace setting) | D3, D10 | restricted-mode smoke test with a sentinel |
| L5 | Recovery and spans never change the build: on every file the build accepts, the recovering mode and the span-carrying tree give byte-identical generated Java and ABI manifest | D5, D6 | corpus tests |
| L6 | Every edit the server proposes is re-analysed first: a quick fix removes its diagnostic and adds none; a rename adds no diagnostic, keeps every other reference resolving to the same declaration, breaks no D3/J10 rule, and warns when it changes an ABI-recorded name | D7 | rename and fix tests |
| L7 | Formatting is idempotent, keeps every comment, and leaves the script hash, ABI manifest and description unchanged (generated Java differs only in position comments) | D8 | formatter corpus tests (M5d) |
| L8 | Diagnostics shown for a document are computed for its current version; build diagnostics for a saved version are withdrawn when the document changes | D4 | protocol tests with interleaved edits and saves |
| L9 | No input (hostile, huge, deeply nested, binary) crashes or hangs the server: size and time caps hold, an engine failure yields one internal-error diagnostic and an engine restart | D4 | protocol tests; mutation corpus |
| L10 | On the reference corpus, analysis latency and memory stay within the bounds M5a records (regression bounds in CI, looser than the reference machine's) | D4 | CI measurement job |
| L11 | Positions are UTF-16 code units, correct with non-BMP characters before the position on the same line | D6 | protocol tests |

## 5. Alternatives considered

Beyond D2's hosts (a)–(e):

- **No language server; grammars only** (Elci's state, F13). Rejected: `.jsc` 2's rules (immutability, shadowing,
  time, audiences) are exactly what a newcomer trips over, and they are only visible to the checker. The stable codes
  exist; showing them as you type is the cheapest large gain.
- **Diagnostics from `joust dev`'s rebuild on save only** (it already watches `contracts/`). Rejected as the editor
  path: a full build per save is 0.5–1.6 s per contract (§1), errors come after saving, and positions arrive as text.
  It remains the CLI's workflow.
- **An incremental checker** (per-declaration invalidation, salsa-style). Rejected for now: whole-file re-analysis is
  milliseconds at 20 times the largest example (§1).
- **Tree-sitter as the server's recovering parser** (D5). Rejected for M5; Q6.
- **A front-end-only Web Image for the server** (measured, §1: half the size, half the start-up, about 130 MB less).
  Rejected: `java:` imports need javac, and a second image is a second artifact to build, ship and keep identical.
  Kept as the fallback if the full engine's memory proves a problem.
- **Publishing from CI with stored tokens.** Rejected for now (D11).

## 6. Consequences

**Positive:** the build's own front end in the editor, so the editor and the build cannot disagree about the language;
no JDK for anyone, and one universal `.vsix`; versioning by construction in npm projects; the stable codes and ABI
gate surface where the author works; the formatter question exposes F12, which matters for every edit to a deployed
contract's file, not only formatting.

**Negative:** memory (about 360 MB, plus about 365 MB while a build worker lives); a protocol layer in JavaScript and a
core in Java, so features cross a boundary and need parity tests; the compiler front end must grow recovery, spans,
trivia and a model export, all inside the trusted toolchain (L5 guards the build against them); Gradle projects get a
bundled compiler that may differ from theirs (Q3); the extension adds a release artifact and a manual publishing step.

## 7. Compatibility

No change to any contract, script, datum, redeemer, description or ABI manifest. `joust build`'s diagnostic lines
change shape (D9), which scripts parsing them would notice; the npm CLI is pre-1.0. The `JscV2` API is unchanged; new
entries are added beside it. The Web Image gains entries and reachability metadata for the model's records.

## 8. Milestones

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M5a | `joust lsp --stdio` with diagnostics as you type (the build's first error and all warnings, with codes and links), document symbols and folding; the engine entries `joust.lang.analyze`; the VS Code extension (M1d's TextMate grammar, language configuration, snippets, server discovery and fallback, status bar, trust model, **JOUST: Build** task and the `$joust` problem matcher); `joust build` prints the catalogue's diagnostic shape (D9); the `.vsix` built and smoke-tested in CI | ADR accepted; ADR-0018 M1 exited (M1d's grammars and catalogue in place) (Q1) | L1 (first error), L2, L3, L4, L9, L11 tested; latency, start-up and memory recorded on the reference corpus as L10's bounds; smoke tests green on the minimum and current VS Code |
| M5b | Recovery (parser and checker) and spans; hover, definition and references within a file, semantic tokens, completion, rename within a file, quick fixes, generated-Java view, ABI diff and **Accept ABI…**, build diagnostics on save (worker) | M5a exit | L1 (recovered), L5, L6, L8 tested; the recovery mutation corpus green; latency still within L10 |
| M5c | Across files: `.jsc` libraries, `import contract`, `java:` imports (read-only library documents), project-wide references and rename, library signature cache | M5b exit; ADR-0018 M4 exited; javac attribution cost in the engine measured | definition and references across a `.jsc` library and into a Java library in the template's tests; L6 across files |
| M5d | Formatter: trivia-preserving lexing, `joust fmt`, `textDocument/formatting` | M5b exit; **on-chain messages no longer carry line numbers** (Q2, an ADR-0018 amendment) | L7 over the corpus and generated layouts |
| M5e | Other editors: Neovim and Helix configurations, the tree-sitter grammar packaged with its queries, docs; the `.vsix` attached to a release for maintainers to publish (D11) | M5a exit (configurations), M5c exit (docs complete) | the release checklist run by hand on each editor |

Recommendation: ADR-0018's recommendation gate (`.jsc` 2 recommended once M2 and M4 exit and the security review
passes) does not depend on M5; the extension is labelled preview while `.jsc` 2 is.

## 9. Verification

D12 holds the test plan. In summary: independent expectations (hand-written markers, never the implementation's own
output) for navigation, hover and completion; JVM/Wasm parity of the document model; L1 and L5 checked against the
build itself over the corpus and generated mutations; protocol tests in Node; extension smoke tests including a
hostile, untrusted workspace; formatter invariants including the script hash. None of these certify the compiler:
that remains ADR-0018 §9's job.

## 10. Open questions

1. **When M5 starts.** ADR-0018 §8 gates M5 on M3 and M4. Options: (a) keep it; (b) start M5a and M5b after M1 exits,
   with only M5c gated on M4. Lean (b): the front end and its codes exist from M1; the server follows the checker as
   M2 and M3 add constructs, since it is the checker; an editor helps the preview's users most. The grammars need
   updating per milestone anyway (D13). This amends ADR-0018 §8.
2. **Formatter and script hashes (F12).** Options: (a) amend ADR-0018 D12 so generated messages carry no line numbers
   (method and ordinal instead), then build the formatter; (b) a formatter that only changes horizontal whitespace,
   which the hash ignores today; (c) no formatter. Lean (a): F12 also means that adding a comment line above an
   action changes a deployed contract's script, which surprises authors independently of any formatter. Changing the
   messages changes every existing `.jsc` 2 script hash once, before `.jsc` 2 leaves preview.
3. **The compiler for Gradle projects.** Options: (a) the bundled one, with a version notice (lean, M5a); (b) on
   consent, download the `@julclang/joust` matching the Gradle plugin's version from npm, checked against the
   registry's integrity, into the extension's storage; (c) a JVM host started through a Gradle task. Lean (a) now,
   (b) if users ask; (c) runs Gradle, i.e. workspace code, and needs a second host.
4. **Publisher identity.** `julclang` (the npm scope and Maven group) or `bloxbean` (the GitHub organisation), on both
   the Marketplace and Open VSX; which maintainers hold the publishing credentials. Lean `julclang`, matching the
   package names. Needs a maintainer.
5. **Minimum VS Code version.** Lean: the oldest version whose extension host loads the engine in the smoke test
   (1.109.0 verified; older Electron versions may need the exnref flag, which `loadImage` sets, or lack the feature).
6. **Tree-sitter in the server.** Lean no (D5); revisit with M5b's recovery results.
7. **Build on save by default.** Lean on in trusted npm projects (it writes nothing), off in restricted mode; a user
   setting turns it off.
8. **A JVM host later.** The core is plain Java; an LSP4J host is small. Lean: not in M5; only if Gradle users of
   other editors ask.
9. **The document model and M1b's IR export.** The M1a report asks M1b to choose between a versioned IR export and a
   JavaScript front end. Lean: keep the document model separate (it is editor-shaped and may change with the
   extension), whatever M1b decides.

## 11. Related findings (out of scope)

- The two CLIs print diagnostics in different shapes (F9). D9 fixes the npm shape in M5a, because the problem matcher needs one; it is also a plain inconsistency with
  the catalogue's description.
- F12 (line numbers in on-chain messages) affects every edit to a deployed contract's source, not just formatting;
  ADR-0018 D16's warning on a mismatched deployment hash is the current guard. Raised as Q2.
- On Gradle, processor and JuLC messages on generated Java are not remapped to `.jsc` positions yet (F10, an M1d item
  of ADR-0018); the editor's build path is the npm toolchain's, which remaps.
- The toolchain reports an internal lowering failure at line −1 (F10); the server maps it to 1:1, but the toolchain
  could give the position of the construct being lowered.

## Revision history

- **r1** (2026-11-13): initial proposal, with the measurements of §1 (toolchain and front-end-only Web Images, JVM
  front end, VS Code 1.109.0's runtime) and F12 (line numbers reach the script hash), found while designing the
  formatter.
