# `.jsc` 2 semantics

What a checked `.jsc` 2 program evaluates to (ADR-0018 D15), for the constructs of milestone M1 (M0, M1a, and M1c's
modifiers and batched actions), of M2a (values, addresses, credentials, funds, and the effects on native assets), of M2b
(params, invariants, events, quorum and authorized) and of M2c (composition: other contracts' instances, read states,
deposits made and received, send datums, stored values). It is
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

**Not yet covered:** owned and keyed objects (M3), and the bodies of `java:` library imports (M4b: a call of one has no
answer, §2.4). `.jsc` libraries (M4) are covered: a library fn is a `fn` (§3.9). A `batched` action (M1c) is covered: its transition
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
| `Value` (M2) | a finite map from (policy, token name) to a non-zero quantity | §1.3; a local's, a fn's, an effect's, and (M2c) a field of the state, the params, a record or a variant |
| `Credential` (M2) | `KeyCred { key }` or `ScriptCred { hash }` | §1.4 |
| `Address` (M2) | a payment credential and a staking part: none, a staking hash (a credential) or a pointer (three integers) | §1.4; `a.payment` is its credential |
| `Instance<C>` (M2c) | another contract's instance: a policy and a 32-byte instance id | a params field only; used whole, as a deposit's target and by `reads` (§5.7, §5.9); not comparable |
| `C.State`, `C.T` (M2c) | a value of the imported contract `C`'s state or declared type | as that type's values, by `C`'s declaration (§5.9) |
| a deposit (M2c) | a value and, for `receives T`, a memo of type `T` | the elements of τ.`funds`.`deposits` (§5.8); not comparable |

Internal values have no type of their own: the **range** `range(a, b)` (§3.6), the **payout** `(to, lovelace)` (§2.3)
and the **send** `(to, value)` (§5.1).

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

### 1.3 Values (M2)

