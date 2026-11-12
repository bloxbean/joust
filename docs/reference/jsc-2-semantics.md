# `.jsc` 2 semantics

What a checked `.jsc` 2 program evaluates to (ADR-0018 D15), for the constructs of milestone M1 (M0, M1a, and M1c's
modifiers and batched actions). It is
written so that an interpreter can be built from it alone. The JavaScript reference interpreter
(`js/jsc-interpreter`) is built from this document, and its tests hold the hand-written vectors cited here
(`test/semantics.test.js`). Five implementations must agree on every outcome defined here (J3): the JavaScript
interpreter, the Java reference interpreter (`v2/Interpreter.java`, M0), the generated Java on the JVM, the UPLC
transition program and the validator.

**Scope.** This document defines the evaluation of a *checked* program, that is, of the IR that the checker writes
(`Name.ir.json`, format in [`jsc-2-ir.md`](jsc-2-ir.md)). The static rules belong to the checker and the ADR: names,
types, shadowing, effect legality, the time rule (D11) and the BigMap flow rules (D7). They are not repeated here,
except where a rule is what makes an evaluation well defined. Every node name below (`Arith`, `MapOp`, …) is the IR's.
The encodings of values as Plutus data (D5) are in the ADR and in the ABI manifest. They are not part of evaluation.

**Not yet covered:** `Value`, `Address`, `Credential`, `send`, `mint`, `deposit`, `accept`, `emit`, `params` and
other contracts (M2), owned and keyed objects (M3), libraries (M4). A `batched` action (M1c) is covered: its transition
is an ordinary action's, the same in a batch and in a direct call; batch execution itself (the batcher's order, the
fold over intents, refunds) is the validator's and the batcher's, not a call's.

## 1. Values

| Type | Values | Notes |
|---|---|---|
| `Int` | every mathematical integer | unbounded on every leg; no overflow anywhere |
| `Bool` | `true`, `false` | compared by value, never by identity (M0 review C11) |
| `Bytes` | finite sequences of octets, of any length | compared by content, never by identity |
| `Key`, `Hash` | `Bytes` | of 28 bytes wherever they cross a boundary (§4); the same value as `Bytes` at run time |
| `List<T>` | finite sequences of `T` | immutable |
| `Option<T>` | `None`, or `Some(v)` with `v : T` | never `Some(None)`: `T` is never an `Option` (D5) |
| record `R`, `State` | a tuple of field values, in the declaration order of `R`'s fields | two records with the same fields are distinct types |
| enum `E` | a variant `V` of `E` and a tuple of `V`'s field values | a variant's *tag* is its index in `E`'s declaration, from 0 |
| `Map<K, V>` | a finite sequence of entries `(k, v)`, keys unique and in canonical order | §1.1 |
| `BigMap<K, Int>`, `BigMap<K, Bytes>` | a triple *(before, now, written)* | §1.2; a state field only |

Two internal values have no type of their own: the **range** `range(a, b)` (§3.6) and the **payout** `(to, lovelace)`
(§2.3).

### 1.1 Canonical key order

Map keys are byte strings. Key `a` comes before key `b` when `a` is shorter, or when they are equally long and `a` is
smaller at the first byte where they differ (bytes compared unsigned, 0 to 255). For keys of at most 64 bytes this is
canonical CBOR's order of the encoded keys, the order in which cardano-client-lib writes an inline map (F19). A map value
always holds its keys strictly ascending in this order. A map that arrives from outside in another order is refused by
the checked-input rule (§4); it never reaches the body.

This is **not** the order of `<` on `Bytes` (§3.3), which is lexicographic: `#ff < #0000` is false, but `#ff` comes
before `#0000` in a map.

### 1.2 BigMaps

