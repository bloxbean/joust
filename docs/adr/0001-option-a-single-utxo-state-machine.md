# ADR-0001: Option A — single-UTxO state machine (`@StateContract`, INLINE storage, direct mode)

**Status:** Proposed (r2: revised after review r1 by three independent reviewers; see §14)
**Date:** 2026-11-06
**Builds on:** `docs/research/state-oriented-contracts-research.md` (Option A, §6 design rules, §9 adjustments),
`docs/research/annotation-catalog.md` (milestone-1 rows)
**JuLC baseline:** `main` at `728cb835`, published locally as `0.1.0-local-728cb83-SNAPSHOT` (javac frontend, ADR-068)
**CCL baseline:** `0.8.0-pre7`

## 1. Context

JOUST lets a developer write a Cardano contract as a **state record plus pure `@Init` / `@Action` / `@View`
functions** and generates (a) the on-chain validator, compiled by JuLC, and (b) a typed off-chain client. The same Java
method runs on both sides: on the JVM to compute the next state and build the transaction, and on-chain to re-run it
and check that the output datum equals the result.

The prototype in `examples/counter` proves the idea with hand-written "generated" files. It has 13 passing tests
(2 JVM, 11 on-chain) against JuLC `main`. Its end-to-end run on Yaci DevKit has never been verified. It also leaves
four things open:

1. **No processor.** `CounterValidator` and `CounterClient` are hand-written.
2. **No value movement.** A counter never holds or pays out funds, but escrow, vaults and crowdfunds do.
3. **One script per instance.** The thread-token policy is parameterised by the seed UTxO, so every deployment is a
   new script hash and address. Every transaction must attach the whole script, a reference script would have to
   be deployed per instance, and nothing can find "all counters" by address.
4. **No architecture for what comes next.** The research doc requires Option A to sit on an internal State IR with
   pluggable storage and execution strategies, so that B, C and D (MERKLE/SHARDED storage, owned/shared state,
   batching) slot in without rewriting A.

Facts established for this ADR (spiked against JuLC `728cb835`):

| # | Fact | Consequence |
|---|---|---|
| F1 | JuLC's javac frontend compiles against a **fixed class path** (its own on-chain API) plus *library sources*: `.java` files under `META-INF/plutus-sources/` (with `index.txt`) in jars on the **annotation-processor path**, plus `@OnchainLibrary` sources of the current compilation | `julc-state-api` must ship its sources that way, and consumers must put it on `annotationProcessor` |
| F2 | `java.lang.annotation.Documented` is not in JuLC's JDK subset; `Retention` and `Target` are | API annotations drop `@Documented` |
| F3 | Generic records are rejected (`JULC0012 Cannot resolve type: type parameter S`) | No `Result<S>`; see D5 |
| F4 | `JulcList<Payout>` built with `JulcList.empty().prepend(...)` in library code, `for` loops over it, and `void` static library methods all compile | Payout lists and a `void require(...)` work |
| F5 | `throw` is rejected (`THROW_UNSUPPORTED`); `Builtins.error()` on the JVM throws a bare `RuntimeException("Plutus script error")`; `ContextsLib.trace` is a no-op on the JVM | A shared `require` would lose its message on the JVM; see D7 |
| F6 | A static call must name its class by simple name (`JULC0123`) | Generated code imports every class it calls |
| F7 | JuLC's processor collects `@OnchainLibrary` sources every round and keeps them across rounds, and compiles a validator in the round it first appears | A validator generated in round 1 compiles in round 2 against the user's class |
| F9 | `IntervalLib.finiteLowerBound/finiteUpperBound` return `-1` for an infinite bound and ignore closure. In Conway the lower bound is inclusive and the upper (`invalidHereafter`) exclusive; JuLC's local evaluator matches | Generated time checks pattern-match `Finite` themselves; the interval is `[validFrom, validTo)` |
| F10 | `ValuesLib.uniqueTokenName(TxOutRef)` is `blake2b_256(txId ‖ I2OSP(idx, 2))` | Instance ids use it |
| F11 | `JulcMap.insert` prepends (duplicate keys stay); `get` aborts on-chain on a missing key; `getOrDefault` has no on-chain lowering | Update with `delete(k).insert(k, v)`; read with `containsKey`/`get` |
| F12 | JuLC lowers `intValue()`/`longValue()` to the identity and treats `int`/`long` as unbounded; the JVM wraps | The processor rejects them in contract code (D2) |
| F8 | In a long-lived Gradle daemon, rebuilding a jar that is on the processor path can make JuLC's `META-INF/plutus-sources` scan silently find nothing (stale `JarFile` cache; `IOException`s are swallowed) | In this build, consume `julc-state-api`'s bundled sources as a **directory**, not a jar; report upstream |