A value is a finite map from a pair *(policy, token name)* of byte strings to a **non-zero** integer, its quantity.
Lovelace is the pair *(empty, empty)*. Entries are in the **ledger's order**: by policy, then by token name, each compared
lexicographically, unsigned, a proper prefix first (the order of `<` on `Bytes`, §3.3; *not* the canonical key order of
§1.1). Every operation keeps a value so: a sum that reaches zero removes the entry. Every quantity of every value lies in
the signed 128-bit range **[−2^127, 2^127 − 1]** (the ledger's native values, CIP-153); an operation whose result would
leave it fails (§3.12). Two values are equal when they hold the same entries.

**Stored values (M2c).** A value may be a field of the state, the params, a record or a variant, so it is stored and
carried as data. As data a value is a map whose token names are in cardano-client-lib's canonical order (shorter first),
not the ledger's; that is an encoding (D5), invisible here: a stored value is the same value, and reading the field gives
it. A value is never an element of a list, an option or a map (wrap it in a record).

### 1.4 Credentials and addresses (M2)

A credential is a key's (`KeyCred`, its 28-byte hash `key`) or a script's (`ScriptCred`, its hash `hash`). An address
is a payment credential and a staking part: none, a staking hash (a credential), or a pointer (slot, transaction index,
certificate index). `wallet(k)` is *(KeyCred k, none)*, `wallet(k, s)` is *(KeyCred k, StakeHash (KeyCred s))*;
`script(h)` and `script(h, s)` likewise with `ScriptCred h`. A pointer arrives only as an input. Two credentials are
equal when they are of the same kind with equal bytes; two addresses when their payment credentials and staking parts are
equal (a pointer's three integers by value).

## 2. Calls

A call evaluates one method. Its inputs are the method's name, the input state σ (a `State` record, absent for an
`init`), the arguments in parameter order, and the transaction context τ. τ is absent for a view, and for a method whose
IR says `usesCtx: false` and that has no `only f`, `quorum` or `authorized` modifier, which then never reads it. τ holds
these values:

| Name | Type | Meaning |
|---|---|---|
| `caller` | `Key` | the key that signed the call (an authenticated sender) |
| `received` | `Int` | the lovelace this call pays into the instance |
| `balance` | `Int` | the lovelace the call may allocate (for a destroy: everything the instance holds) |
| `validFrom` | `Int` | the start of the transaction's validity window (POSIX ms) |
| `validTo` | `Int` | its end |
| `signers` | `List<Key>` | the keys that signed the transaction, the caller among them (read only by `only f`, `quorum` and `authorized`, §2.6; the caller alone when a vector does not say) |
| `approvers` (M2b) | `List<Hash>` | the scripts that approve this call: each has a zero withdrawal in the transaction whose approval names this contract, instance and action (ADR-0009 D3; a native script, which has no redeemer, approves through its withdrawal alone), and is neither this contract's script nor a JOUST verifier (read only by `authorized`, §2.6; none when a vector does not say) |
| `funds` (M2) | | the native assets the call sees, for a method that reads `funds` or sends, mints, burns, accepts or receives: `received` (a `Value`: what the caller offers, without lovelace), `held` (a `Value`: what the instance holds besides lovelace and its own token), `policy` (a `Hash`: the contract's policy), `self` (`Bytes`: the instance's token name) and (M2c) `deposits`: for a receiving action, the deposits claimed on its continuing output in the registry's order, each a value and, for `receives T`, a memo (a value of `T`); empty otherwise |
| `reads` (M2c) | | for a method with `reads` parameters: the state of each instance a params field names, by that field, as the transaction's reference inputs hold it (ADR-0011 D2) |
| `shared` (M3a) | | for an owned method with a `shared` parameter: the instance's state, as the transaction's reference input holds it (§2.8) |

**The params** (M2b, ADR-0009 D1). A contract that declares `params { … }` is called as deployed with them: a `Config`
record, fixed for every call of the deployment (the script is applied to them), read as `params.f`. A call of a method
that reads them needs them; without them it is the caller's error, not a refusal. They are never a time value.

The caller signs. On a direct call, the validator requires the caller's signature on the transaction whenever it reads
τ. In a batch (ADR-0004) the batcher signs the transaction, not the users: a batch step's caller is the intent's owner,
who signed the intent when creating it, so a batch step's τ.`signers` are the intent's signers, its owner (#80 review
S5); a batched action cannot use `only`, so its body never reads them. Either way, a τ whose `caller` is not among its
`signers` is not a call: a call given one is the caller's error, as a call of a method with a modifier without τ is
(§2.6), not a refusal, and neither interpreter answers it (M1 hardening, #76 review S6).

**The outcome** is one of the following:

| Kind | Outcome |
|---|---|
| `init` | the first state |
| `action` | the next state and the effects |
| `destroy` | the effects |
| `view` | the view's value |
| any | **refused** |

A call is *refused* when evaluation *fails* anywhere: a `require` that does not hold, a checked input that is not well
formed, or an operation outside its domain. Every failure has the same outcome on-chain. A failure also has a *class*
(§2.5), which the two interpreters must agree on, and a message, which is prose: no two legs need to agree on messages.
A failure stops the call. Nothing a refused call evaluated is kept, its payouts included. An implementation that cannot
finish a call (its stack runs out) gives **no answer**, which is not a refusal (§2.4).

**Steps of a call:**

1. Bind each parameter to its argument, and (M2c) each `reads` parameter to τ.`reads` of its params field (a call of
   such a method without it is the caller's error, not a refusal).
2. **Modifiers** (§2.6), in source order: a broken one fails the call.
3. **Checked inputs** (§4). For each parameter listed in the method's `checked` list, in order, the argument must be
   well formed at the parameter's type; otherwise the call fails (class `input:<parameter>`). M2c: then, in an init, the
   params it is made under, well formed at `Config` (class `input:params`; review r3, C7); then each read state,
   in the method's order, well formed at `C.State` (class `input:<parameter>`); then, for `receives T`, every deposit's
   memo well formed at `T` (class `input:deposits`). This happens before any statement runs, in the transition alone
   too (it is the method's own check, as the generated code makes it where the method starts).
4. Run the body's statements in order (§2.2).
5. Evaluate the body's result expression, if any: the next state (init, action) or the value (view). A destroy has no
   result.
6. **What was accepted** (§5.4): if the method's body holds an `accept` statement (in any arm or loop), the sum of what
   it accepted must equal τ.`funds`.`received`; otherwise the call fails.
7. The outcome is that value with the **effects** collected in step 4: the payouts and the sends, each in the order
   they were made, the net mint and the sum of what was accepted (§5), the events in the order they were emitted
   (§5.6), and (M2c) the deposits in the order they were made (§5.7).
8. **Output checks** (M2b, §2.7): for an init or an action, the next state's quorum shapes and the invariants.

**The modifiers are preconditions of a call** (§2.6): `only f`, `after f`, `before f`, `quorum` and `authorized` are
evaluated against τ before the body; the output checks (§2.7) after it. `payable`, `open` and `batched` refuse nothing in a call. **Other transaction-level rules are not evaluated by
a call:** everything about the payouts as a whole (whether they total more than τ.`balance`, what a destroy leaves
over, the ledger's minimum per output) and a batch's own rules. From M2 also the rules on native assets that depend on
the transaction or on everything the instance holds rather than on the call (ADR-0018 D4, r15; review r1 F1, S2):

- sends and burns that exceed what the instance holds plus what it receives, accepts and mints: the instance's output
  would hold a negative quantity, which no output can;
- an output quantity above 2^64 − 1, or a net mint of one token outside Int64 (the ledger's widths; a call's values
  range over 128 bits, §1.3);
- a destroy that leaves native assets the instance holds, neither sent nor burned;
- the ledger's minimum lovelace in each send's output;
- a send to the address of another script the same transaction spends (a send to the contract's own script is the
  call's to refuse, §5.1);
- M2c (ADR-0011): a deposit whose target is not spent in the same transaction by a receiving action, or whose claim is
  missing or differs; a read state that is not the inline datum of the unique reference input holding its instance's
  token at its script's address, or not of the contract's state's shape (constructor and field count). The validators
  of the depositor and the receiver, and the reader's, refuse those transactions; a call is evaluated on the deposits
  claimed and the states read as τ gives them.

The call succeeds; its transaction fails, at the ledger or at the validator. The validator leg checks them with the
rest of the transaction; the differential tests' harness refuses the first two as the ledger does
(`StateHarness.ledgerProblem`) and carries a vector of each kind ("every leg accepts, the transaction is refused").

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
| `input:<parameter>` | a checked input was not well formed (§4); the detail is the parameter's name; M2c: a read state's (a `reads` parameter's name), or `input:deposits` for a deposit's memo |
| `modifier:<word> <field>` | a modifier did not hold (§2.6): `only owner`, `after deadline`, `before deadline`; M2b: `quorum council quorum` (the keys, then the threshold), `authorized params.guardian` |
| `invariant:<name>` | M2b (§2.7): the next state broke the invariant `<name>`; `invariant:quorum <keys> <threshold>`: a quorum's shape |
| `domain:<operation>` | an operation left its domain. The operation is `/` or `%` (a zero divisor); a `std` row by name (`slice`, `take`, `drop`, `byteAt`, `verifyEd25519`, `div`, `mod`, `pow`, `expMod`, `readBit`, `shift`, `rotate`, `nth`, `scale`); `asKey` or `asHash`; `List.take` or `List.drop` (a negative count); `Map.set`; `BigMap.set` or `BigMap.delete`; `pay` (a negative amount); M2's `Value.lovelace`, `Value.asset`, `Value +`, `Value -`, `covers` (§3.12), `send`, `mint`, `accept` (§5.1–5.3), or `accepted` (§5.4); M2c's `deposit` (§5.7) |

The two interpreters must give the same class for every refused call (review S4), so a vector meant to reach one rule
shows that both interpreters reach that rule. With several failures in one call, the first in evaluation order (§2.1)
decides.

### 2.6 Modifiers

A method's modifiers (D10) are checked in source order, after binding the arguments and before the checked inputs.
Each reads a state field `f` of σ (M2b: `quorum` and `authorized` may read `params.f` instead) and τ:

| Modifier | Holds when |
|---|---|
| `only f` | the key in `f` is one of τ.`signers` (compared by content) |
| `after f` | τ.`validFrom` ≥ the time in `f` |
| `before f` | τ.`validTo` ≤ the time in `f` |
| `quorum k t` (M2b) | the threshold `t` is at least 1, and at least `t` distinct keys of τ.`signers` are in the list `k` (ADR-0009 D2: read from the input state, never the result) |
| `authorized c` (M2b) | the credential `c` is a key credential whose hash is one of τ.`signers`, or a script credential whose hash is one of τ.`approvers` (ADR-0009 D3) |

The first that does not hold refuses the call with the class `modifier:<word> <field>`; no statement runs. The
validator makes the same checks on the transaction (an unbounded window end never passes). The generated Java method
and the UPLC transition program compute the *transition* alone: every step but step 2, since they leave the
modifiers to the validator. So on a call that breaks a modifier, both interpreters refuse, the validator refuses, and the
JVM and transition legs agree with the interpreters' transition (`Interpreter.transition`, the JS `program.transition`).
A call of a method with a modifier needs τ; without one it is the caller's error, not a refusal. Each `only`, `after`
and `before` names its field: the IR readers refuse one without it (and the interpreters never skip one), so no
modifier is dropped on the way (#76 review S5).

### 2.7 Output checks (M2b)

The validator checks every state it accepts as an output (ADR-0009 D2, D4); the interpreters make the same checks after
the body, in this order, so a call refused there has the class the rule names:

1. At the instance's creation (its `init`, not an owned init): each quorum that names params fields (`quorum params.k
   params.t`) that any method names, an owned method's too, is *well formed*: the params are the deployment's.
2. For an init's or an action's next state: each pair of state fields any method's `quorum` names (once each, in source
   order) is well formed. A destroy has no output and makes no output check.
3. Each invariant holds of the next state (with the params), in declaration order (class `invariant:<name>`). An
   invariant that *fails* while it is evaluated (a division by zero, a `std` row out of its domain) does not hold either,
   and refuses with the same class: its failure is the validator's, as its being false is (#84 review S2). An invariant
   holds no `require` (JSC0441).

*Well formed* (class `invariant:quorum <keys> <threshold>`): the keys are strictly ascending, by content (so they are
distinct), each is 28 bytes, and 1 ≤ threshold ≤ the number of keys. As with the modifiers, the generated Java method
and the transition program compute the transition without these checks, which are the validator's (and the runtime's,
before it submits): on such a call both interpreters refuse, the validator refuses, and the JVM and transition legs
agree with the interpreters' transition. An invariant is evaluated like a view's body: the state, the params and its
own consts; no transaction, no effects.

### 2.8 Owned objects (M3a)

An *owned object* (ADR-0003) is a value of an `owned T { … }` record held in its own UTxO under an instance. Its value is
a record (§1), compared and encoded as one. Owned methods name their type: `owned init T.m`, `owned action T.m`,
`owned destroy T.m`. A call of one is evaluated as in §2, with these differences:

| Kind | Input σ | Outcome |
|---|---|---|
| `owned init T.m` | none | the new object (a `T`) |
| `owned action T.m` | the object, a `T` | the next object and the effects |
| `owned destroy T.m` | the object, a `T` | the effects |

- **The input.** In an owned action or destroy, `state` is the object, and a bare field name reads the object's field;
  the modifiers `only f`, `after f`, `before f`, `quorum k t` and `authorized c` read the object's fields (or `params.f`).
  τ.`received` is what the call pays into the object, and τ.`balance` the lovelace the object holds: for an action
  without its deposit, for a destroy everything (as an instance's, §2).
- **The instance's state** reaches an owned method only through a parameter `s: shared`, bound to τ.`shared`: the
  instance's state as the transaction's reference input holds it (ADR-0003 D1), never spent, so an owned call does not
  contend with others. A call of a method with a `shared` parameter needs τ.`shared`; without it it is the caller's error,
  not a refusal. It is not a checked input: it is this contract's own instance datum, which its validator accepted.
  In a transaction that also spends the instance, τ.`shared` is still the input's state, never the instance call's
  outcome (ADR-0018 D10, r23; today's ledger refuses such a transaction, and the runtime never builds one).
- **No deposits.** An owned action receives none (`receives` is JSC0447): a deposit's target is an instance.
- **Output checks** (§2.7): for an owned init's and an owned action's object, each quorum over fields that the owned
  type's methods name (over the object's fields) is well formed. A params quorum is checked at the instance's init only
  (§2.7 item 1), whichever method names it, never at an owned init. An owned destroy makes no output check.
- **Kinds.** A method of type `T` called on an object of another type is the caller's error, not a refusal: the
  validator refuses it (ADR-0003 I21, ADR-0012 I42) and the attack suite covers it.
- **Transaction-level rules** (§2): a shared read of another instance than the object's, an object spent with an
  instance's redeemer or an instance with an object's, and a creation without its seed are the validator's to refuse.

- **Several owned types** (M3a2, ADR-0012 D1). Each type's objects are a datum constructor of their own (`Holding_T`,
  in declaration order), so a method of type `T` runs only on an object of type `T`, whatever their fields' shapes. Each
  type has its own owned init and escape hatch.
- **BigMaps in owned types** (M3a2, ADR-0012 D2, ADR-0014): an owned record's `BigMap` field is read and written as the
  state's is (§1), its claims owned by the object's input (or, at creation, the seed): one object's claims are never
  another's or the instance's. An owned init may write the object's maps (`BigMap.empty().set(…)`). The instance itself
  keeps no `BigMap` while the contract has owned types (JSC1211).
- **What a creation takes** (M3a2, issue #81): an owned init may `accept` native assets (it is then payable), and its
  outcome's accepted value is the object's, as an action's is. An instance's `init` still accepts none (JSC0426).
- **A payable owned destroy** (M3a2): τ.`received` is paid in with the call, so its payouts may allocate `balance +
  received`. The instance's destroy is never payable.

- **Moves** (M3a3, ADR-0012 D3). An owned action with a parameter `to: into` moves its object to another instance
  of the contract: its outcome is the object's next value, held under that instance. τ.`into` is the destination's
  state as the transaction's input holds it. The destination consents with an instance action `admits T`, whose first
  parameter is the arriving object's id and whose optional second parameter (`obj: T`) is the arriving object as its
  input holds it, before the move (not for a type that keeps a `BigMap`: its map reads would be unproven, so it is
  admitted by its id alone, JSC1212). Both halves are in one transaction, and each reads the other's input, never its
  outcome. **Each half is its own call** (ADR-0018 D10, r25, after #107 review C1): it is evaluated on its own input and
  the other half's input, and a refusal's class is its own half's. The transaction is accepted only if both halves are:
  the validator refuses it when either half refuses. No order between the halves is defined beyond that: a tool that
  runs both reports the first refusal it meets (the runtime's `Compose`, in the order calls were added). An object
  arrives only from an instance of the same policy, so its datum is this contract's own output and not a checked input.
- **Invariants by type** (M3a3, ADR-0012 D1): `invariant T.name = …` reads an object of type `T` (its fields as bare
  names) and is checked on every continuing object of that type (an owned init's, an owned action's, a move's), never
  on another type's objects or on the state. A type that keeps a `BigMap` has none (JSC1213).

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
  bound. A `fn` sees no state, no τ and no locals of its caller (D7), so it is pure. A `fn` may call itself. A `fn` of
  an imported `.jsc` library (the IR's `libraries`, D9) is evaluated in the same way: it is the same kind of `fn`,
  declared in another file. Its `require`s refuse with their own position, in the library's file (§2.5).
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

### 3.12 Values, credentials and addresses (M2)

Every operation evaluates its arguments left to right (receiver first), then:

| Expression | Value | Fails when |
|---|---|---|
| `Value.none()` | the empty value | never |
| `Value.lovelace(n)` | *{(empty, empty) ↦ n}*, empty when `n` = 0 | `n` ≠ 0 is out of the 128-bit range |
| `Value.asset(p, t, n)` | *{(p, t) ↦ n}*, empty when `n` = 0 | `n` ≠ 0 and (`t` has more than 32 bytes, or `n` is out of range) |
| `v + w` | entry by entry sum, zero sums removed | a sum is out of range |
| `v - w` | `v + (−1)·w` | a negated quantity of `w` (that is, −2^127) or a difference is out of range |
| `v.lovelace` | the quantity of *(empty, empty)*, 0 when absent | never |
| `v.of(p, t)` | the quantity of *(p, t)*, 0 when absent | never |
| `v.covers(w)` | every quantity of `w` is at most `v`'s at that pair (0 when absent) | `v` or `w` holds a negative quantity |
| `v == w`, `v != w` | equality of entries (§1.3) | never |
| `scale(k, v)` (`std/value`) | each quantity times `k`; empty when `k` = 0 | `k` ≠ 0 and a product is out of range |
| `isEmpty(v)`, `withoutLovelace(v)` (`std/value`) | whether `v` has no entry; `v` without *(empty, empty)* | never |
| `keyCred(k)`, `scriptCred(h)`, `wallet(…)`, `script(…)` | §1.4 | never |
| `a.payment` | the payment credential | never |
| `paymentKey(a)`, `paymentScript(a)` (`std/address`) | `Some` of the payment credential's hash when it is a key's (a script's), else `None` | never |
| `match c { KeyCred { key } => …, ScriptCred { hash } => … }` | the arm of `c`'s kind, `key` or `hash` bound to its hash | never |

These are exactly the failure conditions of the ledger's native value builtins (`insertCoin`, `unionValue`,
`scaleValue`, `valueContains`), which run on-chain; the generated Java's API adapter (`ValueAdapter`) fails on the JVM
under the same conditions.

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
| `Credential` (M2) | it is the ledger's encoding (a key `Constr 0 [h]` or a script `Constr 1 [h]`: no other tag, no other field count) and `h` has exactly 28 bytes |
| `Address` (M2) | it is the ledger's encoding (`Constr 0 [payment, staking]`, staking `Constr 1 []` or `Constr 0 [s]`), its payment credential is well formed, and its staking part is none or a staking hash (`Constr 0 [c]`) with `c` a well-formed credential. **A staking pointer is never well formed** (r15, review r1 S1: whatever its parts; Conway gives pointers no staking meaning, and a stored pointer no output can carry would make every send to the address fail) |

A `Value` never crosses as an argument (it is not a parameter type, D5); it crosses stored, in a read state or a memo
(M2c, below). The IR lists a
parameter whose type holds an `Address` or a `Credential` in `checked`. The interpreters' values cannot hold a wrong
constructor tag or field count, so for them the encoding clauses are always true; the generated code decides them on the
data (`AddressAdapter.addressOk`/`credentialOk`), where a transition program, which decodes nothing before the method
runs, could otherwise be handed one (review r1 C2). The validator's decoding of a redeemer refuses such data too; the
tests carry both legs on raw data (`JscV2M2HostileInputTest`).

**M2c: the params, at the init** (review r3, C7). The params are fixed for a deployment, so the init checks them once:
well formed at `Config`, by the rules above (every `Key` and `Hash` 28 bytes, maps canonical, addresses the ledger's,
every `Value` as below). No instance is then made under params that would let a writer store a value its readers
refuse (a `Value` param of another policy, or a `Hash` param that `Value.asset` would make one of); every other method
trusts them. A params quorum's keys (§2.7) are checked here first, so a malformed key is `input:params`.

**M2c: read states and memos.** A `reads` parameter's state (another contract's datum) and, for `receives T`, each
deposit's memo are checked inputs too (§2, step 3), by the same rules at `C.State` and `T`. A value stored in them (a
record's `Value` field) is well formed when its data is a value's: no repeated token name, no zero quantity, no empty
token map, policies in order, each lovelace's (empty) or 28 bytes (§5.4a's rules; any token order; review r2, C6:
data crosses as canonical CBOR, whose key order is the ledger's only for such policies). The generated decoder of a memo decides every constructor
tag and field count on the data, as for addresses; the validator checks a read state's outer shape (its constructor and
field count, ADR-0011 D2) and the checked-input rule its keys, maps, addresses and stored values, used or not (review
r1, C4). A read state's nested constructor tags (its `Bool`s, `Option`s and enums) are taken as its contract wrote them:
a `.jsc` contract's validator refuses a malformed tag in any argument it stores, so a state it accepted is well formed
(D5; `JscV2M2cHostileInputTest`). This holds for `.jsc` imports only and is decided again for `java:` contract imports
(M4b). The interpreters check an arriving `Value` (a memo's, a read state's) by the decoder's domain too: every policy
lovelace's or 28 bytes, every token name at most 32 bytes, every quantity in the signed 128-bit range (review r1, C2;
r2, C6).

A map that is out of order or has a duplicate key cannot be a map value (§1). Such an argument is refused here, so the
body never sees one.

**Views are not checked**: their arguments come from the caller's own query, and nothing on-chain depends on them. A
view given a non-canonical map (out of order, or with a duplicate key) has **no defined outcome**: the map operations of
§3.7 are defined on canonical maps only, and the legs may answer differently. The differential tests never send one,
and the runtime's codec writes maps canonically.

## 5. Effects on native assets (M2, D4) and events (M2b)

Effects are statements (D4): an action's or a destroy's, in any arm or loop, never in a `fn`, a view, a `const` value or
a lambda. Each kind accumulates on its own; no order across kinds is defined. Each needs τ.`funds` (§2).

### 5.1 Sends

`send(to, v)` evaluates `to` (an `Address`), then `v` (a `Value`). It fails when `v` holds a quantity that is not
positive, or a reserved token name (§5.5) under the contract's own policy (τ.`funds`.`policy`), or when `to`'s payment
credential is the contract's own script (`ScriptCred(τ.funds.policy)`: every call spends the instance's input there, and
the validator never delivers a send to a spent script's address, ADR-0007 I30; review r1 F1). A checked `Address` (§4)
may be that script: the rule knows no policy, so a stored address can make every later send to it fail (review r2 S6).
A contract that stores an address it will send to guards it where it is taken, as Coins' `init` does:
`require(paymentScript(a) == None || asHash(paymentScript(a) ?? #00) != funds.policy, …)`. Otherwise it appends the
send *(to, v)*: a call's sends are in the order they were made. An empty value may be sent (the transaction's builder
adds the ledger's minimum). M2c: `send(to, v, d)` evaluates `d` after `v` (and its checks) and attaches it to the send as
its inline datum: a value of any of the contract's own types, encoded by D5's rules (a stored value in its wire form,
§1.3). The validator requires the output to carry exactly that datum. Without `d` the send has none.

### 5.2 Mints and burns

`mint(t, n)` and `burn(t, n)` evaluate `t` (`Bytes`), then `n` (`Int`). Each fails unless `n` > 0, `t` has at most 32
bytes, and `t` is not a reserved token name (§5.5). Then the call's **net mint** (a value, empty at first) gains `n`
(`mint`) or −`n` (`burn`) of *(τ.`funds`.`policy`, t)*; it fails when that pair's new total is non-zero and out of the
128-bit range. A total that reaches zero leaves no entry: `mint(t, 2)` then `burn(t, 2)` mints nothing.

### 5.3 Accepted assets

`accept(v)` evaluates `v` and fails when `v` holds lovelace, a quantity that is not positive, or a reserved token name
under the own policy. Otherwise the sum of what the call accepted (a value, empty at first) gains `v`; it fails when a
sum is out of range. `accept(Value.none())` accepts nothing.

### 5.4 What was accepted, checked last

A method whose body holds an `accept` statement takes in exactly what the caller offers (ADR-0007 r2): after its
result is evaluated (step 6 of §2), the sum of what it accepted must equal τ.`funds`.`received`, or the call fails
(class `domain:accepted`). Only a payable method may accept, and a payable method that reads `funds`, sends, mints or
burns must accept (static rules, D4). A method without `accept` has nothing to check: the validator requires that it is
offered no native assets.

### 5.4a What is offered, as a value

τ.`funds`.`received` is a value (§1.3), in the ledger's order, like every other. The transaction carries it as data in
the redeemer, where a writer may order a policy's token names otherwise (cardano-client-lib writes canonical CBOR,
shorter names first: `01` before `00ff`). The validator and the transition program convert that encoding before the
method sees it, and refuse one that is not a value's: a repeated token name of a policy, a zero quantity, an empty token
map, policies out of order (`ValueAdapter.fromWire`; review r1 C1). A transition program's τ.`funds`.`held` is converted
the same way. So the outcome of a call never depends on how its offer was written.

### 5.5 Reserved token names

Under a contract's own policy, a token name of 32 bytes (an instance), or of 29 bytes starting with 0x4F (an owned
object) or 0x49 (an intent), is reserved (ADR-0007 D3): it is never minted, burned, sent or accepted by a call. Another
policy's names are not reserved.

### 5.6 Events (M2b)

`emit(e)` evaluates `e`, a value of the contract's `Event` (each `event X { … }` declares one of its variants), and
appends it to the call's events: they are in the order they were emitted, in arms and loops as they run, and an
untaken branch emits nothing. It needs no τ and never fails. An action or a destroy may emit; an init, a view, a `fn`, a
lambda or a value block may not, nor a batched action. The events are part of the outcome, and of the redeemer: the
validator requires that the redeemer carries exactly the events the method returns (ADR-0009 D5, I35). A method whose
result record has events (any action of the contract that returns the record emits) carries them, empty when it emits
none.

### 5.7 Deposits (M2c)

`deposit(i, v, m)` evaluates `i` (an `Instance<C>`, a params field), then `v` (a `Value`), then `m` (the memo: a value of
any of the contract's own types). It fails (class `domain:deposit`) when `v` is empty, holds a quantity that is not
positive, or holds a reserved token name of any policy but lovelace's (32 bytes; 29 starting 0x4F or 0x49: the output
verifier's rule, so an NFT named by a 32-byte hash cannot be deposited; send it instead). Otherwise it appends the
deposit *(i, v, m)*: a call's deposits are in the order they were made. A deposit takes no `funds`: a method that only
deposits takes ADA (fees' `sell`). The value leaves this instance and goes into `i`'s continuing output in the same
transaction, which `C`'s receiving action takes in (§5.8; ADR-0011 D3); the checker refuses a deposit into a contract
with no receiving action, or with a memo none of its receiving actions decodes. Moving value out, a deposit needs an
audience (`open`, `only`, …), as a payout does.

### 5.8 Receiving deposits (M2c)

An action that says `receives` takes the deposits claimed on its continuing output: τ.`funds`.`deposits`, in the
registry's order, each its value and, for `receives T`, its memo (a checked input, §4). The method decides what to
credit; the validator adds exactly the claimed deposits to the instance's value (ADR-0011 I40). Any other method takes
none: a deposit claimed on its output fails its transaction. A deposit is not comparable; read its `value` and `memo`.

**Deposits are unauthenticated** (review r1, S5). A deposit may come from any input of the transaction, a wallet's
included: the registry names an input as its depositor, not a contract, and `.jsc` does not expose who it was. Its memo
is whatever the depositor wrote, decoded and checked but not proof of anything: credit by `d.value`, and never grant
anything by a memo alone (a grant per memo can be farmed with minimum deposits). When a receiver has several receiving
actions, the transaction's builder picks the one that decodes the memos.

### 5.9 Read states (M2c)

A parameter `p: reads params.f` (an action's or a destroy's) is the state of the instance `params.f` names (an
`Instance<C>`), as the transaction's reference input holds it: a value of `C.State`, read with `C`'s declaration. Its
fields are read like any record's. A field `C` sets from its own window (a time field of `C`, which `C`'s checked IR lists)
is a time value here (§6). Staleness is the reader's rule: a reference input runs no validator, so the reader checks a
time field with `before`/`after` (`require(before(p.updatedAt + maxAge))`: the whole window ends in time).

**Deployer trust** (review r1, S3). Nothing on chain binds an `Instance<C>` to `C`. The validator authenticates a read
state as the datum of a reference input holding the instance's token at its policy's script address, with `C.State`'s
outer shape; a deposit goes to whatever script that policy is. The deployer who sets the params must point the field at
an instance of `C` (for a deposit, of a contract that receives): a deposit into a script that ignores deposit claims is
value the transaction's builder can take (ADR-0011's deployer-trust model).

## 6. Time values (D11)

Time taint is a static property. The checker marks every expression that depends on `validFrom` or `validTo` and
refuses the programs whose outcome a time value could decide or make fail, except through `after`/`before`. M2: a time
value is never a `Value`'s quantity, any part of a sent or accepted value, or a mint quantity or token name (a name over
32 bytes or of a reserved kind fails the call; review r1 S5), and never decides whether `paymentKey` or `paymentScript`
is `Some`. A time value in either operand of `covers` taints its result. M2b: a field that holds a time value is never a
quorum's keys or threshold or an authorized credential (JSC0524), an invariant never depends on one (JSC0525; a
comparison of one is refused where it is, JSC0502), the params are never time values, and an event may carry one (with a
warning, JSC9008: the window's bias carries into the event). M2c: a read state's field is a time value when it is a time
field of its contract; a time value is never any part of a deposit's value (JSC0526; a deposit must be strictly
positive); a send's datum or a deposit's memo may carry one (with a warning, JSC9009); a memo a receiving action reads is
not a time value there (it is a checked input, as an argument is: whoever writes a depositing contract chooses it, as a
caller chooses an argument). Evaluation
ignores the `taint` fields of the IR. `validFrom`, `validTo` and the `std/time` rows are ordinary integers when a call
runs. A program the checker accepts has the same outcome for every window that gives `after` and `before` the same
answers, apart from amounts. A time value can still set an amount, for example a clamped `pay`, and the checker warns
about the bias that gives (J6).

## 7. Vectors

Each rule above has vectors in `js/jsc-interpreter/test/`. `semantics.test.js` holds hand-written ones, by section,
written from this document, not recorded from an implementation: they evaluate IR nodes built in the test, or call the
example contracts through their IR fixtures. `crypto.test.js` holds the recorded ones, each from an oracle named in its
file: Ed25519 and the hashes as the ledger's libsodium 1.0.18 answers them, SHA3-256 from Python's hashlib. The
differential tests (J3, §9 of the ADR) then run every recorded call of the differential suites on all five legs, and
settle a disagreement by this document, not by any leg.
