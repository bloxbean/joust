# `.jsc` 2, milestone M1d: report (ADR-0018 §8)

Date 2026-11-13. Branch `feat/jsc-2-m1d`, stacked on `feat/jsc-2-m1a` (merged at its review round r4, `dfa7025`).
JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, GraalVM 25.3.4.1, Node 22, macOS arm64.

M1d is the tooling of M1: editor support (D13), `joust migrate` and v1's deprecation (D16), the templates switched to
`.jsc` 2 as a preview (D14), the Gradle half of D12's diagnostic remapping, and the docs. M1b (the JavaScript
reference interpreter and the written semantics) and M1c (the example ports and the budget gate) are separate.

## What M1d built

| ADR item | Where | Tests |
|---|---|---|
| D16 `joust migrate`: the checked v1 AST translated with v1's dataflow (SSA consts; an `if` that assigns becomes an `if` statement for its checks and payouts plus an `if` expression per field, only the taken arm evaluated; `Map<Key, Int>` becomes `BigMap<Key, Int>` with `set` chains, reads after writes resolved by key equality, arm writes as `set`s of the taken arm's value); comments kept; the result checked by the `.jsc` 2 front end | `julc-state-dsl` `Migrate`, `JscMain --migrate` | `MigrateTest`, `JscMigrateDifferentialTest`, examples/dsl `MigratedTest` |
| D16 refusals with codes, at the v1 position: already `.jsc` 2 (JSC1101), not valid v1 (JSC1102), `batched` (JSC1103, M3), anything `.jsc` 2 rejects such as time deciding a branch (JSC1104 with the rule's own code), a map key computed in an arm (JSC1105), a dead value that can fail (JSC1106), an existing `.jsc.v1` backup (JSC1107) | `Migrate`, `JscMain`, `js/joust/src/migrate.js` | `MigrateTest.refusals_*`, `migrate.test.js`; catalogue rows checked by `JscV2DiagnosticsTest` |
| D16 the CLIs: `joust migrate [<file.jsc>…] [--in-place]` (npm, through the toolchain engine's new `joust.migrate`), `migrateJsc` (plugin, `--in-place` option; examples/dsl), `JscMain --migrate <src> <out> [--in-place]`. Default output `build/joust/migrated/` / `build/jsc-migrated/`; in place keeps `Name.jsc.v1`, never overwritten. Notes: renamed parameters (client-visible), payout order | `bin/joust.js`, `JoustToolchainWasm`, `MigrateJsc`, `JoustPlugin` | `migrate.test.js`, `JoustPluginTest` |
| D16 "both CLIs warn when a recorded deployment's script hash is not produced by the current build": `joust build` warns for `deployments.<network>.json` entries whose `policy` (the script hash) differs | `js/joust/src/build.js` `staleDeployments` | `migrate.test.js` |
| D16 v1 deprecation (moved from M0): every v1 file warns JSC9006 at its first token, on both paths, pointing at `joust migrate` / `migrateJsc` | `JscMain.lowerWithWarnings` | `MigrateTest.v1Files_warnThatTheyAreDeprecated`, `JscV2CheckerTest` |
| D14 templates: create-joust's `.jsc` variant (node and web) and the native joust's Gradle `-l jsc` template are `.jsc` 2, labelled preview, and ship `Counter.abi.json` (the Gradle one with `@@package@@`), so a first build passes the gate (the M1a report's JSC0003 point) | `js/create-joust/variants/jsc`, `julc-state-cli` templates, `Scaffold` | `JscTemplatesTest` (shipped manifest = the lowering's, for several packages), create-joust's build test (`.jsc` and Java templates give the same description and client), `ScaffoldTest`, js/joust `create.test.js`, `dev.test.js`, `page.test.js` (DevKit) |
| D12 leftover, Gradle path: compileJava's output kept in `build/jsc/javac.log` (a listener added at execution, configuration-cache safe); `remapJscDiagnostics` (its finalizer, only when the log holds something) prints each javac/processor diagnostic on generated Java at the `.jsc` line and column of the source map beside it, with the generated location as a note | `JscMain --remap`, `JoustPlugin`, examples/dsl `build.gradle` | `MigrateTest.gradleRemap_*`; by hand on examples/dsl (below); `JoustPluginTest` |
| D13 tree-sitter grammar for `.jsc` 2 and v1 (one grammar, two top-level forms), an external scanner (`[` on the same line, even `#hex`, `{` after `=>`), highlight and fold queries | `tree-sitter-jsc/` | `tree-sitter test` (12 trees) |
| D13 a shared parse corpus: 71 files (v2/v1, valid/invalid) plus every `.jsc` in the repository and the 70 research files, run through both the hand-written lexers/parsers and tree-sitter | `tree-sitter-jsc/corpus`, `JscCorpusTest`, `test/shared-corpus.mjs` | both sides agree on verdicts (r1: only verdicts were compared, and one of the README's seven differences was pinned; r2 compares declarations and pins every difference, below) |
| D13 a TextMate grammar (VS Code extension, not published) used by the docs' Shiki in place of the Kotlin alias | `editors/vscode-jsc`, `www/astro.config.mjs` | `grammar.test.mjs` (vscode-textmate), the www build |
| Docs: `.jsc` 2 guide, reference (ABI accept flow, diagnostics with the catalogue link), std library (tables generated from `JscV2.bindings()`), migration, v1 (deprecated); other pages' snippets moved to `.jsc` 2 where it has the feature | `www/src/content/docs/languages/*`, others | `JscV2StdDocTest`; the www build with its link check |
| Cleanup: `-Ajoust.portable=true` and `Dsl2PortableTest` removed from examples/dsl (#65 retires portable); `Dsl2JsFixturesTest` stays | | |

## Migration, checked

**Every v1 example.** Guards and Points migrate; their migrations are committed in examples/dsl as `GuardsV2` and
`PointsV2` (package `…dsl.migrated`, renamed because JuLC names a module's compiled scripts by simple name,
`META-INF/plutus/<Name>Validator.plutus.json`) and `MigrateTest` checks they are exactly migrate's output. Jar is
refused (JSC1103, its `tip` is `batched`). The old v1 template Counter migrates to a program with exactly the new
template's ABI manifest (the new template is that output with one more comment line). Script sizes, examples/dsl:
Guards 4,451 → GuardsV2 4,347 bytes; Points 12,380 → PointsV2 12,180.

**Differential, generated** (`JscMigrateDifferentialTest`): 160 generated v1 programs (103 with a `Map<Key, Int>`),
each with ifs nested two deep that assign in one or both arms, checks and payouts in arms, map writes and deletes in
straight-line code and in arms, `has` and reads through earlier writes, `this.f` against a parameter of the same
name, and divisions that must not run. v1's lowering runs on the JVM (compiled in the test), the migration on the
`.jsc` 2 reference interpreter (written independently of both lowerings): 6,400 calls, 1,328 refused by both, 1,013
actions with payouts, all equal (next state, the map's contents on every key a call can name, payout multisets). Two
mutations of `Migrate` (arms swapped in a merge; a read through a write ignoring the write) each fail it. 16 of the
176 valid programs are refused, all JSC1106 (a division computed and overwritten unread: v1 evaluates it, `.jsc` 2
would not).

**Differential, built** (examples/dsl `MigratedTest`): Guards and GuardsV2 on every `d` in −120..120; Points and
PointsV2 over 400 random award/spend/give calls from three keys, both prepared by `MerkleSupport` as the runtime does
(the stored states, map roots included, equal; 94 refused by both); the migrated validators accept sampled calls and
reject the attack suite.

**Gradle remap, by hand** (examples/dsl with a `Counter2.java` duplicating the generated class):
`…/Counter2.jsc:5:1: error: duplicate class: org.julclang.state.examples.dsl.Counter2 (in the Java generated from
Counter2.jsc: org/julclang/state/examples/dsl/Counter2.java:21:8)`, after Gradle's own report of the javac error.

## Found on the way

- **A v1 defect** (frozen, not fixed): a v1 contract with a `Map` field that no method writes lowers without
  `import org.julclang.state.api.MerkleMap`, so javac refuses it (the import is added only when a method body names
  the type, after the imports are rendered). The generator hit it 3 times in 157 map programs before its seeding was
  fixed; `JscMigrateDifferentialTest` skips such programs. Fixing it changes no script that compiles today.
- **Newlines do not end statements in `.jsc` 2**: a line starting with `(` or `-` continued the expression above it.
  Decided in r2 (ADR-0018 r13 D3): a `(` on a later line never continues a call; a leading `-` continues, with
  JSC9007. Migrate still ends a line above a leading `-` with `;`.
- A v1 method named like a generated name (`step` → `Step`, JSC0203) is refused by `.jsc` 2, so migrate refuses it
  (JSC1104 with JSC0203).

## Tests run at this head (review round r2)

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test :examples:dsl:test :examples:dsl:checkJscAbi :julc-state-toolchain:test :julc-state-gradle-plugin:test -PnoDevnet` (each after `cleanTest`) | BUILD SUCCESSFUL: dsl 91, examples/dsl 17 (19 with DevKit), toolchain 64, plugin 8; none failed or skipped. The differential: 262 programs (160 Gen1, 100 Gen2, 3 repros), 156 migrated (refusals JSC1105 7, JSC1106 99), 6,240 calls agree (1,628 refused by both); all 8 mutants caught (13 to 598 disagreements each). `checkJscAbi`: 10 manifests agree |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (both images rebuilt from this head) | `ALL SAME` (GuardsV2 and PointsV2 included); S2 in Wasm 1,393/1,393; S2b 43/43 |
| `npm test` in `js/joust` (the rebuilt engines; DevKit up) | 30/32; the two failures were DevKit's dry faucet (`the faucet is dry …`, and a page whose deploy had failed for it); `page.test.js` rerun alone: 3/3 |
| `npm test` in `js/create-joust` | 9/9, the build test included |
| `npm test` in `tree-sitter-jsc` | 12/12 trees; shared corpus: 82 corpus files, 15 repository files, 70 research files; agrees on every verdict (11 known differences, pinned) and on the declarations of 21 files |
| `npm test` in `editors/vscode-jsc` | 3/3 |
| `npm run build` in `www` | 67 pages, every internal link resolves |
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL (189 tasks); `checkJscAbi`: 10 manifests agree |

Round r1's runs, at `d7aa862`, are in the PR's history (the r1 description).

## Review round r2

The three reviews of r1 (`d7aa862`) and what changed; every repro and mutation is a committed test.

- **Migrate fidelity** (Codex C1, P1; design F1). A const now has an identity kept through pruning and inlining:
  same-named locals on one line in sibling arms were taken for each other, so a dead division in one arm was lost and
  a call v1 refuses was accepted; it is refused (JSC1106) again. F1's mutation (inlining a value that can fail into a
  lazy position) survived the r1 differential; the generator now writes such values (and the same-line locals), and
  `eachMutantOfMigrateFailsTheDifferential` compiles eight named mutations of `Migrate.java` (F1's, C1's name-and-line
  identity, swapped arms, a read ignoring a write, `has` always true, `delete` as a no-op, a missing parenthesis, a
  merge reading the base map) and requires each to fail. The differential also runs the security review's independent
  generator (two maps, `Bytes`, `received`/`balance`, `else if`, depth 3, aliasing keys, a reassignable owner) and the
  repros, with every Int argument zero in each method's first calls.
- **Paths and links** (C2, S2, S3). Without `--in-place`, an output that is or holds the sources, or a target that is
  a source, is refused (JSC1108); npm's default mode writes only under `build/joust/migrated/`. `--in-place` never
  replaces a link (JSC1109) and writes the backup create-new (a dangling link is JSC1107); neither walker follows a
  directory link, and npm's skips a dangling one.
- **Remap** (C3): the `.jsc` file that declares the unit's package and contract; none or several keep javac's location.
- **The migration flow** (S1): `joust deploy` merges, keeping replaced records under `superseded`; `--in-place` copies
  the v1 build's artifacts to `build/joust/v1/`; the stale warning covers another script (by script digest, so
  `@Params` too), a renamed or removed contract and an unreadable file; `migrateJsc` says the new-script warning. The
  migration page documents how to reach v1 instances.
- **Newlines** (F2, S4): a `(` on a later line never continues a call (both parsers); a line starting with `-` still
  continues, with JSC9007 and a hint in any error of the file. `const payout = amount` then `-fee` now warns.
- **CI** (F3): `npm test` in `tree-sitter-jsc` and `editors/vscode-jsc`.
- **Language preview** (F4): the templates, READMEs and both scaffolds' output say what it means; ADR-0018 r13 D14
  says preview deployments are rebuilt with the compiler recorded for them (M1e changes every `.jsc` 2 hash once; corrected after the M1 integration: M1e changed the hashes of the scripts with a
  generated check only).
- **P3s.** F5: the corpus compares declarations (`corpus/shapes.txt`) and pins each README difference
  (`corpus/known.txt`, the reviewer's `retun {…}` and `return else {` included). F6: v1 names that are `.jsc` 2 words
  are renamed (parameters, lets) or refused (JSC1110); JSC1104 points at the statement's column; the `isEmpty` row
  corrected. F7: `wordPattern`, `engines.vscode ^1.109.0`, `JscTextMateTest` (the grammar names exactly the front
  ends' words), the path noted for ADR-0021 M5a. F8: ADR-0018 r13.

## Review round r3

Design and security approved r2 with P3s; Codex's C2–C4 were open. What changed (each repro is a test):

- **Paths and in place** (Codex C2, security S5, S6). Every default-mode target is checked before anything is written:
  through any link on its path, resolved at its nearest existing ancestor, it may not lie in the sources or be any
  source (Codex's `out/a -> ../src/b` alias is refused, JSC1108, on both paths; npm's directory check resolves like the
  JVM's `within`). In place, a file with more than one hard link, under a linked directory, or whose real path is not
  under the sources is refused (JSC1109), and the new text is renamed over the source from a temporary file.
- **Remap** (C3): a candidate is identified by the unit its parser reads, so declarations in comments do not count.
- **v1 artifacts** (C4): kept by the contract name the engine's parser reads (a contract on the package's line
  included), the shared ones always; a failed copy leaves the file unreplaced.
- **Deployments files** (S7): a parseable file of the wrong shape is unreadable (kept beside the new file, warned at
  build); merging and the stale warning are null-safe; the new records are always written.
- **Rebuildability** (design F9): each deployment record states `built` (the package's, the engine's JOUST and its JuLC
  versions); ADR-0018 D14 (r13) says so, and to commit deployments files when a deployed preview script must be rebuilt.
- **JSC9007's note** (F10): only the nearest leading `-` on or before the error.
- **Differential coverage** (F11): 260 of 342 programs migrate (Gen1 160, Gen2 100; JSC1106 71), 26,000 calls agree,
  1,005 actions pay out; the eight mutants are still caught.

## Review round r4

Design and security approved r3; Codex's C5 (P2) and C6, S8, F12, F13 (P3):

- **Outputs by rename** (Codex C5): default-mode outputs are written to a new file and renamed over the target on both
  paths, so a target's inode is never written; an existing target that is a source by identity (a hard link), or has
  more than one link, is refused before anything is written.
- **Temporary files** (C6): npm's have a unique name, and only a file the call created is removed.
- **A file argument in a linked directory** (security S8): refused in place on the JVM.
- **The minus note** (design F12): the parser records each member's lines; the note looks within the failing member,
  backward first, then forward (N1, a value block whose error is found at its start, gets it again).
- **Engine versions** (F13): stamped into a generated class at build time; the Wasm test and the npm test check that a
  built engine does not report `dev`.

## Not in M1d

A deployments record on the Gradle path (it has none; ADR-0018 r13 D16 narrows the stale warning to npm); a language
server (M5); publishing the VS Code extension or the tree-sitter grammar (ADR-0021 M5e).

## After the M1 integration (`feat/jsc-2-m1`, #76)

M1e's message keys (#71) reached the migrated examples when the four M1 parts were merged. Measured as above (the
`cborHex` of `examples/dsl/build/classes/java/main/META-INF/plutus/*.plutus.json`):

| Contract | At M1d's head | Merged | Hash merged |
|---|---|---|---|
| Guards (v1) | 4,451 | 4,451 | `dadd5921…` (v1 is frozen) |
| GuardsV2 | 4,347 | 4,347 | `d810e1fa…` |
| Points (v1) | 12,380 | 12,380 | `17e41c6c…` |
| PointsV2 | 12,180 | 12,083 | `91bab1a4…` |

The migrated text is unchanged (`MigrateTest`), and Gradle and the Web Image toolchain give the same scripts. Other
consequences of the merge for M1d's work:
- The `.jsc` template's refusal now names its site (`[app/Counter.jsc:22:5 (a require in increment)]`), which
  `js/joust/test/create.test.js` and the npm quick start expect.
- `batched` is in `.jsc` 2 since M1c, so tree-sitter's v2 grammar has `batched` and `batched only`, JSC1103 says that
  migrate does not translate v1 `batched` actions (ADR-0018 D16, r14), and the www pages describe the modifier.
- `JscV2.isReservedName` includes the modifier words (JSC0211). v1 cannot declare such names (they are v1 keywords),
  so migrate's output does not change.