## 2. Decision summary

1. Ship **Option A**: one state UTxO per contract instance, identified by a thread token. Storage is **INLINE**
   (the state record is the inline datum), and execution is **direct** (the user's transaction spends the state UTxO).
2. **One script per contract type, many instances.** A `@MultiValidator` (MINT + SPEND) per `@StateContract`
   class, not parameterised by the seed. An instance is identified by its thread-token **name**,
   `uniqueTokenName(seed) = blake2b_256(seedTxId ‖ I2OSP(seedIndex, 2))`. The policy id equals the script hash equals
   the address credential. All instances live at the same **enterprise** script address, with no stake credential.
3. A **`Ctx` record** carries the transaction facts an action may read (`caller`, `received`, `balance`, `validFrom`,
   `validTo`). The client supplies it in the redeemer, and the generated validator checks that every field **equals**
   the corresponding fact of the transaction before calling the user's method.
4. An action **returns** its payouts: either the state type `S`, or a contract-declared record `(S state,
   JulcList<Payout> payouts)`. `@Destroy` returns `JulcList<Payout>`.
5. The processor builds a **State IR** and generates code from it through **strategy interfaces**:
   `StorageStrategy` (INLINE only), `ExecutionMode` (DIRECT only) and `TxLevelValidation` (SPEND only). Later layers add
   implementations instead of rewriting generators.
6. Code is generated from **string templates** (no JavaPoet), so every construct emitted is one we know JuLC accepts.
7. Annotations in milestone 1: `@StateContract`, `@Init`, `@Action`, `@View`, `@Destroy`, `@Payable`,
   `@SignedBy`/`@OnlyOwner`, `@ValidAfter`/`@ValidBefore`. Each compiles to a check you could write by hand.
8. Four examples (counter, vault, escrow, crowdfund) must each pass JVM tests, on-chain tests including a
   generated **attack suite**, and an end-to-end run on Yaci DevKit.

## 3. Developer API (`julc-state-api`)

All of it is JuLC-compilable and JVM-runnable, and ships its sources as library sources (F1).

### D1. Annotations

| Annotation | Target | Attributes | Meaning |
|---|---|---|---|
| `@StateContract` | top-level class | `maxDatumBytes` (default 512), `minAda` (default derived: `4310 × (160 + 200 + maxDatumBytes)`, rounded up to 0.1 ADA; an explicit lower value is an error), `maxValidityMs` (default 1 h) | Marks the contract. Until the upstream meta-annotation change, the class must **also** carry `@OnchainLibrary`; the processor reports an error with the fix if it doesn't |
| `@Init` | method | – | Exactly one. Creates an instance |
| `@Action` | method | – | A state transition |
| `@Destroy` | method | – | Ends the instance: burns the thread token and pays out all funds |
| `@View` | method | – | Read-only, off-chain only; becomes a client getter |
| `@Payable` | `@Init`/`@Action` | – | The call may add lovelace (`ctx.received() > 0`). Without it, `received` must be 0 |
| `@SignedBy("field")` | `@Action`/`@Destroy` | state field name | The transaction is signed by the `byte[]` key hash in that field of the **current** state |
| `@OnlyOwner` | `@Action`/`@Destroy` | – | Same as `@SignedBy("owner")` |
| `@ValidAfter("field")` | `@Action`/`@Destroy` | state field name | The transaction's validity interval starts at or after that `BigInteger` POSIX-ms field |
| `@ValidBefore("field")` | `@Action`/`@Destroy` | state field name | The transaction's validity interval ends at or before that field |

All annotations use `@Retention(CLASS)` and no `@Documented` (F2).

### D2. Method shapes

The processor enforces these and reports violations as compiler errors on the offending element. `S` is the state
type. `Args` are zero or more parameters of JuLC-encodable types (`BigInteger`, `byte[]`, `boolean`, records, sealed
interfaces, `JulcList`, `JulcMap`, `Optional`). `[Ctx]` is an optional **last** parameter.

| Kind | Shape | Notes |
|---|---|---|
| `@Init` | `public static S init(Args [, Ctx])` | `S` must be a record. The state type is defined as this method's return type |
| `@Action` | `public static S name(S, Args [, Ctx])` or `public static R name(S, Args [, Ctx])` | `R` is a record with exactly two components `(S state, JulcList<Payout> payouts)` (D5) |
| `@Destroy` | `public static JulcList<Payout> name(S, Args [, Ctx])` | May be repeated (escrow has `release` and `refund`) |
| `@View` | `public static T name(S, Args)` | No `Ctx`; never on-chain |

Further rules:
- Method names are unique among annotated methods (no overloading). A name maps to a redeemer variant `Capitalized`.
- `@SignedBy`/`@OnlyOwner` must name an `S` component of type `byte[]`; `@ValidAfter`/`@ValidBefore` must name a
  component of type `BigInteger`.
- `@Payable` without a `Ctx` parameter is an error, since the method could not see the amount.
- The whole class, views included, must stay inside JuLC's subset, because JuLC compiles the class source as a library.
- **Shared-execution subset** (F12). The processor rejects `int`, `long`, `short`, `char`, `float` and `double`, and
  calls to `intValue()`/`longValue()` (and the other narrowing conversions), anywhere in the contract class. These
  would make the JVM run and the on-chain run disagree. Identity equality on arrays is already rejected by JuLC
  (`JULC0087`). The testkit's differential helper (D15) checks agreement on concrete inputs, including numeric
  boundaries.

### D3. `Ctx`

```java
public record Ctx(byte[] caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo) {}
```

| Field | Meaning | How the validator verifies it (when the method declares `Ctx`) |
|---|---|---|
| `caller` | Key hash of the party calling (`msg.sender`) | `signedBy(tx, caller)` |
| `received` | Lovelace this call adds to the contract | `received >= 0`; `== 0` unless `@Payable`; enforced by the value equation (I3) |
| `balance` | Funds the call may allocate. In actions: lovelace before the call, minus the deposit. In `@Destroy`: **everything** the UTxO holds, deposit included | `== lovelaceOf(ownInput) − minAda` (actions), `== lovelaceOf(ownInput)` (destroy), `== 0` (`@Init`) |
| `validFrom` | Start of the validity interval (POSIX ms, inclusive) | The lower bound is `Finite` and **equal** to `validFrom` |
| `validTo` | End of the validity interval (POSIX ms, exclusive) | The upper bound is `Finite` and **equal** to `validTo`, and `validTo − validFrom ≤ maxValidityMs` |

The interval contains the current time, so equality plus the width cap bound how stale or how far ahead the two
fields can be (at most `maxValidityMs`). For deadlines, contracts use `Clock.after(ctx, t)` (`validFrom ≥ t`: at or
after `t`) and `Clock.before(ctx, t)` (`validTo ≤ t`: strictly before `t`), which hold for every moment the transaction
could be valid. The client picks **slots** first (tip, tip + TTL) and derives the milliseconds from them with the
network's slot config, so the JVM run and the on-chain run see identical numbers.

A method without `Ctx` gets no caller check and no time bounds, which is cheaper. Its value must be unchanged
unless it returns payouts, and then `received` is 0.

### D4. `Payout`, `Payouts`

```java
public record Payout(byte[] to, BigInteger lovelace) {}     // to = payment key hash
public final class Payouts {                                // @OnchainLibrary
    public static JulcList<Payout> none();
    public static JulcList<Payout> of(byte[] to, BigInteger lovelace);
    public static JulcList<Payout> add(JulcList<Payout> ps, byte[] to, BigInteger lovelace);
    public static BigInteger total(JulcList<Payout> ps);
}
```

Milestone 1 pays lovelace to public-key payment credentials only (28-byte key hashes, checked on-chain). The
validator matches the payment credential, so the runtime pays to the recipient's base address when it knows it (the
caller's own address, or an address book entry) and to the enterprise address otherwise. A payout below the ledger's
min-UTxO is topped up by the fee payer; the check is "paid ≥ owed". Script recipients, stake-part pinning and native
tokens come later.

### D5. Returning payouts without generics

Because generic records are rejected (F3), a contract that pays out declares one record, and the processor
recognises it **by shape**:

```java
public record Result(State state, JulcList<Payout> payouts) {}
```

Alternatives considered:
- A mutating `ctx.pay(...)` accumulator. Impossible, since JuLC locals and records are immutable.
- A processor-generated `VaultResult` type that user code references before it exists. That works in javac (types are
  resolved after generation), but it's IDE-hostile and makes the user's source depend on generated code that JuLC
  must also see.
- Having the client choose payouts and the action validate them. An action that forgot to validate would let the
  caller drain the contract, so this is unsafe by default.

### D6. State type and datum

The datum is the user's state record **as is**, with no framework envelope. It's readable via the CIP-57 schema, and
the inline datum is structurally equal (`equalsData`) to the action's result. Framework data (instance id, deposit) is derived from the
UTxO (the token name and `minAda`), not stored.

### D7. `Require`

```java
Require.that(boolean condition, String message)
```

The JVM class throws `RequireException(message)`. The **bundled on-chain source** (in `src/onchain/java`) does
`if (!condition) { ContextsLib.trace(message); Builtins.error(); }`. Both fail exactly when `condition` is false. This
is the one deliberate JVM/on-chain source split in the API. It's kept to a single four-line method, and a test
asserts that both variants fail on exactly the same inputs. Bare `Builtins.error()` still works in user code.

## 4. On-chain design (generated `XValidator`)

### D8. Instance identity

- `instanceId = ValuesLib.uniqueTokenName(seed)` = `blake2b_256(seedTxId ‖ I2OSP(seedIndex, 2))`. This is 32 bytes,
  so it's a valid token name. The seed is any UTxO the deployer spends. Ids are unique **per policy**: one seed can
  create one instance of each contract type in a single transaction, so other dApps must authenticate an instance by
  `(policy, instanceId)`.
- **MINT `Create(TxOutRef seed, args…, [ctx])`:**
  - The seed is consumed.
  - The transaction mints **exactly one** asset under this policy, `(policy, instanceId, 1)`, and nothing else
    under the policy.
  - Exactly one output carries the token.
  - That output's address is this script's **enterprise** address (no stake credential), and it has no reference
    script (I12).
  - Its inline datum equals `init(args, ctx)`, and the serialised datum is at most `maxDatumBytes` (I13).
  - Its value is exactly `{lovelace: minAda + received, policy.instanceId: 1}`.
  - `ctx` passes the checks in D3, with `balance == 0`.
- **MINT `Burn()`:** every entry under this policy has quantity `−1`. Which instances may be burned is decided by
  their SPEND `@Destroy` checks: the token can only leave a state UTxO through SPEND.

The seed is carried in the `Create` redeemer and must be **consumed**. Only the token name binds the instance to the
seed: one seed gives one instance, forever.

### D9. SPEND, per invariant

`ownInput` is the input being validated. It must hold exactly one token under our policy, with quantity 1, whose name
is `instanceId`. UTxOs at the address without a token are junk and cannot be spent (I10).

| Inv. | Check (actions) | Check (`@Destroy`) |
|---|---|---|
| I3 Value | Unique continuing output value `== {lovelace: L, policy.instanceId: 1}` with `L = lovelaceOf(ownInput) + received − total(payouts)` (exact: no token dust, no skim) | `total(payouts) == lovelaceOf(ownInput)`: everything, deposit included, is allocated by the contract's own rules |
| I4 Continuation | Exactly one output carries `policy.instanceId`, at the **same address** as `ownInput`, with an inline datum `== next` and no reference script | Mint contains `(policy, instanceId, −1)`, so no continuing output is possible |
| I13 Datum size | `serialiseData(next)` is at most `maxDatumBytes` | – |
| I14 Deposit | `L ≥ minAda`: payouts never spend the deposit | – |
| I5 Caller | `signedBy(tx, ctx.caller)` if the method declares `Ctx` | same |
| I6 Ctx | D3 checks | same |
| I7 Payouts | Each payout has `lovelace > 0` and a 28-byte `to`. For each recipient `r`, the sum of outputs whose payment credential is `PubKey(r)` and whose inline datum is `ownInput.outRef` must be ≥ the sum of payouts to `r` | same |
| I11 Policy annotations | `@SignedBy(f)`: `signedBy(tx, current.f)`. `@ValidAfter(f)`: the lower bound is `Finite` and `≥ current.f`. `@ValidBefore(f)`: the upper bound is `Finite` and `≤ current.f` (strictly before, since the upper bound is exclusive). An unbounded side never passes | same |

The **payout tag** (inline datum = the state input's `TxOutRef`) prevents double satisfaction: two instances, of this
or any other JOUST contract, spent in one transaction can't both count the same payout output.

The user's method runs on-chain on the decoded current state, the redeemer's arguments and the verified `Ctx`, exactly
as the client ran it on the JVM.

### D10. Transaction-level validation

The research recommends withdraw-zero transaction-level validation from day one. In Option A each spend validates
**only its own instance**: every check above is local to `ownInput`, its token and its tagged outputs, and nothing
iterates over all script inputs. Spending *k* instances costs *k* independent, small validations, so withdraw-zero
would add a script execution without removing any work. A therefore ships `TxLevelValidation.SPEND` only. The
interface exists so B/C/D can add `WITHDRAW_ZERO`, and later a CIP-112 guard, for checks that span inputs.

### D11. Invariant list (the attack suite in D16 must cover each)

| ID | Invariant |
|---|---|
| I1 | One seed gives one instance: the seed is consumed, exactly one token is minted, and its name is the seed hash |
| I2 | The initial datum equals `init(args, ctx)`, at this script's address |
| I3 | Exact value accounting (create, act, destroy); no token dust; destroy allocates everything |
| I4 | Exactly one continuing output, same address, inline datum equals the result |
| I5 | `ctx.caller` signed |
| I6 | `received`, `balance`, `validFrom` and `validTo` match the transaction |
| I7 | Payouts are paid, tagged with this instance's input |
| I8 | Destroy burns the token and allocates the whole balance |
| I9 | The burn redeemer only burns |
| I10 | Script-address UTxOs without the thread token are never spendable |
| I11 | `@SignedBy` and time annotations hold against the current state, with finite bounds |
| I12 | State outputs have the enterprise script address and no reference script |
| I13 | The serialised state never exceeds `maxDatumBytes`, so a call can't brick the instance by outgrowing `minAda` |
| I14 | Actions never spend the deposit: the continuing lovelace is at least `minAda` |

## 5. Processor (`julc-state-processor`)

### D12. Pipeline

`@StateContract` element → **model reader** (javax.lang.model, no tree attribution needed) → **validation** (D2;
errors reported on the element) → **State IR** → generators → `Filer` writes `XValidator.java` and `XClient.java`
into the contract's package in round 1. JuLC's processor compiles `XValidator` in round 2 (F7).

State IR (immutable records in `org.julclang.state.processor.ir`):

```
ContractIR(pkg, name, stateType, minAda, init: TransitionIR, actions: [TransitionIR], destroys: [TransitionIR],
           views: [ViewIR], storage: StorageStrategy, execution: ExecutionMode, txLevel: TxLevelValidation)
TransitionIR(kind, method, variant, params: [ParamIR], hasCtx, payable, returns: STATE | RESULT(type) | PAYOUTS,
             auth: [SignedBy(field)], time: [ValidAfter(field) | ValidBefore(field)])
```

The strategy interfaces generate the storage-, execution- and tx-level-specific fragments: how the current state is
read, how the next state is checked, and where checks run. A ships one implementation of each.

### D13. Generated client (`XClient`), shape

```java
XClient client = XClient.deploy(runtime, alice, args..., [lovelace if @Payable]);
XClient client = XClient.at(runtime, instanceId);
TxResult r = client.increment(bob, BigInteger.TEN);      // @Action: (Account caller, args..., [lovelace if @Payable])
TxResult r = client.close(alice);                        // @Destroy
BigInteger n = client.count();                           // @View
State s = client.state();
```

Each action method:
1. Reads the current state UTxO and decodes `S`.
2. Builds `Ctx`.
3. Pre-checks the policy annotations, failing fast with clear messages.
4. Runs the user's method on the JVM. A failing `Require` throws here, and nothing is submitted.
5. Builds the transaction through the runtime (D14).

All tx plumbing lives in the runtime. The generated client is a thin typed facade.

## 6. Runtime (`julc-state-runtime`)

### D14. Responsibilities

- **`StateRuntime`:** holds `BackendService`, an optional `TransactionEvaluator`, `Network`, a `ChainTime` (slot ↔
  POSIX), the TTL and a retry policy.
- **`StateContractScript`:** loads the compiled script from `META-INF/plutus/XValidator.plutus.json` through
  `JulcScriptLoader`, and derives the policy id and address.
- **Locate:** find the state UTxO by asset unit `policy ‖ instanceId` at the script address.
- **Build:**
  - create: seed, mint, output;
  - act: spend with redeemer, continuing output, tagged payouts, validity interval, required signer;
  - destroy: spend, burn, tagged payouts.
- **Submit:** evaluate (local JuLC evaluator or backend), sign, submit, then wait until the state UTxO moves.
- **Contention, without double execution:** the runtime retries only when the node **rejected** the submission
  (nothing was accepted) **and** the state UTxO it spent has moved. It then re-reads, re-runs on the JVM and resubmits,
  up to N times. Once a submission is accepted, the runtime never resubmits. It waits for the transaction itself to
  be included, not for "some" successor state. A timeout raises `PendingTransactionException(txHash)`, leaving the
  caller to decide.
- **Exact outputs:** before submitting, the runtime checks that the built transaction's state output has exactly the
  expected value and datum, so a builder-side min-ADA adjustment can't silently break I3. It also checks the datum
  size client-side.
- **Time:** `ChainTime` converts slots to POSIX ms (zero time, zero slot, slot length; for Yaci DevKit, from its admin
  API). The same config goes to `JulcTransactionEvaluator`.
- **Errors:** `RequireException` (rule failed on the JVM), `ContentionException`, `SubmitException`,
  `PendingTransactionException`.
- A retried action re-runs against the **new** state. Callers that need a guard (for example a slippage limit) put it
  into the action's arguments and check it with `Require` (documented).

## 7. Testkit (`julc-state-testkit`)

### D15. Harness

`StateContractHarness` builds `ScriptContext`s for create, act and destroy that mirror what the runtime submits. It
evaluates the compiled validator in the JuLC VM and reports budgets. Its **consistency** helper runs the method on the
JVM, builds the matching transaction, and asserts that the validator accepts it.

### D16. Attack suite

`AttackSuite.of(harness, scenario)` takes any accepted act or destroy scenario. For each mutation below that
**applies** to the scenario, it asserts the mutation is rejected. For example, "caller not signed" applies only to
methods that declare `Ctx` or a `@SignedBy`, and over-paying a payout is allowed. The mutations:
- wrong datum;
- extra token in the output (dust);
- value skimmed (−1 lovelace) and value added (+1 lovelace);
- thread token missing, or sent to another address;
- two continuing outputs;
- caller not signed;
- `received` / `balance` / `validFrom` / `validTo` lied about;
- payout untagged, under-paid, or paid to someone else;
- destroy without burn;
- spending a junk UTxO;
- seed not consumed, two tokens minted, and a wrong token name (create);
- stake credential added to the state address, reference script attached, datum hash instead of inline datum;
- `validFrom`/`validTo` not equal to the bounds, an unbounded side, an interval wider than `maxValidityMs`;
- payouts exceeding the balance (I14), and a state grown past `maxDatumBytes`.

On top of the per-scenario mutations, the suite composes transactions:
- two instances in one transaction sharing one payout output (double satisfaction);
- duplicate payouts to one recipient (aggregation);
- a create and a burn under one policy in one transaction (mixed mint).

Every example runs it.

## 8. Build integration

### D17. Consumer wiring (milestone 1, in this repo)

```groovy
dependencies {
    implementation project(':julc-state-runtime')                 // brings julc-state-api
    annotationProcessor project(':julc-state-processor')
    annotationProcessor libs.julc.annotation.processor
    annotationProcessor project(path: ':julc-state-api', configuration: 'onchainSources')   // F1, F8
}
```

`onchainSources` is a consumable configuration whose artifact is the **directory** of bundled sources (F8). A Gradle
plugin, `org.julclang.state`, will replace this block later (out of scope).

Versions come from `gradle/libs.versions.toml`. JuLC is consumed from Maven local until a release includes what we
use.

## 9. Examples and acceptance criteria

| Example | Exercises |
|---|---|
| `counter` | Actions without value, an anyone-can-call action, `@OnlyOwner` reset |
| `vault` | `@Payable` deposit, owner withdraw after `unlockAt` (`@ValidAfter`), `Result` payouts, `@Destroy close` |
| `escrow` | `@Payable @Init` (the buyer funds it), two `@Destroy`s (`release` to the seller by the buyer or arbiter; `refund` to the buyer by the seller or arbiter, or by anyone after the deadline) |
| `crowdfund` | `JulcMap` state (contributions), payable `contribute` before the deadline, `refund` action paying the caller, `@Destroy claim` by the creator if the goal is met after the deadline |

The milestone is done when:
1. `./gradlew build` is green, and the hand-written `examples/counter/generated/*` files are deleted, replaced by
   processor output.
2. Each example has JVM tests, on-chain harness tests (happy paths plus D16), and a DevKit integration test (tagged
   `devnet`, skipped when Yaci DevKit is unreachable) that has been **run and passed** against a live DevKit.
3. Processor diagnostics for each D2 rule are covered by compile-testing tests.
4. The generated validator size and the per-action CPU/mem budgets of each example are recorded in its README.

## 10. Consequences

**Positive:** one reusable script per contract type, ready for reference scripts and indexing by address. Value
movement is safe by construction (exact accounting plus tagged payouts). Every annotation expands to visible
generated code.

**On B/C/D (corrected after review):** A's IR records the strategy choice, and keeps the user-facing logic (state
transition, payouts, authorisation and time obligations) separate from the INLINE/DIRECT/SPEND lowering. That is a
seam, not a proof. MERKLE/SHARDED storage needs logical collections and access sets. Batching needs caller evidence
other than settlement signatures, and payout identities finer than the state input. ADR-0002 extends the IR for
these and may change the generators. **The commitment is that A's user-facing API and A contracts keep working
unchanged**, not that the generators survive untouched.

**Negative and limits:**
- Throughput is one write per block per instance (inherent to A).
- `minAda` is a fixed deposit sized for `maxDatumBytes` at today's `coinsPerUTxOByte` (4310). If governance raises
  that parameter, existing instances may need a top-up mechanism (future `@Deposit`).
- Every action that declares `Ctx` carries five extra integers or bytes in its redeemer.
- Payouts go to key hashes only.
- Until upstream changes land, the user's class carries both `@StateContract` and `@OnchainLibrary`.
- Junk UTxOs sent to the script address are locked forever, and anyone can create instances with any init
  arguments (for example naming a victim as seller). Front ends must show who created an instance and with what
  arguments.
- With several signers, the transaction builder chooses which one is `caller`.

## 11. Out of scope (later ADRs)

`@Param`, `@Deposit`/min-ADA top-up, reference-script deployment helper, token payouts, script recipients,
`@Anonymous`/`@MultiSig`/`@ScriptCaller`, `StateMap` and storage strategies (B), `@Owned`/`@Shared` (C), `@Batched` (D),
the `.jsc` DSL (E), events/indexer, `@Invariant`, upgrades, the Gradle plugin, CIP-57 blueprint enrichment.

## 12. Upstream follow-ups (JuLC)

1. Accept annotations meta-annotated with `@OnchainLibrary` (drops the double annotation).
2. Library-source scan: don't swallow `IOException`s, and open jar resources without the URL cache (F8).
3. Consider shipping `Require`-style helpers in the stdlib with a message-preserving JVM implementation (F5).

## 13. Open questions

1. Should a junk UTxO at the script address be recoverable, for example by its sender, rather than locked?
2. Should `Ctx` be split, so an action can declare only `caller` without time bounds?
3. Should payouts pin the recipient's stake part (a full `Address`) to protect delegation and voting power?

## 14. Review r1 and its resolution

Three independent reviews: an eUTxO security review (S), a JuLC/CCL feasibility review against the sources (F), and
Codex (C). All three asked for changes. Resolution:

| Finding | Source | Resolution |
|---|---|---|
| Unbounded validity defeats deadline checks (`finite*Bound` returns −1) | S11, F1, C1 | Generated helpers pattern-match `Finite`, and an unbounded side fails (D3, D9) |
| Ctx time fields only one-sided, so the caller controls them | S1, F1, C5 | Equality with the bounds plus `maxValidityMs`; `Clock.after/before`; `[from, to)` documented (D3) |
| Datum growth can brick an instance (min-UTxO, tx limits) | S2 | `maxDatumBytes` checked on-chain; `minAda` derived from it (I13) |
| Stake credential of the state address unconstrained | S3 | Enterprise address only (I12) |
| Payouts can spend the deposit | S4, C4 | `L ≥ minAda` (I14) |
| Reference script on state outputs | S5 | Forbidden (I12) |
| Deposit leaks to the destroyer | S6, C6 | Destroy allocates everything; `ctx.balance` in destroy includes the deposit (I3, D3) |
| Payout recipient: stake part, malformed key, min-UTxO | S7, S8, F6, C7 | 28-byte check; base address where known; top-up by the fee payer; stake pinning left open (§13) (D4) |
| Retry can execute an action twice | C3 | Retry only on rejected submission; wait for the submitted tx; `PendingTransactionException` (D14) |
| JVM/on-chain numeric divergence | C2 | Processor rejects `int`/`long`/narrowing calls; differential tests (D2, F12) |
| IR doesn't prove B/C/D pluggability | C8 | Claim corrected (§10) |
| Attack suite: conditional mutations, compositions | S13, C9 | D16 extended |
| `getInlineDatum` aborts on non-inline outputs | F2 | Payout scan pattern-matches `OutputDatum` |
| `credentialHash` matches script credentials too | F3 | Payout scan pattern-matches `PubKeyCredential` |
| Slot/time conversion gaps | F4 | Slots first, ms derived; `ChainTime`; the evaluator always gets a `SlotConfig` (D14) |
| `JulcMap` semantics | F5 | F11; the crowdfund example uses `delete` + `insert` |
| `Payouts` signatures not public | F7 | Fixed (D4) |
| Use `uniqueTokenName` | F9 | D8 |
| Datum equality is structural | S12 | Reworded (D6) |
| Spoofed instances, caller ambiguity, retry against changed state | S9, S10 | Documented (§10, D14) |
| `JulcAnnotationProcessor` is not incremental | F12 | Noted; the JOUST processor claims only `org.julclang.state.api.StateContract` |
