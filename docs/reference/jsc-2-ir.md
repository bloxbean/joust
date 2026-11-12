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
semantics document. A defect of the checker that changes the IR is caught elsewhere, by the lowering's own legs (JVM,
transition program, validator): all of them, and both interpreters, read the same checked program.

## Stability

`"ir"` is the format version. Version 1 is described here. A change to a node's fields or meaning gets a new version,
and readers refuse versions they do not know (`IrJson.read`, and the JavaScript `load`). Output is deterministic:
fields in a fixed order, sets sorted, and each local's id is the checker's sequence number, unique in a program. The
export is lossless. `JscV2IrTest` reads every example back, writes it again and gets the same text. It also lowers
the program read back to the same Java and the same ABI manifest, and checks that the examples use every node.

## Shape

```json
{
  "ir": 1,
  "language": "jsc 2",
  "package": "org.example",
  "contract": "Counter2",
  "source": "Counter2.jsc",
  "options": [{ "name": "maxDatumBytes", "value": "128" }],
  "records": [Record…],
  "enums": [{ "name": "Phase", "variants": [Record…], "at": [3, 1] }],
  "state": Record,
  "timeFields": ["lastSeen"],
  "recursiveFns": ["fact"],
  "fns": [{ "name", "params": [Local…], "result": Type, "body": Block, "at" }],
  "methods": [{ "kind": "init|action|destroy|view", "name", "params": [Local…], "mods": [{ "name", "field"? }],
                "checked": [localId…], "usesCtx", "pays", "result": Type|null, "body": Block, "at" }],
  "warnings": [{ "line", "col", "code", "message" }]
}
```

- **Record:** `{ "name", "fields": [{ "name", "type": Type }], "at" }`, with the fields in declaration order. An enum's
  variants are records in declaration order, so a variant's tag is its index.
- **Type:** `"Int"`, `"Bool"`, `"Bytes"`, `"Key"`, `"Hash"`, `{ "list": T }`, `{ "option": T }`, `{ "record": name }`
  (`"State"` for the state), `{ "enum": name }`, `{ "map": [K, V] }`, `{ "bigmap": [K, V] }`.
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
  | `If` | `cond`, `then` (Block), `otherwise` (Block or null), `pays` |
  | `For` | `var` (Local), `list`, `body` (Block), `pays` |
  | `Match` | `scrutinee`, `arms`, `pays` |

- **Arm:** `{ "ctors": [name…], "fields": [name…], "binds": [Local…], "body": Block, "at" }`. `ctors` lists the
  variants it covers (`Some`/`None` for an option), and `fields[i]` is the field that `binds[i]` takes (`value` for
  `Some(x)`).
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
  | `Logic` | `op` (`&& \|\|`), `left`, `right` | | `IfValue` | `cond`, `then`, `otherwise` (Blocks) |

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

A call is `{ "method", "state": v|null, "args": [v…], "tx": { "caller", "received", "balance", "validFrom",
"validTo" }|null }`, with `caller` as hex and the other fields as decimal strings. An answer is `{ "refused": message }`
or `{ "ok": { "value": v|null, "payouts": [{ "to": hex, "lovelace": "n" }…] } }`.
