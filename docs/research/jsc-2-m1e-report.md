# `.jsc` 2, milestone M1e: report (ADR-0018 r12)

Date 2026-11-13. Branch `feat/jsc-2-m1e`, stacked on `feat/jsc-2-m1a` (`dfa7025`). JuLC `v0.1.0-pre21-rc1`, JDK 25,
GraalVM 25.3.4.1, Node 22.12.0, macOS arm64. Two fixes found while reviewing ADR-0021 (#66, the language server):
design F4 and Q2, security S2 and S5. Every number was measured at this branch's head, by the test named with it.

## 1. On-chain messages hold no layout and no names (D12, J20)

**Before:** a generated check's message was `File.jsc:<line>: …`, some with a parameter's name
(`k must hold 28-byte key hashes`). A blank line after `package`, a renamed `Key` parameter or a renamed file changed
the script hash.

**The rule** (ADR-0018 D12, r12): layout, comments, the file's name, line and column numbers and names never reach
the script; behaviour, the author's `require` text, ABI facts and the source order of checks within a method do.

**The key scheme:** `#<kind>:<redeemer>.<n>`.

| Kind | Meaning | `n` |
|---|---|---|
| `#input:spend2.1` | a checked input (D5) of the third spend constructor failed | the redeemer field, as the ABI manifest numbers it (an init's `mint0` fields start with the seed) |
| `#pay:spend3.1` | a payout's amount was negative | the pay statement's ordinal in its method, source order from 1 |

The `require`s that only keep an `if`/`for`/`match` statement's arm helper (they cannot fail) carry the empty message.
`fn`s have no keys: their failures trace the author's text or an operation's fixed message (`take: a negative
count`), and the message map lists every site of each. An author's message may not take the key's form (JSC0430).

**The message map:** the source map, format 2, adds `messages`: every message the script can trace, with its `.jsc`
sites (line, column, kind, method or `fn`, the parameter of an input check). Written beside the Java on both paths
and into `contracts.json`; `@julclang/joust-runtime` appends the sites to a refusal
(`#pay:spend1.2 [Pay.jsc:9:17 (a payout in split is negative)]`); JVM tooling uses `JscV2.Lowered.explain`.

**Hashes and sizes** (`JscV2HashStabilityTest`; before: the same build at `dfa7025`):

| Contract | Before | After | Bytes before → after |
|---|---|---|---|
| `Counter2` | `7baf4785…` | `0b1c24bc…` | 5,760 → 5,729 (−31) |
| `Ledger` | `927e1a0e…` | `03c48c00…` | 14,871 → 14,633 (−238) |
| `Pledges` | `4b2d347a…` | `694bf21f…` | 8,454 → 8,228 (−226) |
| `Shelf` | `46116d3b…` | `98407f75…` | 12,783 → 12,755 (−28) |
| `Vectors` | `93dcd7b2…` | `8fe9538b…` | 7,883 → 7,823 (−60) |
| `Branches` (new in review r1) | — | `820bcd4e…` | 6,260 |
| `Bounds`, `StdBytes`, `StdMath` | `32046198…`, `fa6154c2…`, `002d363a…` | unchanged | 9,973, 7,385, 8,150 (no generated check) |

Full hashes after: Counter2 `0b1c24bc085fb7dedf6b1b42201796c1de5cba6db407da7cdd8785cc`, Ledger
`03c48c008a7e711c51fad54dcb6410f335f7fbf6bc91e3c9415a180e`, Pledges
`694bf21f9ac7dede5d06ff338e0ebb7c8a1addb339ee7c72b0ba5ccc`, Shelf
`98407f75af30f9ba7aabea0d8aa23ae29467e933a015a690f8eaa33b`, Vectors
`8fe9538bbd7f9bbbce83fcf50272cefce0dacc303795c28d105c4eca`. The ABI manifests did not change (`checkJscAbi`: 8 agree).

**Invariance** (`JscV2HashStabilityTest`, each of the 8 examples built by the toolchain): a blank line after
`package`, after every `{` and before the last `}`; re-indentation; CRLF; comments added and removed; the file
renamed; every local, lambda, loop and match variable, `fn`, parameter and method renamed: the hash is unchanged in all
72 builds. A flipped `require` comparison changes it in every example. Swapping Pledges' two `fn`s leaves it unchanged
(a fact of JuLC, asserted as an alarm, not promised). `JscV2MessagesTest`: no message names a file; every generated key
is written once and maps to one site; every traced message is in the map.

## 2. The nesting cap (J18)

**Before:** 1,000 nested parentheses overflowed the parser (`StackOverflowError`, not caught); a 5,000-term sum
overflowed the checker; a 1,000-branch else-if chain overflowed the emitter; the Web Image threw an opaque `TypeError`
at about 250 parentheses.

**Now** (`JscV2DepthTest`, `JscV2DepthBuildTest`, `depth-in-node.mjs`):

- **64 levels** on both paths. The parser counts each parenthesis, block, argument, arm, lambda and type argument
  (JSC0149 at the token past the cap); `Depth` measures the built tree iteratively, where unbalanced chains (`/`, `%`,
  `??`, `==`, field reads, method calls, `[ ]`, short value else-if chains) count a level per link (JSC0150); types
  contain types at most 64 deep (JSC0340). The Web Image's limit with a 1 MB worker stack is 232 levels (ADR-0021 r3
  §1), and Node's default stack is about 1 MB: 64 leaves a margin of more than three.
- **Chains of any length:** `+`/`-`, `*`, `&&`, `||` chains are built balanced (up to 8 operands as written; longer
  ones split in the middle, signs flipped in a right part split at `-`; the interpreter checks the value of a
  5,000-term mixed sum). An else-if chain is one node in the syntax tree and the IR, checked, interpreted and lowered in
  loops, one helper per branch. `fn`s reached with time arguments are analysed from a work list; the type-cycle check
  is a work list too.
- **Safety net:** `JscMain` and `Toolchain.build` report any `Error` from the front end as JSC0005 at the file
  (tested with the front end on a 96 KB thread stack); `Toolchain.build` turns a `StackOverflowError` past the front
  end into a diagnostic; `joust build` reports an engine failure instead of throwing.

Each Web Image case runs on a **cold** engine, the first build in a fresh Node process, as `joust build` meets it.
"Main thread" is Node's default stack (about 1 MB). "Worker" is `joust build`'s 8 MB worker (since review r1).

| Case | JVM | Web Image, cold: main thread / worker |
|---|---|---|
| 56 nested parentheses; blocks 25; lambdas 12; match 12; value ifs 20 | ok | ok / ok |
| 1,000 parentheses; blocks, lambdas, match, value ifs 200 | JSC0149 | JSC0149 / JSC0149 |
| a 5,000-term sum; a 3,000-term `&&` | ok | ok / ok |
| 200 else-if statement branches; a recursive chain of 9 | ok | ok / ok |
| `x / 2 / 2 …` 200 links | JSC0150 | JSC0150 / JSC0150 |
| a type chain 100 deep; 4 types × 60 `List` layers | JSC0340 | JSC0340 / JSC0340 |
| 1,000 statement branches; 4 chains × 100; 300 and 1,000 value branches | ok (3.6 s, 1.2 s, 0.4 s, 2.2 s) | worker: ok (11.9 s, 4.7 s, 2.1 s, 8.6 s) |

**JuLC's limit in the Web Image** (bloxbean/julc#327). The front end handles any chain length, but JuLC's own PIR
passes (`NarrowingElisionPass`, `PirHelpers.mapChildren`) recurse on the compiled term.

On Node's main thread, a **cold** engine fails at about 270–300 else-if branches (statement or value), or four chains
of 70, some of them under 16 KB. A warm engine goes further. Round r1's first figures ("1,000 value branches") were
measured warm and were wrong.

`joust build` now runs the engine on a worker with an 8 MB stack. There, a cold engine builds 2,000 statement branches
(83,925 B, 40 s), so the npm and Gradle outcomes agree. The engine's failure, if it still happens, is a diagnostic.

## ADR-0021 §3a

ADR-0021 (#66) §3a asks ADR-0018 for two M1e items, both in r12:

- **Item 2 (messages):** name-free keys, unique within a script (J20, tested), a map from each key back to
  `File.jsc:line:col` (the source map's `messages`), kept with the script in `contracts.json` for audits. Preview
  deployments: none exist; D16's rule (rebuild with the recording compiler) is stated in D12.
- **Item 3 (nesting):** one catalogued cap (64), identical on the JVM and the Web Image, with a margin of more than
  three below the smallest measured stack (232 levels at 1 MB); operator and else-if chains handled iteratively. It is
  tested on a default-stack Node.

Items 1 (§8's gate), 5 (per-contract ABI acceptance) and 6 (D13) are not M1e's.

## Review round r1 (#71)

Every finding has a regression test.

- **Attribution** (Codex C3, C4; security S1, S2; design F5): a trace is matched whole (equal, or after `": "`), and
  the longest match wins. It is looked up only in the refusing contract's map: the engine names it in a composition
  (`refusedBy`), and each map in `contracts.json` carries its script's hash. JSC0430 refuses `#word:` anywhere in an
  author's message. The JVM, the runtime and JS share the rule's vectors
  (`julc-state-dsl/src/test/resources/jsc2-message-matching.json`). `BuiltContract` has `messages?`.
- **A complete map** (C5, F3): each std adapter's messages, and `MerkleMaps.putInt`'s, are recorded at the call sites.
  `JscV2M1eR2BuildTest` checks every string constant of each example's linked validator and transition programs
  against its map; `pow(2, -1)`'s actual trace is explained at its site.
- **Type depth** (C2, S3): every layer counts, at declarations, state fields and written types.
- **Recursion** (C1, F6, S5): a recursive value chain stays inline at any length (Codex's 9- and 12-branch cases).
  A recursive call in a condition the lowering puts in a helper is JSC0601 at the call.
- **The package** (F1): the one exception, from JuLC's sensitivity to import order (bloxbean/julc#328). It is stated in
  D12 and J20 and asserted.
- **Cold engine** (F2, S4): see above; `js/joust/test/cold-engine.test.js` builds 300 branches (14,925 B) and four
  chains of 100 in a fresh process.
- **The JVM path** (F4): the map is a resource beside the contract's classes (the plugin and `examples/dsl`).
  `JscMessages` appends the sites to a `RequireException` the contract method raises in `StateInstance` and in init.
- **Tests** (F7): `Branches.jsc` holds a paying statement chain, a 12-branch value chain, a recursive 9-branch chain
  and a `pow` refusal, on four legs (48 calls). The rename edit now renames every declared name in reversed
  alphabetical order. A package edit is asserted.

## Tests run at this head

See the PR description for the commands and their output.