A BigMap value is *(before, now, written)*. *before* and *now* are finite maps from byte strings to values, and
*written* is a Boolean. The state's BigMap field starts as *(c, c, false)*, where *c* is the map's contents in the
input state. `BigMap.empty()` is *({}, {}, false)*. A write (§3.8) changes *now* and sets *written*. It keeps *before*.
In a `BigMap<K, Int>`, no entry of *now* holds 0, because 0 means absence. A BigMap is never compared, bound, passed or
captured (D7, a static rule), so its value is visible only through the operations in §3.8 and in the next state.

## 2. Calls

A call evaluates one method. Its inputs are the method's name, the input state σ (a `State` record, absent for an
`init`), the arguments in parameter order, and the transaction context τ. τ is absent for a view, and for a method whose
IR says `usesCtx: false` and that has no `only f` modifier, which then never reads it. τ holds six values:

| Name | Type | Meaning |
|---|---|---|
| `caller` | `Key` | the key that signed the call (an authenticated sender) |
| `received` | `Int` | the lovelace this call pays into the instance |
| `balance` | `Int` | the lovelace the call may allocate (for a destroy: everything the instance holds) |
| `validFrom` | `Int` | the start of the transaction's validity window (POSIX ms) |
| `validTo` | `Int` | its end |
| `signers` | `List<Key>` | the keys that signed the transaction, the caller among them (read only by `only f`, §2.6; the caller alone when a vector does not say) |

**The outcome** is one of the following:

| Kind | Outcome |
|---|---|
| `init` | the first state |
| `action` | the next state and the payouts |
| `destroy` | the payouts |
| `view` | the view's value |
| any | **refused** |

A call is *refused* when evaluation *fails* anywhere: a `require` that does not hold, a checked input that is not well
formed, or an operation outside its domain. Every failure has the same outcome on-chain. A failure also has a *class*
(§2.5), which the two interpreters must agree on, and a message, which is prose: no two legs need to agree on messages.
A failure stops the call. Nothing a refused call evaluated is kept, its payouts included. An implementation that cannot
finish a call (its stack runs out) gives **no answer**, which is not a refusal (§2.4).

**Steps of a call:**

1. Bind each parameter to its argument.
2. **Modifiers** (§2.6), in source order: a broken one fails the call.
3. **Checked inputs** (§4). For each parameter listed in the method's `checked` list, in order, the argument must be
   well formed at the parameter's type; otherwise the call fails. This happens before any statement runs.
4. Run the body's statements in order (§2.2).
5. Evaluate the body's result expression, if any: the next state (init, action) or the value (view). A destroy has no
   result.
6. The outcome is that value with the payouts collected in step 4, in the order they were made.

