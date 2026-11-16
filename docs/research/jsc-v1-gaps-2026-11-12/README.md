# Is `.jsc` ready to be the recommended contract language for JS developers?

> **Status note (2026-11-12, with ADR-0018 r3).** This is the research as written before ADR-0018 r1, kept for the
> record. Superseded statements: "within about 5%" (the crowdfund port was +63%; sizes are historical observations
> with the engine of that day, not reproduced with a pinned artifact: ADR-0018 F4); "the template does not use `.jsc`"
> (PR #53 made `.jsc` the default). `probe-results.txt` was regenerated from `probes/` against `julc-state-dsl` at
> `0e4ad41` and has one row per probe.

Research date 2026-11-12. Sources: `julc-state-dsl` (Lexer/Parser/Ast/Lowering/JscMain), ADR-0005, ADR-0015, `examples/dsl`,
`js/create-joust`, `js/joust`, `www/src/content/docs/languages/jsc*.mdx`, JuLC docs. Probes, ports and outputs are in this directory: `probes/` (the probe files), `probe-results.txt` (their lowering
results, from `Probe.java` run against `julc-state-dsl` at `0e4ad41`: `java -cp <dsl classes> Probe.java probes out`), and
`ports/` (the five example ports and the two-counter machine, each built in an npm project with
`js/joust/bin/joust.js build`). Committed with ADR-0018 r2; written before ADR-0018 r1.

**Short answer:** no, not yet. `.jsc` v1 covers the single-UTxO subset well: counter, vault, escrow, crowdfund (as a
MerkleMap), token, pool and jar. All of them compile end to end through the npm Wasm toolchain, and the scripts are within
about 5% of the Java ones. It has no functions, no loops, no lists, no records, no conditional expression, no
mutable locals, no byte or hash operations, and none of the protocol features added after ADR-0005 (owned or keyed
objects, assets, sends, params, events, invariants, quorum, purposes, composition). The npm path also loses its
diagnostics. The template does not use `.jsc`. There is no editor support, and the npm test story is DevKit-only.

---

## 1. What `.jsc` supports today (exact inventory)

### Grammar (from Parser.java)
```
file    = "package" seg {"." seg} [";"] "contract" Ident ["(" opt {"," opt} ")"] "{" {member} "}" EOF   (Parser.java:241-283)
opt     = ("minAda"|"maxDatumBytes"|"maxValidityMs") "=" INT                                           (:178, :250-261)
member  = "state" Ident ":" type [";"]
        | "init" params {mod} block | ("action"|"destroy") Ident params {mod} block                    (:305-332)
        | "view" Ident params ":" type "=" expr [";"]                                                  (:334-344)
type    = Int | Bool | Bytes | Key | Map<Key, Int>                                                     (:285-303)
mod     = payable | open | batched [only] | (only|after|before) Ident                                  (:179, :314-330)
stmt    = let x = e | require e "msg" | pay e to e | if e block [else (if…|block)]
        | delete m[e] | [this.] f (=|+=|-=) e | m[e] (=|+=|-=) e                                       (:374-425)
expr    = || > && > == != > < <= > >= > + - > * / % > unary ! - > primary  (left-assoc, no chained compares) (:435-461)
primary = INT [ada] | #hex | true | false | this.f | f(args) | m[e] | name | (e)                        (:463-512)
```
- Lexical rules (Lexer.java): `//` comments only (no block comments, :27); `#hex` with an even number of digits (:38-43);
  strings on one line, no escapes (:44-52), used only as `require` messages; `_` digit separators (:36-37);
  newlines are not significant.
- One contract per file (Parser.java:281); no `import`.

### Types (Ast.java:78-99; Lowering.javaType)
| .jsc | Java | Where |
|---|---|---|
| `Int` | `BigInteger` | everywhere; literals must fit in 63 bits (Lowering.java:838-842) |
| `Bool` | `boolean` | everywhere |
| `Bytes` | `byte[]` | everywhere; only `==`/`!=` work on it |
| `Key` | `byte[]` (28-byte check on params, Lowering.java:304-310) | Key ⊂ Bytes, not vice versa (Ast.java:88-90) |
| `Map<Key, Int>` | `MerkleMap` | state fields only; values ≥ 0; 0 = absent (Lowering.java:486-505) |

**Absent:** `Address`, `List`/arrays, `Option`, records/structs, enums or sum types, strings, `Value`/assets,
any other map shape, and inline (JulcMap) maps.

### Statements
`let` (immutable, block-scoped, must be used: Lowering.java:312-317; no shadowing, :446). Field assignment `= += -=`
on state fields only. Map entry writes and `delete`. `require cond "msg"`. `pay amount to key` (lovelace only, to a
Key; 0 skipped, negative rejected: :520-531). `if/else if/else` (each arm is a static helper returning a record: :542-639).
**Absent:** loops (`for`, `while`), `return`, `match`/`switch`, mutable locals (`var`), `fail`, assignments to anything
but state fields, `emit`, `send`, `mint`.

### Expressions
Integer arithmetic `+ - * / %`: `/` truncates and `%` takes the dividend's sign, both like JS BigInt (Lowering.java:798-809).
Comparisons on Int only, so there is no Bytes ordering (probe 21d). `==`/`!=` on Int, Bool and Bytes/Key, where byte
equality uses `ByteStringLib.equals` (:820-834). `&& || !` short-circuit. The `ada` suffix works on literals only.
Built-ins: `caller`, `received`, `balance`, `validFrom`, `validTo` (:737-746).
Functions: `min`, `max`, `has(m,k)`, `isEmpty(m)`, `after(t)`, `before(t)` (:750-786). **That is the entire function
set.** There are no user functions, no byte functions (length, concat, slice), no hashes and no signature checks
(probe 21: `unknown function 'sha2_256' (min, max, has, isEmpty, after, before)`). There is no conditional expression
(probe 10) and no if-expression (probe 31).

### Contract-model coverage (Java annotation → .jsc)
| Java | .jsc |
|---|---|
| `@Init`, `@Action`, `@Destroy`, `@View` | `init` (always named `init`), `action`, `destroy`, `view` (single expression, no Ctx) |
| `@Payable`, `@SignedBy`/`@OnlyOwner`, `@ValidAfter`, `@ValidBefore`, `@Batched(only)` | `payable`, `only f`, `after f`, `before f`, `batched [only]` (Lowering.java:353-412) |
| `Require`, `Payouts`, `Clock`, `Ctx`, `MerkleMaps` (Int values) | `require`, `pay`, `after()`/`before()`, built-ins, `m[k]` |
| `@StateContract(minAda, maxDatumBytes, maxValidityMs)` | contract options. Not supported: `assetSlots`, `stakeSelf` |
| `@Owned/@OwnedInit/@OwnedAction/@OwnedDestroy/@Shared/@Into/@Admits`, keyed `@Owned(key=…)` | **none** |
| `Assets`, `Funds`, `Sends`, mints, `Datum`, `Address` | **none** |
| `@Params`, `@Quorum`, `@AuthorizedBy`, `@Invariant`, events, `@Reads`, `@Receives`/deposits, `@Migrate*`, `@Withdraw/@Certify/@Votes/@Propose` | **none** |
| `@Batched` with a MerkleMap | rejected (Lowering.java:374-376) |

The docs agree (`www/.../languages/jsc.mdx:570-578` "Limits"; `jsc-reference.mdx` is a complete and accurate grammar
plus error catalogue). The ADR's open items are owned objects, bounded loops, a TextMate grammar, source maps and a
reference interpreter (ADR-0005 §5, lines 243-249).

## 2. Turing completeness

- **`.jsc` is not Turing complete, and per call not even primitive-recursive complete.** It has no loops, functions,
  recursion or lists. A method body is a finite tree of guarded straight-line code. Each statement runs at most once
  (if-arms are helpers called at most once, ADR-0005 D3), so every call terminates, and its cost is linear in source size
  times operand size. ADR-0005 D2 chose this on purpose ("No loops in v1", line 126).
- **Across transactions the state machine is Turing complete.** With unbounded `Int` fields and `if`, a contract can
  run a 2-counter Minsky machine one instruction per call. `minsky/contracts/m/Minsky.jsc` builds (4,011 B). So
  "not Turing complete" is a per-transaction property, and an off-chain driver supplies the iteration.
- **JuLC's Java subset is Turing complete modulo budget.** It has for-each over lists, compiled to folds with
  accumulators (julc `docs/.../guides/for-loop-patterns.md`), `while` loops desugared to tail recursion
  (`getting-started.md:891-901`), self-recursive helpers through the Z-combinator (`advanced-guide.md:1356-1380`), and
  mutual recursion of at most two methods (`getting-started.md:1581`).
- **What this means in practice:** on-chain, every script runs under the per-transaction ExUnits limit (mainnet is
  roughly 10 billion CPU steps and 14 to 16.5 million memory units; *I did not check the current parameter values*), so
  no program on Cardano is unbounded. JOUST adds tighter limits: batches top out at about 8 intents and scripts must
  stay well under 16 KB. The question that matters is whether `.jsc` can *iterate over data*: a list of guardians,
  line items, a parameter list, or a fold over a small inline map. It can't, because it has no lists to iterate. The
  JOUST examples barely loop in contract bodies. Only `rollup/Ledger.java:72-103` and
  `staking/StakedVault.java:97` do, because the generated validator does the heavy iteration. The right target is
  therefore **bounded `for x in list` folds plus non-recursive helpers**. That keeps totality, which is a property
  worth advertising. `while` and general recursion are not needed.

## 3. Empirical probes (DSL lowering, `Probe.java` → `JscMain.lowerWithWarnings`)

| Probe | Result (exact message) |
|---|---|
| `while n < k { n += 1 }` | `6:30: error: expected '=', '+=' or '-=' but found 'n'` |
| `for i in 0..k {…}` | `6:28: error: expected '=', '+=' or '-=' but found 'i'` |
| `for (let i = 0; …)` | `6:28: error: expected '=', '+=' or '-=' but found '('` |
| member `fn double(x: Int): Int = x * 2` | `6:5: error: expected state, init, action, destroy or view but found 'fn'` |
| call `double(k)` | `6:28: error: unknown function 'double' (min, max, has, isEmpty, after, before)` |
| `state xs: List<Int>` / param `List<Int>` | `unknown type 'List' (Int, Bool, Bytes, Key, Map<Key, Int>)` |
| `Map<Key, Bytes>` | `3:18: error: v1 maps are Map<Key, Int>` |
| `Address`, `Option<Int>` | `unknown type 'Address' …`, `unknown type 'Option' …` |
| `record Point {…}`, `event`, `invariant`, `owned` | `expected state, init, action, destroy or view but found 'record'` (likewise for the others) |
| `let x = k; x = x + 1` | `7:9: error: 'x' is a let: lets are immutable` |
| `n = k > 0 ? k : 0` | `6:34: error: unexpected character '?'` |
| `let y = if k > 0 { k } else { 0 }` | `6:32: error: unexpected 'if' in an expression` |
| let declared in both arms, used after | `7:12: error: unknown name 'y'` |
| `return` | `6:43: error: expected '=', '+=' or '-=' but found '}'` |
| `match k {…}` | `6:30: error: expected '=', '+=' or '-=' but found 'k'` |
| `label = "abc"` | `7:26: error: unexpected 'abc' in an expression` |
| `import foo.Bar` | `1:1: error: expected 'package' but found 'import'` |
| `/* comment */` | `6:5: error: expected state, init, action, destroy or view but found '/'` |
| `require(k > 0, "pos")` | `8:37: error: expected ')' but found ','` |
| `"say \"hi\""` | `escapes are not supported in strings` |
| `k ** 2` | `unexpected '*' in an expression` |
| `sha2_256(x)`, `b + x` on Bytes, `x < b` on Bytes, `x.length` | `unknown function 'sha2_256' …`; `expected Int but this is Bytes`; `expected a statement but found '.'` |
| `owner = b` (Bytes → Key) / `pay 5 to b` (Bytes) | `expected Key but this is Bytes` |
| `n = k ada` | `expected a statement but found 'ada'` |
| `100000000000000000000` | `integer literal too large` |
| `require caller == owner \|\| after(deadline)` | `after(t) holds for the whole validity window, so it is allowed only as a requirement …` |
| `batched` + Map | `batched actions and Map state cannot be combined yet (ADR-0004 D1)` |
| `quorum guardians 2`, `send …`, `mint …` | `expected '{' but found 'quorum'`; `expected '=', '+=' or '-=' …` |
| **OK** | nested if / else-if chains, 4-deep nested ifs, pay in branches, destroy paying in both arms, Bool ops, `%`/unary `-`, views with params, `;` style, Bytes `==` |

The positive probes all build to UPLC through the npm toolchain (`npmproj/`; 2.4 to 5.7 KB each).

**Safety finding: the `after`/`before` guard can be bypassed.** `validFrom` and `validTo` are plain `Int` built-ins
(Lowering.java:744-745), so `if validFrom >= deadline {…}` and `require !(validFrom >= deadline) "…"` are both accepted
(probes 60 and 61). Those are exactly the uses ADR-0005 D2 (lines 115-118) bans for `after()`. Meanwhile the *safe*
`require x || after(t)` is rejected (Lowering.java:794: only `&&` propagates "positive"). As a result
`tutorials/escrow-crowdfund.mdx:90` says that the escrow refund "must be written in Java", but it compiles as
`caller == seller || caller == arbiter || validFrom >= deadline` (`ports/contracts/ports/Escrow.jsc`).

## 4. Gap analysis: ports of Java examples

| Example | Portable? | Built (npm) vs Java (`reference/examples.mdx:388-412`) | Notes |
|---|---|---|---|
| vault | yes | 6,335 B vs 6,273 B | init must be named `init` (Java `open`) |
| escrow | yes, via the `validFrom` workaround | 5,955 B vs 5,688 B | `after()` under `\|\|` is rejected |
| crowdfund | yes, with changed storage | 13,008 B vs 7,976 B | there is no inline map, so a MerkleMap is forced: +63%, plus the map verifier, proofs and mirrors |
| token | yes | 12,303 B vs 11,718 B | identical shape to Points |
| pool | yes | 9,859 B vs 9,586 B | `payable batched only open` |
| bank | **no** | – | `@Owned` Account, `@OwnedInit/Action/Destroy`, `@Shared` read |
| market | **no** | – | owned, `Address` field, `Assets`/`Funds`/`assetSlots`, `Sends` with inline datum, `@Into`/`@Admits`, helper functions, `Builtins.constrData` |
| registry | **no** | – | keyed owned objects (`key`, `signedKey`, `retire`), `JulcList<byte[]>` field, `@Quorum`, `@Invariant` |
| treasury/oracle/fees/staking/upgrade | **no** | – | `@Params`, `@AuthorizedBy`, `@Reads`/Compose, purposes, migration |

Tutorial coverage: counter, vault, escrow/crowdfund, token and pool are expressible. bank, dex, multisig-treasury,
oracle-composition, staked-vault and upgrades are not.

### Prioritized missing constructs

**P0: needed before ".jsc is recommended for JS devs"**

| # | Construct | Syntax suggestion | Lowering | Risk/effort |
|---|---|---|---|---|
| 1 | Conditional expression | `let fee = big ? a : b` (or `if c { a } else { b }` as an expression) | Java `?:` with both sides typed. JuLC compiles `?:` lazily, which ADR-0005 D3 already relies on | Low |
| 2 | `const` alias; mutable locals `var` | `const x = …` (immutable, as `let`); `var acc = 0; acc += 1` | `var` reuses the SSA machinery of fields (Env.fields → Env.vars) and is carried through `IfArmK` records | Low to medium |
| 3 | Pure helper functions | `fn fee(p: Int, bps: Int): Int = p * bps / 10000` and block bodies ending in `return e` | Each becomes a `static` Java method in the class. The body is lowered by the same SSA/arm code; no state writes, no `pay`; `require` allowed; non-recursive in v2 | Medium |
| 4 | Bytes, hash and signature built-ins | `len(b)`, `b ++ c`, `slice(b, i, n)`, `take/drop`, `sha2_256`, `blake2b_256/224`, `keccak_256`, `verifyEd25519(k, m, s)`, `bytesToInt`, `intToBytes`, Bytes `<` | `ByteStringLib`/`CryptoLib`. `long` results should go through a BigInteger wrapper in julc-state-api so SubsetLint passes | Low (lint risk) |
| 5 | Sound time predicates | Allow `after()`/`before()` anywhere monotone-positive in a `require` (`\|\|` included). Reject or warn on `validFrom`/`validTo` in `if` conditions or under `!` | Propagate `positive` through `\|\|` (Lowering.java:792-796). Add a raw time-read check | Low; it closes a safety hole |
| 6 | Diagnostics in the npm path | `file.jsc:L:C` | Toolchain.java:104 drops `e.line`/`e.col` (passes -1). :102 uses `JscMain.lower`, so warnings are lost (verified: the destroy warning is not printed). Processor and JuLC errors point at a phantom `/p/P.java:13` (verified with `minAda = 1`). Map them back through the `// File.jsc:line` comments, and write the generated Java to `build/joust/generated/` | Low |
| 7 | Targeted parse errors | `loops are not in .jsc yet …`, `functions: use fn …`, `use let/const`, `/* */` support | Keywords `while for fn return match import const` plus `?`, `===`, `/*` in Parser.stmt and member | Low |
| 8 | Template and positioning | `npm create joust -- --lang jsc` (or `.jsc` as the default, as ADR-0015:306 already states) | `js/create-joust/template/contracts/app/Counter.jsc` (the landing page already has it, `samples.mjs:35`). README:12 says "no other options" | Low |
| 9 | Editor support | TextMate grammar and VS Code extension; a Shiki grammar for the docs (today `jsc` is aliased to Kotlin, `www/astro.config.mjs:27`) | – | Low |
| 10 | `--traces` on the npm CLI | `joust build --traces` | `bin/joust.js:26` passes only src/out; `build.js:34` takes `options` | Low |

**P1: feature parity for common dApps**

| # | Construct | Syntax | Lowering | Risk/effort |
|---|---|---|---|---|
| 1 | Records/structs | `struct Line { to: Key, amount: Int }`; `l.amount` | Java records (non-generic). A typed local before each accessor (CLAUDE.md gotcha) | Medium |
| 2 | Lists + bounded fold | `List<Key>`, `for g in guardians { if g == caller { found = true } }`, `len(xs)`, `xs.contains(x)` | A generated recursive static helper `loopK(JulcList<T>, LoopK acc)` with `stepK` from the existing arm machinery. This avoids JuLC's accumulator rules (Value-accumulator gotcha). No `while` | Medium to high |
| 3 | Owned objects | `owned Account { owner: Key, balance: Int }`, `owned init open(shared bank)`, `owned action …`, `owned destroy …` | `@Owned` record and `@OwnedInit/Action/Destroy` with a `@Shared State` parameter | High |
| 4 | Native assets, mint, send | `received(policy, name)`, `pay 1 of policy.name to k`, `mint n of name`, `send v to addr with datum …`, option `assetSlots` | `Assets`, `Funds`, `Sends`, `Datum.inline`. Datums are built with `Builtins.constrData` | High |
| 5 | `Address` type | `Address`, `wallet(key)`, `script(hash)` | `new Address(new Credential.PubKeyCredential(...))` | Medium |
| 6 | Params, invariants, events | `params { feeBps: Int }`; `invariant tier = e.tier <= 3`; `event Sold(price: Int)` + `emit` | `@Params` (typed local), `@Invariant` static, a result `events` component | Medium each |
| 7 | More map shapes | `Map<Bytes, Int>`, `Set<Key>`, `Map<Key, Struct>`, `SmallMap<K, V>` (inline, sorted) | MerkleMap codecs; `JulcMap` + `SortedMaps` for inline maps | Medium |
| 8 | Option and enums with `match` | `Option<Key>`, `enum Phase { Open, Closed }`, `match p { Open => …, Closed => … }` | Sealed interfaces and records; switch patterns (no enums in bundled sources, CLAUDE.md) | Medium to high |
| 9 | Offline JS tests | `joust test` / `import { simulate } from '@julclang/joust'`: run calls against an in-memory ledger, plus the attack suite | The engine already evaluates transitions; it needs a chain stub (not verified) | High |
| 10 | Reference interpreter / differential testing | – | ADR-0005 §5 Q4. More users means more exposure to miscompiles (ADR-0005 §3) | Medium |

**P2:** keyed objects (ADR-0016), `@Quorum`/`@AuthorizedBy`, script purposes (staking and governance),
Compose/Reads/deposits, migration/upgrades, batched + Map, `while` and recursive helpers, an LSP, column-accurate
source maps, `import` of shared `.jsc` libraries, a `fail "msg"` statement, the call-style alias `require(c, "m")`,
decimal `1.5 ada`, literals over 63 bits, and string escapes.

## 5. Developer-experience assessment

- **Errors:** the Gradle path prints `file:line:col: error: …` (JscException.format). The npm path prints
  `error: p/P.jsc: expected '=', '+=' or '-=' but found 'n'` with no position. The CLI does exit 1. Warnings are
  dropped. Downstream errors and warnings cite generated Java that the user never sees (`warning: /p/P07.java:13: …`).
  The documentation admits the position loss (`jsc-reference.mdx:380-383`). Error messages for JS-shaped code are
  generic (see §3).
- **Editor:** nothing exists: no grammar, extension or LSP (ADR-0015 §11 line 471 lists it as out of scope).
- **Docs:** good and accurate. `jsc.mdx` (589 lines) and `jsc-reference.mdx` (402 lines, a full error catalogue) match
  the code. One stale claim: escrow-crowdfund.mdx:90. `typescript.mdx` and `quick-start-npm` use the Java Counter.
- **Tests:** the npm projects can only test end to end on DevKit (`template/test/counter.test.js`). The JVM
  `StateHarness`/`Attacks`/`DslTest` levels are not available to npm users. `require` messages do come back to JS
  (`assert.rejects(..., /increment must be positive/)`).
- **Debugging:** each generated statement carries a `// File.jsc:line` comment, and generated checks carry
  `File.jsc:L` messages. `-Pjoust.traces` exists on Gradle only. The npm CLI has no traces flag, and generated Java is
  not written to disk on the npm path, so users cannot inspect it.
- **Unverified:** current mainnet ExUnits limits; whether the runtime engine can evaluate calls without a chain (for
  P1-9); the Wasm engine (built 2026-11-09) matches the current DSL sources, but I did not diff the bytes.
