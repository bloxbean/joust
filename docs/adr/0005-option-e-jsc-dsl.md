# ADR-0005: Option E — `.jsc`, a state-contract surface syntax that lowers to Java

**Status:** Superseded by ADR-0018 (r26: `.jsc` v1 and its migration were removed; the language is ADR-0018's `.jsc`).
Before: Accepted with changes (r2). A semantics reviewer and Codex both returned "request changes"; their
findings are resolved in §6 and implemented.
**Date:** 2026-11-07
**Builds on:** ADR-0001 to ADR-0004. Research doc §4 Option E and §6 (one State IR, several front ends).
**Stack:** #1 (A), #2 (JuLC ADR-071), #3 (B), #4 (C), #5 (D), then this.

## 1. Context

A `@StateContract` in Java is readable, but immutability shows through. Every update rebuilds the state record, and
every map write threads a new map value. Solidity developers expect `total += received` and `balances[to] += amt`.
The research doc recommends a surface syntax that **lowers to the same State IR** and reuses everything else. That
is the approach JuLC's JRL took (rules lowered to Java, then compiled).

## 2. Decision

### D1. Lower `.jsc` to a Java `@StateContract`, not to UPLC

`julc-state-dsl` parses and type-checks a `.jsc` file and **emits one Java `@StateContract` class**. Everything after
that is unchanged: the JOUST processor builds the State IR, the validator, the client and the lint, and JuLC compiles.

Consequences:
- **Nothing new is trusted on-chain.** The invariants (I1–I25) come from the processor. A DSL bug can only produce a
  contract that differs from what the author meant, and that contract is still enforced as written.
- **Same JVM/on-chain equivalence.** The generated Java runs on both sides like a hand-written contract.
- **Readable output.** The generated Java is plain, and each statement carries a comment with its `.jsc` line.
  Developers can read it, debug it, or switch to Java at any time.

### D2. Language (v1)

```
package org.example.dsl;

contract Jar(maxDatumBytes = 192) {
    state owner: Key
    state goal: Int
    state total: Int
    state tips: Int

    init(goal: Int) payable {
        owner = caller
        this.goal = goal              // `this.` names a state field a parameter shadows
        total = received
    }

    action tip() payable batched {
        require received >= 1 ada "tip at least 1 ADA"
        total += received
        tips += 1
    }

    action withdraw(amount: Int) only owner {
        require amount > 0 && amount <= balance "bad amount"
        if total < goal {
            require amount * 2 <= balance "before the goal, at most half"
        }
        pay amount to owner
    }

    destroy close() only owner {
        pay balance to owner
    }

    view reached(): Bool = total >= goal
}
```

- **Types:**

  | `.jsc` | Java |
  |---|---|
  | `Int` | `BigInteger` |
  | `Bool` | `boolean` |
  | `Bytes` | `byte[]` |
  | `Key` | `byte[]`, a 28-byte key hash |
  | `Map<Key, Int>` | a `MerkleMap` with integer values (Option B) |

  A `Key` is also `Bytes`, but **`Bytes` is not a `Key`**. Keys come from `caller`, `Key` parameters or fields, or a
  28-byte hex literal. Each `Key` parameter gets a generated check that it is 28 bytes. Otherwise a wrong-length key
  could never sign (`only` would lock the contract), or it would credit a key nobody holds.
- **Members:** `state` fields, in order. One `init`; `action`s; `destroy`s; `view`s (single expressions).
- **Modifiers** map one-to-one onto annotations:

  | Modifier | Annotation |
  |---|---|
  | `payable` | `@Payable` |
  | `only f` | `@SignedBy("f")` |
  | `after f` | `@ValidAfter("f")` |
  | `before f` | `@ValidBefore("f")` |
  | `batched` | `@Batched` |
  | `batched only` | `@Batched(only = true)`. `batched only owner` means `batched` plus `only owner`, so the checker rejects it |
  | `open` | none: it states that anyone may call a method that pays out |

  The checker applies the processor's rules itself and reports them at `.jsc` positions:
  - `only`, `after` and `before` don't apply to `init` or to `batched` actions, and need a `Key` field (`only`) or an
    `Int` field (`after`, `before`);
  - `batched` is for actions, and not with Map state;
  - `payable` doesn't apply to a destroy;
  - views can't read `caller`, `received`, `balance` or time.
- **Statements:**
  - `let x = e`;
  - `f = e`, `f += e`, `f -= e` (on state fields);
  - `m[k] = e`, `m[k] += e`, `m[k] -= e`, `delete m[k]` (on map fields);
  - `require cond "message"`;
  - `pay amount to key`;
  - `if cond { … } else { … }`.
- **Expressions:**
  - literals: integers with an optional `ada` suffix (×1,000,000), `true`, `false`, and hex bytes `#cafe`;
  - names: a bare name is a `let`, else a parameter, else a state field, else a built-in (`caller`, `received`,
    `balance`, `validFrom`, `validTo`). `this.f` is the state field's **current** value, after earlier assignments in
    the method;
  - operators: `+ - * / %` (truncating, the same on both sides), `== != < <= > >=`, `&& || !`;
  - indexing: `m[k]` (an absent key reads as 0);
  - functions: `min`, `max`, `has(m, k)`, `isEmpty(m)`, `after(t)`, `before(t)` (`Clock`). `after` and `before` are
    allowed **only as requirements**: at the top of a `require`, or a conjunct under `&&`. They hold for the whole
    validity window, so `!after(t)` does not mean "before t", and a window that straddles `t` would take the wrong arm
    of an `if`.
- **Precedence**, lowest first: `||`, `&&`, `== !=`, `< <= > >=`, `+ -`, `* / %`, then unary `! -`. Binary operators
  are left-associative, and comparisons don't chain.
- **Statements** need no separator (`;` is optional), and newlines don't matter. A `let` is visible to the end of its
  block. Names can't be redeclared or shadowed, can't contain `__`, and can't be Java keywords or names the generated
  client uses.
- **Paying out needs an audience.** An action or destroy that pays must have `only <field>` or `open`. Otherwise
  anyone could call it, and the body's checks would be the only guard (`action drain(to: Key) { pay balance to to }`).
- **No loops** in v1 and no user-defined records. Both are in the Java contract model, so authors who need them
  write Java.

### D3. Lowering

- **SSA.** Every assignment makes a fresh `final` local: `total += received` becomes
  `BigInteger total_1 = s.total().add(ctx.received());`. Reads use the latest local. The method returns
  `new State(...)` from the final values. This fits JuLC's subset, where locals are immutable.
- **Branches.** Each arm of an `if` becomes a generated static helper. It takes every value live at the `if`
  (parameters, `let`s, current fields, payouts, `ctx`), runs the arm's statements in order with their own SSA locals,
  and returns a generated record `IfArmK` of the fields (and payouts) the arm may assign. The call site binds the
  condition once:
  `IfArmK arm = cond ? m__ifK_then(...) : m__ifK_else(...)`.
  - Only the taken arm runs, on the JVM and on-chain (JuLC compiles `?:` and `||` lazily). A division or a map read in
    an untaken arm never runs. This is tested on-chain (`Guards.jsc`).
  - The code grows linearly with the source; nothing is substituted.
  - An arm that assigns nothing (only `require`s) returns `true`, and the call is wrapped in `Require.that`, so its
    checks always run when the arm is taken.
- **Payouts.** `pay` threads a `JulcList<Payout>` the same way. A negative amount fails with a located message
  (`Jar.jsc:24: a payout cannot be negative`). An amount of **0 is skipped**, because the validator rejects zero
  payouts (I7) and `pay received - price to caller` must work on an exact payment. An action with any `pay` returns a generated
  `Outcome(State state, JulcList<Payout> payouts)`, the result shape A recognises. A `destroy` returns the payouts.
- **Ctx.** The method takes `Ctx ctx` when the body or a modifier needs it (`caller`, `received`, `balance`, time,
  `payable`, `batched`) and always for `destroy`.
- **Maps:**

  | `.jsc` | Java |
  |---|---|
  | `m[k]` | `MerkleMaps.getInt(m, k, BigInteger.ZERO)` |
  | `m[k] = v`, `+=`, `-=` | the new value must be ≥ 0, with a located `Require`. **0 is stored as absence** (`MerkleMaps.remove`), else `putInt` |
  | `delete m[k]` | `MerkleMaps.remove` |
  | `has(m, k)` | `MerkleMaps.contains`, the same as `m[k] != 0` |
  | `isEmpty(m)` | `MerkleMaps.isEmpty`, for a destroy's rules |

  - Map values are non-negative.
  - A read after a write in the same method sees the write: each write threads a new map value.
  - A Map can't be assigned whole, bound to a `let`, passed or returned.
  - Every read and write goes through the proven read set (ADR-0002).
- **Equality** on `Bytes`/`Key` lowers to `ByteStringLib.equals`, never `==` (CLAUDE.md gotcha). On `Int` it lowers to
  `compareTo(...) == 0`.
- **Init.** Every `Bytes`/`Key` field must be assigned on every path (before an `if` or in both its arms). `Int`,
  `Bool` and `Map` fields default to `0`, `false` and empty. Reading a field before it is assigned is an error.
- **Lets must be used.** An unused `let` is an error. JuLC may drop an unused binding on-chain while the JVM still
  evaluates it, so `let x = 1 / d` must not look like a check: use `require`.

### D4. Checking

The checker reports `file:line:col` errors before any Java is emitted. It also warns when a destroy never reads
`balance`, because a destroy must pay out everything. The errors:
- unknown names; type mismatches;
- assigning a parameter, a `let` or a built-in;
- assigning a parameter that shares a state field's name without `this.` (`this.goal = goal`);
- `only`/`after`/`before` naming a field of the wrong type;
- `pay` outside an action or destroy;
- `Map` used anywhere except as a state field.

### D5. Build integration

A plain Gradle `JavaExec` task (`generateJsc`) runs `org.julclang.state.dsl.JscMain` on `src/main/jsc`. The task has:
- its own `jsc` configuration as its classpath;
- declared inputs and outputs;
- an output directory `build/generated/sources/jsc` that is registered as a `main` source directory built by the task,
  so `compileJava` depends on it.

Each run:
- empties the output directory first, so a deleted or renamed `.jsc` leaves no stale contract that would still get a
  validator;
- rejects two files that generate the same class.

The processor path and the `onchainSources` directory dependency stay as ADR-0001 D17 requires. The output is
ordinary source, so the processor treats it like hand-written code. A Gradle plugin can come later.

## 3. Consequences

**Positive:** Solidity-like mutation and very little ceremony. The trusted base doesn't grow. The output stays inspectable.

**Limits:**
- **A miscompile is the author's security bug.** The trusted base doesn't grow, but a wrong lowering produces a
  contract the validator then enforces faithfully. The checker's rules and the tests in §4 and §6 are the defence. A
  reference interpreter for differential testing is listed in §5.
- `balance` in a `batched` action is the balance at that point of the batch, so it depends on intent order. In a
  destroy it includes the deposit.
- A v1 subset: no loops, records, owned objects or maps other than `Map<Key, Int>`.
- Two error layers: the DSL checker first, then the processor's diagnostics on generated code. Comments carry the
  `.jsc` line.
- Each arm helper takes every live value as a parameter, which costs a little more than hand-written Java.

## 4. Example

`examples/dsl`:
- `Jar.jsc` (A + D): tips with `batched`, an owner's withdraw with a payout, and a close;
- `Points.jsc` (B): a `Map<Key, Int>` of points with award, spend (with `delete` in a branch) and a view.

- `Guards.jsc`: a semantics fixture. An untaken arm, and the right side of a false `||`, divide by zero and must not
  run.

`DslTest` checks the generated Java against outcomes stated in the test, independently of the lowering:
- before and after the goal;
- a self-transfer reading its own debit;
- spending everything removes the holder;
- a short `Key` is rejected;
- laziness.

It also evaluates the generated validators on-chain with the attack suite. `JscCheckerTest` has 14 tests covering
every checker rule, linear growth over ten sequential `if`s, and that byte equality never uses `==`. `DslDevnetTest`
runs Jar (intents and a batch included) and Points on Yaci DevKit.

Measured, the generated contracts cost about the same as hand-written ones:

| Call | CPU | Memory |
|---|---|---|
| Jar `tip` | 194M | 651k |
| Jar `withdraw` | 236M | 796k |
| Points `give` (spend + verifier) | 328M + 392M | 1.11M + 1.49M |

The scripts are 11 KB (Jar) and 14 KB (Points).

## 5. Open questions

1. Owned objects (`owned record`) and loops over bounded lists.
2. A Gradle plugin; IDE support (a TextMate grammar) for `.jsc` files.
3. Full source maps from the processor's diagnostics on generated code back to `.jsc` columns. Today each generated
   statement carries a `// File.jsc:line` comment, and the checker catches what the processor would.
4. A reference interpreter for `.jsc`, to differentially test the lowering on generated inputs.

## 6. Review r1 and its resolution

| Finding | Source | Resolution |
|---|---|---|
| **High.** Branch substitution blows up exponentially, or (if hoisted) evaluates untaken arms; it drops unused lets and re-resolves predicates | Semantics 1, Codex 1 | Arms are generated helpers returning a record (D3): lazy, linear, in source order, with the condition bound once. An unused `let` is an error. Tests: `untakenArmsAndShortCircuits_doNotRun_onTheJvmOrOnChain` (on-chain), `branchesBecomeArmHelpers_sizeLinear` |
| **High.** Laziness of `\|\|` and `?:` assumed | Semantics 2 | Stated as a requirement and tested on-chain (`Guards.jsc`) |
| **High.** `after`/`before` in conditions mean the wrong thing | Semantics 3 | Allowed only as requirements (top of `require` or `&&` conjuncts). Test `timePredicatesOnlyAsRequirements` |
| **High.** `pay` without authorisation drains the contract | Semantics 4 | A paying action or destroy needs `only` or an explicit `open`. Test `payoutsNeedAnExplicitAudience` |
| **High.** `Map<Key, Int>` vs negative values; zero vs absence; whole-map assignment | Codex 2, Semantics 5 | Values ≥ 0 with located checks; 0 is absence; whole-map assignment, `let` and parameters rejected. Test `mapWritesOfZeroRemoveTheEntry_andCannotGoNegative` |
| `Key` is just `byte[]` | Semantics 6, Codex 3 | `Bytes` is not a `Key`; `Key` parameters are checked to be 28 bytes. Test in `points_awardGiveSpend` |
| Shadowing contradiction; grammar incomplete; generated-name hygiene | Semantics 7, Codex 4 | D2 defines resolution, `this.`, precedence, separators, scope and reserved names. Generated names use `__`, which user names can't contain |
| The trust claim understates the risk | Semantics 8 | §3 states it. Independent-expectation tests in `DslTest`; a reference interpreter is open question 4 |
| `pay 0` aborts | Semantics 9 | 0 is skipped and a negative amount fails with a located message |
| Legality matrix (views/Ctx, batched/maps, init policies, batched policies) | Codex 3 | The checker enforces each rule at `.jsc` positions. Test `modifierLegality` |
| `isEmpty` missing | Codex 5 | Added. `Points.close` uses it |
| `balance` meaning; destroy must pay everything | Semantics 10–11 | Documented (§3); warning when a destroy never reads `balance` |
| Division and remainder | Semantics 12 | `/` is `divide`, which is `QuotientInteger`; `%` is `remainder`, which is `RemainderInteger`. Both truncate on both sides. Division by zero fails on both sides |
| Diagnostics mapping | Codex 6 | Located messages in generated checks and per-statement comments; full source maps are open question 3 |
| Build wiring | Semantics 13, Codex 7 | D5: task inputs and outputs, the dependency through the source set, a clean output directory, duplicate-class detection, a separate classpath |
| Acceptance tests don't establish correctness | Codex 8 | `DslTest` states expected outcomes independently. The on-chain laziness, self-transfer and sign checks are covered |