**The modifiers are preconditions of a call** (§2.6): `only f`, `after f` and `before f` are evaluated against τ before
the body. `payable`, `open` and `batched` refuse nothing in a call. **Other transaction-level rules are not evaluated by
a call:** everything about the payouts as a whole (whether they total more than τ.`balance`, what a destroy leaves
over, the ledger's minimum per output) and a batch's own rules. The validator leg checks them with the rest of the
transaction.

**Names.** Records, enum variants and the state (`State`) share one namespace in a file (D5, M1a decision #3), so a
record or variant value is identified by its name alone, as both interpreters and the vectors' `{"rec": name}` do.

### 2.1 Evaluation order and laziness

Evaluation is **strict, left to right, receiver first**, with these exceptions. Each is a *lazy position*: its operand
is evaluated only if the rule says so (D6).

| Construct | Lazy part |
|---|---|
| `a && b` | `b`, only when `a` is `true` |
| `a \|\| b` | `b`, only when `a` is `false` |
| `c ? a : b`, value `if` (`Cond`, `IfValue`), `if` statement | only the arm the condition selects; in an else-if chain, the conditions in order up to the first that is `true` |
| `match` (value or statement) | only the arm the scrutinee selects |
| `o ?? d` | `d`, only when `o` is `None` |
| `xs.any(f)`, `xs.all(f)`, `xs.find(f)`, `xs.contains(v)`, `indexOf(xs, v)`, `m.any(f)`, `m.all(f)` | elements after the first decisive one are not visited |
| a lambda's body | once per element visited, in order |
| a `fn`'s body | at each call |

Expressions have no side effects, and every failure is a refusal on-chain. So the order among strict operands
(`f(a, b)`, `a + b`, a record's fields) cannot change whether a call is accepted, nor what it returns. Only *whether*
an operand is evaluated can, and the lazy positions above are exactly where that is decided. The order does decide
*which* failure a call meets first when it has several, so it fixes the refusal's class (§2.5): both interpreters
follow it exactly, receiver before arguments (`xs.contains(v)`, `xs.fold(init, …)` and `m.fold(init, …)` evaluate the
collection first), arguments left to right, a record's fields in declaration order. The UPLC legs report no class.

Payouts are made by statements only, so their order is the order in which statements run (§2.3).

### 2.2 Statements

A block is a list of statements and an optional result expression. Its statements run in order, and then the result is
evaluated in the environment they extended.

| Statement | Meaning |
|---|---|
| `const x = e` (`Let`) | evaluate `e`; bind `x` to it for the rest of the block |
| `require(c, msg)` | evaluate `c`; fail with `msg` unless it is `true` |
| `pay(to, amount)` | §2.3 |
| `if c { … } else if d { … } else { … }` (`If`) | evaluate the conditions in order until one is `true`, and run that branch's block; when none is, run the `else` block (no `else`: nothing). Bindings made inside an arm do not leave it |
| `for (const x of xs) { … }` (`For`) | evaluate `xs` (a list or a range), then run the body once per element, front to back, with `x` bound to it |
| `match e { … }` (`Match` statement) | evaluate `e`, select the arm (§3.5), bind its names and run its statements |

### 2.3 Payouts

`pay(to, amount)` evaluates `to`, then `amount`. Both are always evaluated, also when the amount turns out to be 0,
so a failing recipient expression fails the call whatever the amount (M0 review C12). Then:

- `amount < 0` fails ("a payout cannot be negative");
- `amount = 0` makes no payout;
- `amount > 0` appends the payout `(to, amount)`.

A call's payouts are those it appended, in order: source order, list order within a `for`, and only the taken arms of
`if` and `match`. `to` is a `Key`. Pays are statements, so they appear only in method bodies, never in a `fn`, a view,
a `const` value or a lambda.

### 2.4 Depth and limits

Evaluation can be deep in two ways: a recursive `fn` (D7), and an expression nested deeply in the source. M1e's
nesting cap (J18: at most 64 levels, with `+`/`-`, `*`, `&&`, `||` and else-if chains read without deeper recursion)
bounds the second; until it lands, a long chain such as `a + a + … + a` is as deep as it is long. Lists, maps and
ranges are finite, so only recursion can make evaluation endless.

**The chain's limit is its budget, nothing else.** A transaction refuses a call that exceeds the protocol's execution
budget (J18), and nothing short of it: `fn down(n) = n <= 0 ? 0 : 1 + down(n - 1)` at depth 3,000 costs about 20% of
the budget's CPU and 56% of its memory, and the chain accepts it.

**An implementation's limit is no answer.** When an implementation cannot finish a call (its stack runs out, or a time
limit stops it), it gives *no answer*, which is never a refusal: the chain may well accept that call. The reference
interpreters run calls on a stack deep enough for every recursion a transaction's budget allows (512 MiB: the
JavaScript one in a worker, `bin/run-vectors.js`; the Java one on its own thread), and report a stack overflow even there
as a *limit* (JavaScript `Limit`, Java `Interpreter.Limit`), which the differential tests treat as a failure of that
leg, never as an agreement. A call whose caller gives up (the Java interpreter's waiting thread is interrupted) is
*cancelled*: no answer either; the evaluation stops at its next step and has ended when the caller returns. The vectors include `down(3000)` on the transition program and both interpreters.

### 2.5 Refusal classes

A refusal's class names the rule that refused the call, as `kind:detail`:

| Class | When |
|---|---|
| `require:<line>:<col>:<message>` | a `require` did not hold; the detail is its position in the `.jsc` file (the IR's `at`) and its message, as written, so two `require`s with one message are told apart (review S6) |
| `input:<parameter>` | a checked input was not well formed (§4); the detail is the parameter's name |
| `modifier:<word> <field>` | a modifier did not hold (§2.6): `only owner`, `after deadline`, `before deadline` |
| `domain:<operation>` | an operation left its domain. The operation is `/` or `%` (a zero divisor); a `std` row by name (`slice`, `take`, `drop`, `byteAt`, `verifyEd25519`, `div`, `mod`, `pow`, `expMod`, `readBit`, `shift`, `rotate`, `nth`); `asKey` or `asHash`; `List.take` or `List.drop` (a negative count); `Map.set`; `BigMap.set` or `BigMap.delete`; or `pay` (a negative amount) |

The two interpreters must give the same class for every refused call (review S4), so a vector meant to reach one rule
shows that both interpreters reach that rule. With several failures in one call, the first in evaluation order (§2.1)
decides.

### 2.6 Modifiers

A method's modifiers (D10) are checked in source order, after binding the arguments and before the checked inputs.
Each reads a state field `f` of σ and τ:

| Modifier | Holds when |
|---|---|
| `only f` | the key in `f` is one of τ.`signers` (compared by content) |
| `after f` | τ.`validFrom` ≥ the time in `f` |
| `before f` | τ.`validTo` ≤ the time in `f` |

The first that does not hold refuses the call with the class `modifier:<word> <field>`; no statement runs. The
validator makes the same checks on the transaction (an unbounded window end never passes). The generated Java method
and the UPLC transition program compute the *transition* alone: every step but step 2, since they leave the
modifiers to the validator. So on a call that breaks a modifier, both interpreters refuse, the validator refuses, and the
JVM and transition legs agree with the interpreters' transition (`Interpreter.transition`, the JS `program.transition`).
A call of a method with a modifier needs τ; without one it is the caller's error, not a refusal.

## 3. Expressions

### 3.1 Names, literals and blocks

| Node | Value |
|---|---|
| `Lit` | the literal. `utf8("…")` was folded by the checker into the UTF-8 bytes of the string, and `asKey(#…)` of a 28-byte literal into the literal |
| `LocalRef` | the value bound to that local (a parameter, `const`, loop, lambda or match binding) |
| `StateRef` (`state`) | σ |
| `StateField` (a bare field name) | σ's field. It always reads the input state, never a value being built |
| `CtxRef` | the value of that name in τ (§2) |
| `BlockValue` | its block's value: the statements in order, then the result (§2.2). The parser makes no such node at M1a (a lambda's block is the lambda's body), but the IR and both interpreters keep it |

### 3.2 Integers

| Operation | Result | Fails when |
|---|---|---|
| `a + b`, `a - b`, `a * b`, `-a` | exact | never |
| `a / b` | the quotient rounded **toward zero** (as JS `BigInt`; Plutus `quotientInteger`) | `b = 0` |
| `a % b` | `a - b * (a / b)`: it has the sign of `a`, or is 0 | `b = 0` |
| `min(a, b)`, `max(a, b)`, `abs(a)` | as named | never |
| `a < b`, `a <= b`, `a > b`, `a >= b` | the integer order | never |

Vectors (`/`, `%`): `7, 3 → 2, 1`; `-7, 3 → -2, -1`; `7, -3 → -2, 1`; `-7, -3 → 2, -1`; `0, 5 → 0, 0`; `-1, 2 → 0, -1`;
`7, 0` fails. Floor division and modulo are `div` and `mod` (§3.10).

### 3.3 Booleans and comparison

`!a`, `a && b`, `a || b` as usual, with `&&` and `||` lazy (§2.1).

**Byte strings compare lexicographically.** At the first index where they differ, the smaller byte (unsigned) is the
smaller string. When one string is a proper prefix of the other, the prefix is smaller. So `#01 < #0100 < #02` and
`#7f < #80`. This is the order of `<`, `<=`, `>`, `>=` on `Bytes`, `Key` and `Hash`. It is not the canonical key order
(§1.1).

### 3.4 Equality

`a == b` (`Equals`) is structural at the node's `operand` type, and `a != b` is its negation:

| Type | Equal when |
|---|---|
| `Int` | the same integer |
| `Bool` | the same truth value |
| `Bytes`, `Key`, `Hash` | the same length and the same bytes |
| `List<T>` | the same length and equal elements at `T`, position by position |
| `Option<T>` | both `None`, or both `Some` with equal values at `T` |
| record `R` | each field equal at its declared type, in declaration order |
| enum `E` | the same variant, and each field equal at the variant's declared field type |
| `Map<K, V>` | the same keys in the same order, and equal values at `V` |

`BigMap`s, and values holding one (the state of a contract with a BigMap), are never compared (D7). The same equality
decides `xs.contains(v)`, `indexOf(xs, v)` and map lookups (§3.7).

### 3.5 Records, enums and `match`

- `New` (`R { … }`, `{ ...s, f: e }`) builds a record from its `values`, which are in field order. A spread's fields are
  `GetField`s of the spread value.
- `GetField` reads a field of a record or of the state.
- `Variant` builds the enum value: a variant and its field values in order. `Open` has no fields.
- `SomeOf` is `Some(v)`, and `NoneLit` is `None`.

**`match`** evaluates its scrutinee once, then selects the **first arm whose `ctors` include the value's constructor**:
the variant's name for an enum value, `Some` or `None` for an option. The checker makes the arms exhaustive and free of
overlap, so exactly one arm matches. The arm binds each of its `binds` to the field named in `fields` at the same
position. For `Some(x)`, the field is `value`, the option's content. Then it evaluates the arm's block. A `_` arm lists
every remaining constructor and binds nothing.

### 3.6 Lists and ranges

| Operation | Result | Fails when |
|---|---|---|
| `[a, b, ...rest]` (`ListLit`) | the items, then `rest`'s elements | |
| `xs.length`, `xs.isEmpty` | as named | |
| `xs.head` | `Some(first)`, or `None` when empty | |
| `xs.sum()` | the sum of a `List<Int>` (0 when empty) | |
| `xs.reverse()` | the elements in reverse order | |
| `xs.concat(ys)` | `xs`'s elements, then `ys`'s | |
| `xs.contains(v)` | whether some element equals `v` (§3.4); stops at the first | |
| `xs.any(f)` / `xs.all(f)` | the first element for which `f` is `true` / `false` decides; otherwise `false` / `true` | |
| `xs.find(f)` | `Some(first element where f holds)`, or `None` | |
| `xs.filter(f)` | the elements where `f` holds, in order | |
| `xs.map(f)` | `f` of each element, in order | |
| `xs.fold(init, (acc, x) => e)` | `init`, then `e` with `acc` the previous result, for each element front to back | |
| `xs.take(n)` | the first `min(n, length)` elements | `n < 0` |
| `xs.drop(n)` | all but the first `min(n, length)` | `n < 0` |

**`range(a, b)`** (`Range`) evaluates `a`, then `b`. It is the integers `a, a + 1, …, b - 1`, and it is empty when
`b <= a`. It is never a list value. It is only the receiver of `any`, `all`, `filter`, `map` and `fold`, or the list of a
`for`. Those visit its elements in ascending order, and `filter` and `map` make a list. Vectors:
`range(0, 4).fold(0, (s, i) => s + i * i) = 14`, `range(0, 7).filter(i => i % 2 == 0) = [0, 2, 4, 6]`,
`range(3, 1)` is empty.

### 3.7 Maps

`Map.empty()` (`MapEmpty`) is the map with no entries. For `m : Map<K, V>`:

| Operation | Result | Fails when |
|---|---|---|
| `m.get(k)` | `Some(v)` for the entry with key `k`, else `None` | |
| `m.has(k)` | whether an entry has key `k` | |
| `m.set(k, v)` | `m` with `k`'s value replaced by `v`, or with `(k, v)` inserted at its canonical position | `k` has more than 64 bytes |
| `m.delete(k)` | `m` without `k`'s entry (`m` itself when absent) | never |
| `m.size`, `m.isEmpty` | the number of entries; whether it is 0 | |
| `m.keys`, `m.values` | the keys, and the values, in key order, as lists | |
| `m.any(f)`, `m.all(f)` | as for lists, over `(k, v)` in key order | |
| `m.fold(init, (acc, k, v) => e)` | as for lists, over `(k, v)` in key order | |

Keys are compared by content (bytes), whatever `K` is.

### 3.8 BigMaps

For a BigMap value *(before, now, written)* (§1.2) and a key `k`:

| Operation | Result | Fails when |
|---|---|---|
| `m[k]` (`at`, `BigMap<K, Int>` only) | *now*(k), or 0 when absent | |
| `m.get(k)` | `Some(now(k))`, or `None` when absent | |
| `m.has(k)` | whether *now* has `k` | |
| `m.set(k, v)` on `BigMap<K, Int>` | *(before, now* with `k ↦ v`*, true)*, or with `k` removed when `v = 0` | `k` has more than 64 bytes; `v < 0`; `v` needs more than 8,192 bytes as an unsigned big-endian integer (`v ≥ 2^65536`) |
| `m.set(k, v)` on `BigMap<K, Bytes>` | *(before, now* with `k ↦ v`*, true)* | `k` has more than 64 bytes |
| `m.delete(k)` | *(before, now* without `k`*, true)* | `k` has more than 64 bytes |
| `m.isEmpty` | *before* is empty and *written* is false | |
| `BigMap.empty()` (`BigEmpty`) | *({}, {}, false)* | |

The checks in `set` and `delete` happen when the write is evaluated. A write whose result is only read through and then
discarded still fails (M1a review C4). `BigMap<K, Bytes>` tells absence from empty bytes: `get` on an absent key is
`None`, and on an entry set to `#` it is `Some(#)`. A state's BigMap field in the next state is the field's *now*.
The runtime stores it as a Merkle root of *now* (ADR-0014), which is an encoding, not part of evaluation.

### 3.9 Functions, lambdas and the prelude

- `CallFn`: evaluate the arguments left to right. Then evaluate the `fn`'s body block with **only** its parameters
  bound. A `fn` sees no state, no τ and no locals of its caller (D7), so it is pure. A `fn` may call itself.
- Lambdas exist only as arguments of the collection methods above. They see the locals in scope where they are
  written. They are evaluated as §3.6 and §3.7 say.
- `asKey(b)`, `asHash(b)` (`Prelude`): `b` when it has 28 bytes, else fail.
- `after(t)` (`TimePred`, `after: true`): `validFrom >= t`, that is, the whole window is at or after `t`. `before(t)`:
  `validTo <= t`, the whole window ends by `t`. Both are evaluated like other expressions. The checker allows them only
  at the top of a `require` (D11).

### 3.10 The standard library (D8)

A row fails exactly outside its domain. The domains are the ones in `Std.java` and the ADR's D8. "Bit 0" is the lowest
bit of the last byte, that is, the bytes read as an unsigned big-endian number.

| Row | Result | Fails when |
|---|---|---|
| `std/bytes` `length(b)` | the number of bytes | |
| `concat(a, b)` | `a`'s bytes, then `b`'s | |
| `slice(b, start, n)` | the bytes from `min(start, len)` to `min(start + n, len)` | `start` or `n` outside `[0, 2^31)` |
| `take(b, n)`, `drop(b, n)` | the first `min(n, len)` bytes; the rest | `n` outside `[0, 2^31)` |
| `byteAt(b, i)` | the byte at `i`, 0 to 255 | `i` outside `[0, 2^31)`, or `i >= len` |
| `toInt(b)` | `b` read as an unsigned big-endian integer; `#` is 0 | |
| `toHex(b)` | lower-case ASCII hex, two characters a byte | |
| `std/crypto` `sha2_256`, `sha3_256`, `blake2b_256`, `blake2b_224` | the digest (32, 32, 32, 28 bytes) | |
| `verifyEd25519(key, msg, sig)` | §3.11 | `key` is not 32 bytes, or `sig` is not 64 |
| `std/math` `div(a, b)`, `mod(a, b)` | the quotient rounded **toward negative infinity**; `a - b * div(a, b)`, which has `b`'s sign or is 0 | `b = 0` |
| `pow(a, e)` | `a^e` (`pow(0, 0) = 1`) | `e` outside `[0, 1024]` |
| `expMod(a, e, m)` | `a^e mod m`, in `[0, m)` (`a` may be negative) | `e < 0`, or `m <= 0` |
| `sign(a)` | -1, 0 or 1 | |
| `std/bits` `andBytes(pad, a, b)`, `orBytes`, `xorBytes` | aligned at the first byte; with `pad` the result is as long as the longer, its tail copied from the longer; else as long as the shorter (CIP-122) | |
| `complement(b)` | each bit flipped | |
| `readBit(b, i)` | bit `i` | `i` outside `[0, 2^31)`, or `i >= 8 * len` |
| `shift(b, n)` | the `8·len`-bit number shifted left by `n` (right when negative), zero-filled, truncated to `len` bytes; 0 when `\|n\| >= 8·len` | `n` outside `(-2^31, 2^31)` |
| `rotate(b, n)` | rotated left by `n mod 8·len` (right when negative); `b` when empty | `n` outside `(-2^31, 2^31)` |
| `countSetBits(b)` | the number of 1 bits | |
| `findFirstSetBit(b)` | the index of the lowest 1 bit; -1 when there is none | |
| `std/list` `last(xs)` | `Some(last element)`, or `None` | |
| `nth(xs, i)` | `Some(element i)` (from 0), or `None` when `i >= length` | `i < 0` |
| `indexOf(xs, v)` | `Some(first index whose element equals v)` (§3.4), or `None` | |
| `std/time` `elapsedSince(t)` | `max(0, validFrom - t)` | |
| `elapsedSinceAtMost(t)` | `max(0, validTo - t)` | |
| `windowStart()`, `windowEnd()` | `validFrom`, `validTo` | |

### 3.11 Ed25519

`verifyEd25519(key, msg, sig)` answers exactly as the ledger's builtin. That builtin is libsodium's
`crypto_sign_verify_detached`, whose acceptance rules are stricter than RFC 8032's (D8). Lengths first: a key that is
not 32 bytes or a signature that is not 64 **fails** the call. Otherwise the answer is `true` or `false`, and never a
failure. Let `R` be the first 32 bytes of `sig` and `S` the last 32 read little-endian, `L = 2^252 +
27742317777372353535851937790883648493` the group order, and `p = 2^255 - 19`. The answer is `false` when:

1. `S >= L` (S not canonical);
2. `R` has small order. By its encoding, with the top bit of the last byte cleared, its y coordinate (little-endian)
   is one of `0`, `1`, `p - 1`, `p`, `p + 1`,
   `2707385501144840649318225287225658788936804267575313519463743609750303402022` or
   `55188659117513257062467267217118295137698188065244968500265048394206261417927`;
3. `key`'s y coordinate (top bit cleared) is `>= p` (not canonical), or it is in that small-order list;
4. `key` is not a point of the curve `-x² + y² = 1 + d·x²·y²`, `d = -121665/121666 mod p`: no x satisfies it for its
   y. The sign of x is the key's top bit;
5. otherwise, with `h = SHA-512(R ‖ key ‖ msg)` read little-endian and reduced mod `L`, and `B` the base point
   (y = 4/5, x even): the encoding of `[S]B − [h]A` (y little-endian, the top bit the parity of x) differs from `R` as
   bytes.

Otherwise the answer is `true`.

**Non-canonical `R` is always `false`** (rule 5): the encoding of a point is always canonical (its y below `p`), so an
`R` whose y is `p` or more never equals it, on or off the curve. This closed M1a's open item: the ledger's libsodium
1.0.18 agrees on every such `R` (y = `p` … `p + 18` and `2^255 − 18` … `2^255 − 1`, both sign bits).

The vectors (`test/fixtures/ed25519-libsodium.json`, 783 of them, answered by the ledger's libsodium 1.0.18 and checked
on both interpreters, a subset also on the JVM, transition and validator legs) cover each rule on its own: every
small-order encoding as the key, as `R` and as both; `R` the identity with `S = h·a`, which RFC 8032 accepts and rule 2
refuses; mixed-order keys `aB + T`, accepted exactly when the equation holds (for `T` of order 8: `h ≡ 0 mod 8`);
mixed-order `R`; non-canonical encodings; keys off the curve; `S` at `L − 1`, `L` and above. By hand: RFC 8032's test 1
is `true`. That signature with its last byte changed, with `S + L`, or with
the message `#00` is `false`. The identity key with `R` the identity and `S = 0` is `false`. A key encoding `p + 1` is
`false`. A 31-byte key fails.

## 4. Checked inputs (D5, J15)

A value crossing a trust boundary is checked before the body sees it. At M1a this means the arguments of `init`,
`action` and `destroy`, for the parameters the IR lists in `checked`: those whose type holds a `Key`, a `Hash` or a
`Map` anywhere. A value `v` is **well formed at type `T`** when:

| `T` | Well formed when |
|---|---|
| `Key`, `Hash` | `v` has exactly 28 bytes |
| `Int`, `Bool`, `Bytes` | always |
| `List<T>` | every element is well formed at `T` |
| `Option<T>` | `None`, or `Some(x)` with `x` well formed at `T` |
| record `R` | every field is well formed at its declared type |
| enum `E` | every field of its variant is well formed at the variant's declared field type |
| `Map<K, V>` | every key has at most 64 bytes and is well formed at `K`, the keys are **strictly** ascending in canonical order (§1.1; so unique), and every value is well formed at `V` |

A map that is out of order or has a duplicate key cannot be a map value (§1). Such an argument is refused here, so the
body never sees one.

**Views are not checked**: their arguments come from the caller's own query, and nothing on-chain depends on them. A
view given a non-canonical map (out of order, or with a duplicate key) has **no defined outcome**: the map operations of
§3.7 are defined on canonical maps only, and the legs may answer differently. The differential tests never send one,
and the runtime's codec writes maps canonically.

## 5. Time values (D11)

Time taint is a static property. The checker marks every expression that depends on `validFrom` or `validTo` and
refuses the programs whose outcome a time value could decide or make fail, except through `after`/`before`. Evaluation
ignores the `taint` fields of the IR. `validFrom`, `validTo` and the `std/time` rows are ordinary integers when a call
runs. A program the checker accepts has the same outcome for every window that gives `after` and `before` the same
answers, apart from amounts. A time value can still set an amount, for example a clamped `pay`, and the checker warns
about the bias that gives (J6).

## 6. Vectors

Each rule above has vectors in `js/jsc-interpreter/test/`. `semantics.test.js` holds hand-written ones, by section,
written from this document, not recorded from an implementation: they evaluate IR nodes built in the test, or call the
example contracts through their IR fixtures. `crypto.test.js` holds the recorded ones, each from an oracle named in its
file: Ed25519 and the hashes as the ledger's libsodium 1.0.18 answers them, SHA3-256 from Python's hashlib. The
differential tests (J3, §9 of the ADR) then run every recorded call of the differential suites on all five legs, and
settle a disagreement by this document, not by any leg.
