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
| D13 a shared parse corpus: 71 files (v2/v1, valid/invalid) plus every `.jsc` in the repository and the 70 research files, run through both the hand-written lexers/parsers and tree-sitter | `tree-sitter-jsc/corpus`, `JscCorpusTest`, `test/shared-corpus.mjs` | both sides agree; 1 known difference (README) |
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
- **Newlines do not end statements in `.jsc` 2**: a line starting with `(` or `-` continues the expression above it
  (`const c = !flag && flag` then `(c ? m : -m) - …` parses as a call of `flag`). Migrate ends the line above such a
  line with `;`. Authors can hit it too; a lint (or a parser rule like the one for `[`) is a maintainer decision.
- A v1 method named like a generated name (`step` → `Step`, JSC0203) is refused by `.jsc` 2, so migrate refuses it
  (JSC1104 with JSC0203).

## Tests run at this head

After merging `feat/jsc-2-m1a` at r4 (`dfa7025`):

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test :examples:dsl:test :julc-state-toolchain:test :julc-state-gradle-plugin:test` (each after `cleanTest`) | green: dsl 82, examples/dsl 19, toolchain 64, plugin 8; none failed or skipped |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (both images rebuilt from this head) | `ALL SAME` (every `.jsc` file's generated Java, source map and manifest equal to Gradle's, GuardsV2 and PointsV2 included); S2 in Wasm 1,393/1,393; S2b 43/43 |
| `npm test` in `js/joust` (the rebuilt engines in `engine/`; DevKit up) | 29/29, including `migrate.test.js` (3) and the DevKit tests of the templates (`create.test.js`, `dev.test.js`, `page.test.js`) |
| `npm test` in `js/create-joust` | 9/9, the build test included (the `.jsc` 2 and Java templates give the same description and client) |
| Template smoke: `create-joust app --template node --lang jsc`, packages linked, `joust build`, `joust deploy`, `npm test` on DevKit | `app.Counter: 5759 bytes`, deployed, template test 1/1 |
| `npm test` in `tree-sitter-jsc` | 12/12 trees; shared corpus: 71 corpus files, 15 repository files, 70 research files, tree-sitter agreeing with the hand-written parsers (1 known difference) |
| `node --test` in `editors/vscode-jsc` | 3/3 |
| `npm run build` in `www` | 67 pages, every internal link resolves |
| `./gradlew build --continue` | everything green except 10 DevKit tests in examples/escrow, registry and wallet, each `IllegalStateException: topup failed` (DevKit's faucet answered 500 to concurrent top-ups; none of those modules is changed here); `:examples:dsl:checkJscAbi`: `10 ABI manifest(s) agree with the processor's descriptions`. Rerun alone, `./gradlew :examples:escrow:test :examples:registry:test :examples:wallet:test --max-workers=1`: BUILD SUCCESSFUL (escrow 7, registry 81, wallet 3 tests, none failed) |

## Not in M1d

Gradle's half of "both CLIs warn about recorded deployments" (the Gradle path records no deployments file); a
language server (M5); publishing the VS Code extension or the tree-sitter grammar; wiring the tree-sitter and TextMate
tests into CI (they run with `npm test` in their directories).
