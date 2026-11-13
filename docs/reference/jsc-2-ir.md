# `.jsc` 2 IR export (format 1)

The checker's typed IR of a `.jsc` 2 contract, as JSON (ADR-0018 D15, M1b). The JavaScript reference interpreter
(`js/jsc-interpreter`) evaluates it. Its meaning is defined in [`jsc-2-semantics.md`](jsc-2-semantics.md).

## Where it is written

Both build paths write `Name.ir.json` beside the generated Java:

- **Gradle:** `generateJsc` (`JscMain`) writes it to `build/generated/sources/jsc/java/main/<package path>/`, beside
  `Name.java` and `Name.jscmap.json`;
- **npm:** `joust build` writes it to `build/joust/generated/<package path>/`. The Web Image engine returns it as
  `generated[i].ir`.

The two copies are equal byte for byte. The Web Image test (`toolchainWasmTest`, `build-in-node.mjs`) compares the
engine's copy with Gradle's. The export is not an ABI and is not committed: it changes whenever the checker's output
changes, and it is rebuilt with the Java. A Java file whose ABI manifest is refused is not written, and neither is its
IR.

## Why an export, not a second front end

The M1a review (F11) left two options: export the IR, or write a second front end in JavaScript. M1b exports it, for
two reasons. First, the checker stays the only place that decides what a program means statically: names, types,
shadowing, effect legality, time taints (D11) and the BigMap flow rules. A second checker would need its own
differential tests for every refusal, which is J5's territory, and the two would drift. Second, D15's independence
concerns evaluation. The JavaScript interpreter shares nothing with the Java code except this file. It has its own
values, arithmetic, equality, map order, checked inputs, standard library, hashes and Ed25519, all written from the
semantics document. The cost: every leg reads the same checked program, so a defect of the checker that changes the IR
reaches every leg alike, and no differential test can see it. The semantics' hand-written vectors and the hand-stated
outcomes of the differential tests guard against that, as they do for M0's Java interpreter (review F17). And the
JavaScript reader recomputes the two checker decisions it can state from the semantics alone, refusing an IR that
disagrees (below).

## Stability

`"ir"` is the format version, exactly the integer 1 here. Readers refuse versions they do not know (`IrJson.read`,
and the JavaScript `load`).

**Version 1 is the format as of the M1 integration** (#76): `If` and `IfValue` hold a `branches` list (M1e's flat
else-if chains). M1b's pre-release shape (`cond`, `then`, `otherwise` on those nodes) was never released and is not
version 1; the readers refuse it ("unknown field"). From M1's release, the rule below applies.

**What a version-1 reader refuses, and so what may change without a new version.** Both readers are strict: a duplicate
key, a number that is not a plain integer, an integer literal that is not decimal, bytes that are not lower-case hex, a
local referenced before it is declared or declared twice. The JavaScript reader also validates the whole program at
load, before anything runs: an unknown node or statement kind, field, operator, modifier name (`payable`, `open`,
`only`, `after`, `before`, `batched`), method kind, `std` row or type is refused there. So a change is **additive** when an old
reader refuses every program that uses it, at load, rather than misreading it: a new node kind, a new modifier (M1c's
`batched`), a new `std` row or a new type, each with fields of its own. **M2a's additions are of this kind**: the types
`Value`, `Address` and `Credential`, the nodes `FundsRef` and `LedgerOp`, the statements `Send`, `Mint` and `Accept`, the
`std/value` and `std/address` rows, and the arms `KeyCred`/`ScriptCred` of a match on a credential; they keep version 1.
**So are M2b's**: the top-level `params` (a Record named `Config`) and `invariants`, each present only when the contract
declares them (so an M1 or M2a export is unchanged), the node `ParamsRef`, the statement `Emit`, and the modifiers
`quorum` (with `keys` and `threshold` instead of `field`) and `authorized`; a version-1 reader that predates them refuses
them at load (an unknown field, node, statement or modifier). The JavaScript reader also refuses a condition or a
boolean operand that is not of type `Bool` (a `Branch`'s, `Require`'s or `Cond`'s condition; `Logic`'s and `Not`'s
operands), and an empty `branches` list. Additive changes keep version 1. A change to
an existing node's fields, to the meaning of an existing node, operator, modifier or row, or to the program's top-level
fields gets a new version.

**Two checker decisions are checked again.** The JavaScript reader recomputes each method's `checked` list (§4 of the
semantics: the parameters, of an init, action or destroy, whose types hold a `Key`, a `Hash` or a `Map` anywhere) and
its `usesCtx` (a destroy; a modifier other than `open`, `only`, `quorum` and `authorized`; or a `CtxRef`, `TimePred` or
`std/time` call in the body), and refuses an IR that disagrees: a checker defect there becomes a refused load, not an agreement of every leg.

**The source it came from.** `sourceSha256` is the SHA-256 of the `.jsc` file's UTF-8 text. A consumer that holds the
source (M1d's `joust check`) should lower it in process or compare the hash, and refuse a stale export.

**Not the language server's model.** The export is the checked program for evaluation. It is not ADR-0021's document
model (its own `modelVersion`, spans and error recovery), which reads the IR in process; the two should not grow around
one file.

Output is deterministic:
fields in a fixed order, sets sorted, and each local's id is the checker's sequence number, unique in a program. The
export is lossless. `JscV2IrTest` reads every example back, writes it again and gets the same text. It also lowers
the program read back to the same Java and the same ABI manifest, and checks that the examples use every node.

## Shape

```json
{
  "ir": 1,
  "language": "jsc 2",
  "sourceSha256": "<64 hex digits>",
  "package": "org.example",
  "contract": "Counter2",
  "source": "Counter2.jsc",
  "options": [{ "name": "maxDatumBytes", "value": "128" }],
  "records": [Record…],
  "enums": [{ "name": "Phase", "variants": [Record…], "at": [3, 1] }],
  "state": Record,
  "params": Record,
  "timeFields": ["lastSeen"],
  "recursiveFns": ["fact"],
  "fns": [{ "name", "params": [Local…], "result": Type, "body": Block, "at" }],
  "methods": [{ "kind": "init|action|destroy|view", "name", "params": [Local…], "mods": [{ "name", "field"? }],
                "checked": [localId…], "usesCtx", "pays", "result": Type|null, "body": Block, "at" }],
  "invariants": [{ "name", "body": Block, "at" }],
  "warnings": [{ "line", "col", "code", "message" }]
}
```

- **Record:** `{ "name", "fields": [{ "name", "type": Type }], "at" }`, with the fields in declaration order. An enum's
  variants are records in declaration order, so a variant's tag is its index.
- **M2b.** `params` (present only when declared) is the params record, named `Config`. The contract's events are the
  enum `Event` in `enums`, its variants in declaration order. `invariants` (present only when declared) are Bools over
  the state (`StateRef`, `StateField`) and the params (`ParamsRef`), checked like a view's body. A modifier is
  `{ "name", "field"? }`; `quorum`'s is `{ "name": "quorum", "keys", "threshold" }`; `authorized`'s `field`, like
  `quorum`'s `keys` and `threshold`, is a state field or `params.f`.
- **Type:** `"Int"`, `"Bool"`, `"Bytes"`, `"Key"`, `"Hash"`, `"Value"`, `"Address"`, `"Credential"` (M2),
  `{ "list": T }`, `{ "option": T }`, `{ "record": name }` (`"State"` for the state), `{ "enum": name }`,
  `{ "map": [K, V] }`, `{ "bigmap": [K, V] }`.
- **Local** (where it is bound): `{ "id", "name", "type", "taint", "at", "fieldTaints"? }`. A reference to it is
  `{ "node": "LocalRef", "local": id, … }`. `fieldTaints` is present only for a value of the state's type: each field's
  taint.
- **`at`:** `[line, column]` in the `.jsc` file, both from 1.
- **Integers** (literals, option values) are decimal strings, since they are unbounded. **Bytes** are lower-case hex
  strings.
- **Block:** `{ "stmts": [Stmt…], "result": Expr|null, "at" }`.
- **Stmt:** `{ "stmt": kind, …, "at" }` with these kinds:

  | `stmt` | Fields |
  |---|---|
  | `Let` | `local` (Local), `value` |
  | `Require` | `cond`, `message` |
  | `Pay` | `to`, `amount` |
  | `Send` (M2) | `to`, `value` |
  | `Mint` (M2) | `burn` (boolean: `burn(…)`), `name`, `qty` |
  | `Accept` (M2) | `value` |
  | `Emit` (M2b) | `event` (an `Event` value) |
  | `If` | `branches` (Branch…, at least one), `otherwise` (Block or null), `pays` |
  | `For` | `var` (Local), `list`, `body` (Block), `pays` |
  | `Match` | `scrutinee`, `arms`, `pays` |

- **Branch:** `{ "cond": Expr, "then": Block, "at" }`, one test of an `if` and its else-if chain (`If`, `IfValue`).
  An else-if chain is one node with a branch per test (ADR-0018 J18, M1e), so its depth does not grow with its length.
- **Arm:** `{ "ctors": [name…], "fields": [name…], "binds": [Local…], "body": Block, "at" }`. `ctors` lists the
  variants it covers (`Some`/`None` for an option, `KeyCred`/`ScriptCred` for a credential), and `fields[i]` is the
  field that `binds[i]` takes (`value` for `Some(x)`; `key` or `hash` for a credential).
- **Expr:** `{ "node": kind, …, "type": Type, "taint": n, "at" }`. `taint` is a bit set: 1 means the value depends on
  time, 2 on the current call's window (D11). Evaluation ignores it. The kinds and their own fields:

  | `node` | Fields | | `node` | Fields |
  |---|---|---|---|---|
  | `Lit` | `value` (string, boolean or hex) | | `ListProp` | `op` (`length isEmpty head sum reverse`), `list` |
  | `LocalRef` | `local` | | `Concat` | `left`, `right` |
  | `StateRef` | | | `Contains` | `list`, `value`, `elem` (Type) |
  | `StateField` | `field` | | `Hof` | `kind` (`any all find filter map`), `list`, `x` (Local), `body` |
  | `CtxRef` | `name` (`caller received balance validFrom validTo`) | | `Fold` | `list`, `init`, `acc`, `x` (Locals), `body` |
  | `NoneLit` | | | `Variant` | `enum`, `variant`, `values` |
  | `SomeOf` | `value` | | `Match` | `scrutinee`, `arms` |
  | `GetField` | `target`, `record`, `field` | | `MapEmpty` | |
  | `New` | `record`, `values` (in field order) | | `MapOp` | `op` (`get has set delete size keys values isEmpty`), `map`, `args` |
  | `CallFn` | `fn`, `args` | | `MapHof` | `kind` (`any all`), `map`, `k`, `v` (Locals), `body` |
  | `CallStd` | `module`, `name`, `args` | | `MapFold` | `map`, `init`, `acc`, `k`, `v` (Locals), `body` |
  | `Prelude` | `op` (`min max abs asKey asHash`), `args` | | `BigEmpty` | |
  | `TimePred` | `after` (boolean), `threshold` | | `BigOp` | `op` (`get has at set delete isEmpty`), `map`, `args` |
  | `ListLit` | `items`, `rest` (or null) | | `Range` | `from`, `to` |
  | `Neg`, `Not` | `operand` | | `ListArg` | `op` (`take drop`), `list`, `arg` |
  | `Arith` | `op` (`+ - * / %`), `left`, `right` | | `Cond` | `cond`, `then`, `otherwise` |
  | `Compare` | `op` (`< <= > >=`), `left`, `right` | | `Coalesce` | `option`, `default` |
  | `Equals` | `negate`, `left`, `right`, `operand` (Type) | | `BlockValue` | `block` |
  | `Logic` | `op` (`&& \|\|`), `left`, `right` | | `IfValue` | `branches`, `otherwise` (Block) |
  | `FundsRef` (M2) | `field` (`received held policy self`) | | `LedgerOp` (M2) | `op`, `args` (below) |
  | `ParamsRef` (M2b) | `field` (of `Config`) | | | |

  `LedgerOp`'s ops and argument counts: `none` 0, `lovelace` 1, `asset` 3 (policy, name, quantity), `add` 2, `sub` 2,
  `lovelaceOf` 1, `of` 3 (value, policy, name), `covers` 2; `keyCred` 1, `scriptCred` 1, `wallet` 1, `walletStaked` 2,
  `script` 1, `scriptStaked` 2, `payment` 1.

A local is always bound before it is referenced in document order. That holds for a method's or `fn`'s parameters
before its body, a `Let`'s local after its value, and lambda and arm bindings after the collection or scrutinee and
before the body. So a reader can resolve ids in one pass.

## Values in vectors

The differential tests pass calls to the JavaScript interpreter as JSON, in a tagged form that needs no types:

| Value | JSON |
|---|---|
| `Int` | `{ "int": "-12" }` |
| `Bool` | `true`, `false` |
| `Bytes`, `Key`, `Hash` | `{ "bytes": "0a0b" }` |
| `List` | `[v…]` |
| `None`, `Some(v)` | `{ "none": true }`, `{ "some": v }` |
| record or variant | `{ "rec": "Name", "fields": [v…] }` (the state is `"State"`) |
| `Map` | `{ "map": [[k, v]…] }`, in the value's own order. An input may be out of order; the checked-input rule refuses it |
| `BigMap` | `{ "bigmap": [[k, v]…] }`: its contents (*now*), in canonical order |
| `Value` (M2) | `{ "value": [[policy, name, "q"]…] }`: policy and name as hex, the quantity as a decimal string, in the ledger's order (semantics §1.3) |
| `Credential` (M2) | `{ "keyCred": hex }`, `{ "scriptCred": hex }` |
| `Address` (M2) | `{ "address": [credential, stake] }`, `stake` `null`, `{ "stakeHash": credential }` or `{ "stakePtr": ["slot", "tx", "cert"] }` |

A call is `{ "method", "state": v|null, "args": [v…], "tx": { "caller", "received", "balance", "validFrom",
"validTo", "signers", "funds"?, "approvers"? }|null, "transition"?, "params"? }`, with `caller` and each of `signers` as hex and the other fields as
decimal strings. `signers` (semantics §2.6) may be left out: the caller alone signs. `funds` (M2) is
`{ "received": Value, "held": Value, "policy": hex, "self": hex }`, for a call that reads funds or moves native assets.
`approvers` (M2b, semantics §2.6) lists the scripts that approve the call, as hex (none when left out); `params` (M2b) is
the `Config` record the contract is deployed with (§2). `"transition": true` runs the
transition alone, without the modifiers (§2.6). An answer is
`{ "refused": message, "class": "kind:detail" }` (semantics §2.5),
`{ "ok": { "value": v|null, "payouts": [{ "to": hex, "lovelace": "n" }…], "sends"?, "mint"?, "accepted"?, "events"? } }`
(M2: the sends `[{ "to": Address, "value": Value }…]`, the net mint and the accepted sum; M2b: the events `[v…]`, each
present only when not empty), or
`{ "limit": message }` when the
interpreter could not finish (semantics §2.4: no answer, a failure of that leg).
