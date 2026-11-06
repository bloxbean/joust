# State-Oriented Smart Contracts on Cardano, on top of JuLC

> **Project name (decided 2026-11-06):** `julc-state`, a.k.a. **JOUST** (*Java On UPLC STate*, pronounced "jowst").
> Repo `bloxbean/joust`, artifacts `org.julclang:julc-state-{api,processor,runtime,testkit}`, packages `org.julclang.state.*`.

*Research note, 2026-11-06. Goal: let a Java developer write a contract as "state + operations"
(get/put/update, like a Solidity contract or a simple mint) and have the toolchain handle UTxOs,
datums, redeemers, thread tokens, value conservation, transaction building and contention.*

---

## 1. TL;DR

**Yes, this is feasible, and JuLC is unusually well positioned for it.** The decisive trick is
*"compute off-chain, re-verify on-chain with the same code"*:

```
nextState = action(currentState, args, txView)     // runs on the JVM to BUILD the tx
validator: action(datumIn, redeemer.args, ctx) == datumOut   // same method compiled by JuLC to CHECK it
```

JuLC already compiles a Java subset to UPLC, and its records, `JulcList`, `JulcMap` and stdlib also
run as plain Java on the JVM. That means one transition method can be the single source of truth for
both the transaction builder and the validator. Haskell (PlutusTx + plutus-apps), Aiken and Helios
can't do that without a second implementation. Scalus (Scala) is the only close peer.

What the developer stops thinking about: datum/redeemer encoding, thread NFTs, continuing-output
checks, double-satisfaction, min-ADA, value conservation, UTxO lookup, tx building, retries.

What can't be abstracted away and must show up in the language: **concurrency**. A single state
UTxO allows roughly one update per block. Every serious design below differs mainly in how it maps
state onto UTxOs to get around this. The language should make that choice an explicit, typed
*storage/concurrency strategy*, not hide it.

**Recommendation:** build it as a **Java-embedded framework** (annotations, with JuLC's annotation
processor generating validator, off-chain client and indexer glue). It should have a pluggable
**storage strategy** (single-UTxO → Merkle (MPF) map → sharded UTxO-per-key) and an optional
**batched/intent mode**. Add a dedicated syntax (a Solidity-like `.jsc` or an extension of JRL)
later, on top of the same intermediate model via ADR-056's `CompilerBackend`.

---

## 2. What already exists in the bloxbean stack (verified in local repos)

| Building block | Where | Why it matters |
|---|---|---|
| Java-subset → UPLC compiler, records, sealed interfaces, `@MultiValidator` (mint+spend+withdraw in one script) | `julc/julc-compiler`, `julc-stdlib/annotation` | Thread token mint and state spend can live in one script hash, so there's one address and one policy |
| On/off-chain unification: `JulcList`/`JulcMap` with JVM impls (`JulcArrayList`, `JulcAssocMap`) | `adr/next/onchain-offchain-unification-julclist-julcmap.md` | The same transition code can run off-chain |
| Record ⇄ PlutusData conversion | `julc-cardano-client-lib/.../ReflectivePlutusDataConverter`, `PlutusDataAdapter` | Off-chain datum encoding comes for free |
| Local tx evaluation against a real ScriptContext | `JulcTransactionEvaluator`, `julc-testkit` | Can dry-run every generated tx before submit (budget plus correctness) |
| Language-neutral backend (`FrontendProgram` → `CompilerBackend`, typed PIR, SPEND/MINT profiles) | `adr/056-language-neutral-compiler-backend.md` | A new DSL can target PIR directly without transpiling to Java |
| Experimental DSL precedent: JRL (Drools-like rules → Java → UPLC) | `julc-jrl`, `adr/julc_jrl/*` | Already has parser/type-checker/CLI/blueprint plumbing; it's the place to add `state` blocks |
| On-chain Merkle Patricia Forestry verifier (Aiken-compatible) | `julc-examples/.../mpf/MerklePatriciaForestry.java`, `MpfRegistryValidator` | Unbounded key→value state with an O(log n) proof per access |
| Off-chain MPF/JMT with RocksDB/RDBMS backends | `cardano-client-lib/verified-structures` | Off-chain side of a Merkleized state map |
| On-chain sorted linked list lib | `julc-examples/.../LinkedListLib.java` | Uniqueness/non-membership for the UTxO-per-key layout |
| QuickTx, blueprint, intents | `cardano-client-lib/quicktx` | Tx building for generated clients |
| TxFlow: multi-step flows, UTxO chaining, confirmation, rollback, retry | `cardano-client-lib/txflow` | Chaining of state transitions, rollback handling, retry on contention |
| Yaci Store / Yano | indexers | "Where is the current state UTxO?" and MPF mirror maintenance |
| CIP-113 programmable tokens in JuLC | `julc-cip113*` | Account-like token balances already modelled on UTxOs |

So most of the hard parts already exist as separate pieces. What's missing is the **state model
layer** that ties them together and generates the boilerplate.

---

## 3. The core mapping problem

| Account-model concept | eUTxO realisation the framework must generate |
|---|---|
| Contract instance / "address" | Script address + a **thread token** (NFT) minted once by the same `@MultiValidator`; token identity = contract identity |
| Storage | Inline datum of the thread UTxO (small), or a **Merkle root** in the datum (large), or **many UTxOs**, one per key/account (parallel) |
| `this.balance` | `Value` sitting in the state UTxO; framework derives the output value = input value + deposits − payouts and checks it exactly |
| `msg.sender` | No such thing. Map to a **required signer** (`extra_signatories`) or a credential witness (spent input from address X, or a withdraw-zero from stake credential X). The language exposes `caller()` and generates the check |
| `block.timestamp` | Tx validity interval; `now()` becomes `[lower, upper]` bounds; the framework sets the interval when building |
| Function call | Redeemer = `Action(name, args)`; spending the thread UTxO with that redeemer |
| View function | Off-chain read of the datum; on-chain via **reference input** (CIP-31) without contention |
| Contract → contract call | No synchronous calls. Composition is **atomic co-spending in one tx**: A's validator checks that B's thread input is spent with redeemer `B.X(args)` (inspect `txInfo.redeemers`) |
| Events/logs | Not on-chain. Derived by the indexer from (prevState, action, nextState) |
| Revert | Validator returns false/errors; off-chain dry-run catches it before submit |

Security invariants the generator should enforce in **every** generated validator (these are the
classic hand-written eUTxO bugs, which is why automating them is the main value):

1. Exactly one continuing output carrying the thread token, at the same script address.
2. Output datum is inline and equals `action(inDatum, args)`, compared as encoded Data.
3. Output value equals the expected value exactly (no token dust or "datum bloat" griefing), plus
   framework-computed min-ADA.
4. **Double-satisfaction protection**: when several state UTxOs (or several instances) are spent in
   one tx, each output is bound to its input (e.g. by thread-token identity or an input index in
   the redeemer).
5. Thread token can only be minted once (one-shot seed UTxO) and only into a valid initial state;
   it can only be burned by a declared `@Destroy` action.
6. Bounded state size and execution budget, checked by JuLC budget estimation at build time.

---

## 4. Options

Each option is framed as *what the developer writes* → *what gets generated* → *trade-offs*.
They are **layers rather than competitors**: A is the foundation, B and C are storage strategies
plugged into A, D is a concurrency mode, and E is a surface syntax over all of them.

### Option A: Annotation-driven state machine in Java (single state UTxO)

*Lowest risk. Builds directly on today's JuLC. Recommended first milestone.*

```java
@StateContract                                      // new annotation
public class Counter {

    public record State(BigInteger count, PubKeyHash owner) {}

    @Init
    static State init(PubKeyHash owner) {
        return new State(BigInteger.ZERO, owner);
    }

    @Action
    static State increment(State s, BigInteger by, Ctx ctx) {
        require(ctx.signedBy(s.owner()), "owner only");
        require(by.signum() > 0, "positive");
        return new State(s.count().add(by), s.owner());
    }

    @Action
    static State withdraw(State s, BigInteger lovelace, Ctx ctx) {
        require(ctx.signedBy(s.owner()), "owner only");
        ctx.pay(s.owner(), Value.lovelace(lovelace));   // payout from contract balance
        return s;
    }

    @View static BigInteger count(State s) { return s.count(); }
}
```

**Generated (by an extension of the JuLC annotation processor):**

- On-chain `@MultiValidator`:
  - MINT: one-shot thread token (param = seed UTxO), checks the initial datum equals `init(args)`.
  - SPEND: decode `State`, switch on the redeemer `Action` sealed interface, recompute the next state,
    check invariants 1–6 above, and check `ctx.pay(...)` obligations (outputs to the recipient) and
    contract-balance delta.
  - `Ctx` is a thin, deterministic façade over `ScriptContext` (signers, validity range, payments,
    reference inputs). On-chain it compiles to stdlib calls. Off-chain the same interface records
    *intentions*: required signers, payouts, validity bounds.
- Off-chain `CounterClient` (plain Java + CCL):
  ```java
  var counter = CounterClient.deploy(backend, ownerAccount, ownerPkh);  // mints thread token
  counter.increment(BigInteger.TEN).signWith(ownerAccount).submit();     // finds UTxO, runs increment()
                                                                         // on JVM, builds/evaluates/submits
  BigInteger c = counter.view().count();
  ```
  It locates the current state UTxO by thread token (Yaci Store, Blockfrost, Ogmios), runs the
  transition on the JVM, builds the tx with QuickTx, dry-runs it with `JulcTransactionEvaluator`,
  submits, and on `BadInputs`/contention re-reads the state and retries (TxFlow).
- CIP-57 blueprint + a typed event stream (`onTransition(prev, action, next)`).

**Pros:** small, incremental, single source of truth, very Java-native (records + static methods
fit JuLC's existing immutable style), testable with plain JUnit (no chain needed for logic).
**Cons:** one update per block (~20 s) per instance. State must fit in the datum (practically a few KB
before tx-size and CPU/mem limits bite). Only `final`/immutable style unless the compiler adds
local mutation sugar.
**Effort:** ~1 annotation processor extension + 1 runtime lib + client generator. Weeks, not months,
for a prototype.

---

### Option B: Typed state collections with pluggable storage strategies

*The "account model" part: `mapping(address => uint)` without the developer seeing UTxOs.*

```java
@StateContract
public class Token {
    @Field BigInteger totalSupply;

    @Storage(MERKLE)                          // or INLINE | SHARDED  (see table)
    StateMap<PubKeyHash, BigInteger> balances;

    @Action
    void transfer(PubKeyHash to, BigInteger amt, Ctx ctx) {
        PubKeyHash from = ctx.caller();                  // = required signer, generated check
        BigInteger fb = balances.getOrDefault(from, ZERO);
        require(fb.compareTo(amt) >= 0, "insufficient");
        balances.put(from, fb.subtract(amt));
        balances.put(to, balances.getOrDefault(to, ZERO).add(amt));
    }
}
```

The same source compiles to different on-chain layouts depending on `@Storage`:

| Strategy | On-chain layout | `get` | `put` | Max size | Concurrency | Off-chain needs |
|---|---|---|---|---|---|---|
| `INLINE` | `JulcMap` inside the datum | lookup in datum | rebuild datum | ~hundreds of entries | 1 tx/block | nothing |
| `MERKLE` (MPF) | 32-byte MPF root in datum; redeemer carries proofs | `MPF.has(root,k,v,proof)` | verify old proof, compute new root | unbounded | 1 tx/block (root is one UTxO) | MPF mirror (CCL `verified-structures` + RocksDB/RDBMS), kept in sync by indexer, proofs generated per tx |
| `SHARDED` (UTxO per key) | Each entry is its own UTxO tagged with token `hash(key)`; existence/uniqueness via sorted linked list or minted key-NFT | reference input (no contention) | spend + recreate that entry UTxO | unbounded | **parallel across keys** | index entries by key (Yaci Store) |
| `OWNED` (Sui-style) | Entry UTxO sits at an address the *user* controls via a stake/payment credential + script rules (like CIP-113) | reference input | user spends own entry | unbounded | fully parallel per user | wallet-side discovery |

What the compiler does:

- Turns `StateMap` reads/writes inside an action into a **read set / write set** (statically or by
  recording off-chain execution). This becomes the list of proofs or entry UTxOs the tx must
  include. It's the same idea as Solana/Sui access lists, derived automatically.
- Off-chain, executes the action against the real state view (MPF mirror or indexed entries) and
  records the touched keys. On-chain, checks proofs and new root, or checks each touched entry's
  continuing output.

**Pros:** this is the real "user thinks in state" experience. Storage choice becomes a one-line
performance decision, and the MPF and linked-list primitives already exist in JuLC examples.
**Cons:** MPF needs a trusted-but-verifiable off-chain mirror (anyone can rebuild it from chain
history, but someone has to run it). SHARDED inserts need uniqueness machinery (linked-list node
insertion touches the predecessor, so inserts contend on neighbours). Per-tx CPU budget limits how
many keys one action can touch (each MPF proof is about 10–30k CPU steps per level; needs measuring).

---

### Option C: Global/shared vs owned state in the type system (Sui/CKB-inspired)

A refinement of B that makes the eUTxO concurrency model *visible but friendly*:

```java
@SharedState  Config config;                    // one UTxO, read via reference input, rarely written
@OwnedState(by = "holder") record Position(PubKeyHash holder, BigInteger collateral, BigInteger debt) {}
@SharedState(batched = true) Pool pool;          // hot shared state, written by batcher (Option D)
```

- **Owned** state: one UTxO per owner. Only that owner's txs touch it, so there's no contention.
  This is how Sui scales and how CIP-113/Indigo/Liqwid positions work.
- **Shared read-mostly** state: reference inputs, so unlimited parallel readers. Admin writes are rare.
- **Shared hot** state: must be batched (D) or sharded (B).
- Uses the **withdraw-zero / "observe" pattern** so that validation logic over many inputs runs once
  per tx instead of once per input. This is a big CPU saving and is supported today via `@MultiValidator`
  with a WITHDRAW entrypoint.

The compiler can **lint**: "action `liquidate` writes `@SharedState pool` → serialised to 1 tx/block;
consider `batched = true`." That gives developers the performance model in Solidity terms.

---

### Option D: Intent / batcher mode (for hot shared state)

For AMMs, order books, mints with public demand, or counters with many writers:

```java
@Action(mode = BATCHED, maxPerBatch = 20)
void deposit(BigInteger amt, Ctx ctx) { ... }   // same code as before
```

**Generated:**

1. **Request validator**: users lock an *intent* UTxO `{action, args, caller, refund-to, deadline}`.
   Users never touch the hot state, so there's no contention for them. Cancelable by the caller.
2. **Batch transition** on the state validator: folds `action` over N intents in canonical order
   (the order is fixed by the validator, e.g. input order or sorted), checks each user's payout/refund,
   and checks the final state.
3. **Batcher service** (generated Java, runs on Yaci Store/Yano): watches intents, runs the fold on
   the JVM, builds and submits. It's permissionless, since anyone can batch and correctness is enforced on-chain.

**Pros:** removes the throughput ceiling. This is the industry pattern (SundaeSwap, Minswap,
Indigo, Liqwid), so it's proven.
**Cons:** latency (two txs per user op), batcher incentive/fee design, ordering/MEV
fairness, and the "one fold per tx" CPU budget caps batch size. Semantics change from "my call
executes now" to "my request executes later or is refunded". The language should make that explicit
(`Future<Result>`-style client API).

---

### Option E: A dedicated state-oriented language (surface syntax)

Same semantics as A–D, with syntax that allows Solidity-like mutation and hides Java's
immutability ceremony:

```
contract Token {
    state  totalSupply: Int
    state  balances: Map<PubKeyHash, Int> @merkle

    init(owner: PubKeyHash, supply: Int) {
        totalSupply = supply
        balances[owner] = supply
    }

    action transfer(to: PubKeyHash, amt: Int) {
        let from = caller()
        require balances[from] >= amt  "insufficient"
        balances[from] -= amt
        balances[to]   += amt
    }

    view balanceOf(who: PubKeyHash): Int = balances[who] ?? 0
}
```

Implementation paths (in order of preference):

1. **Lower to a "State IR" and generate Java for A–D**, the JRL approach (ADR JRL-001:
   transpile to Java, then `JulcCompiler`). Cheapest, and it reuses everything, including JVM execution
   off-chain, because the generated Java runs on both sides. Mutation (`balances[from] -= amt`) is
   SSA-converted to immutable Java.
2. **Emit typed PIR via ADR-056 `CompilerBackend`** for the on-chain part, and generate Java/JVM
   bytecode or an interpreter for the off-chain part. Better codegen control, but the
   on/off-chain single-source property becomes a two-implementation consistency risk.
3. **Extend JRL** with `state`/`action` blocks for business-user audiences (compliance, CIP-113
   rules).

**Pros:** best ergonomics and familiarity for Solidity developers, and room for features Java can't express
(storage annotations on fields, `+=` on maps, `caller()` as a keyword).
**Cons:** a new language means IDE support, LSP, debugger, docs, and audit surface. Java developers
may prefer staying in Java. Do this **after** the semantics are proven in Java (A–D).

---

### Option F (longer-term): Execute off-chain, commit on-chain (L2 / rollup-style)

The state lives in an off-chain ledger (Hydra head, Midgard-style optimistic rollup, or an
app-specific sequencer). L1 holds only a state root (MPF/JMT) plus fraud or validity proofs. The
"language" is the same state-machine source. The difference is that actions are executed by an operator
and only disputed actions (or a zk proof) are verified by the JuLC validator. This gives real
account-like throughput, but it's an infrastructure project, not a language feature. Keep the State IR
compatible with it (pure, deterministic transitions, Merkle-ised storage) so it stays possible.

---

## 5. Comparison

| | A Single-UTxO SM | B Storage strategies | C Owned/shared | D Batched intents | E New syntax | F L2/rollup |
|---|---|---|---|---|---|---|
| Developer sees UTxOs? | No | No | Lightly (owned vs shared) | No (but async semantics) | No | No |
| Throughput | 1 tx/block/instance | INLINE/MERKLE: 1; SHARDED/OWNED: parallel | Parallel for owned | High | Inherits | Very high |
| State size | KB | Unbounded (MERKLE/SHARDED) | Unbounded | Inherits | Inherits | Unbounded |
| Off-chain infra | Indexer lookup | MPF mirror or key index | Index | Batcher service | Inherits | Operator/sequencer |
| Builds on existing JuLC/CCL | Fully | MPF + linked list exist | `@MultiValidator` + withdraw | TxFlow, Yaci Store | JRL, ADR-056 | verified-structures |
| Effort | S | M | M | M–L | L | XL |
| Risk | Low | Medium (proof costs) | Medium | Medium (economics) | Medium (adoption) | High |

---

## 6. Recommended architecture

```
                ┌──────────────────────────────────────────────┐
  Java (@StateContract)  ─┐                                    │
  .jsc / JRL state syntax ─┴─▶  State IR                       │
                ( schema, actions, read/write sets,            │
                  storage strategy, concurrency mode,          │
                  caller/value/time obligations )              │
                └───────┬───────────────┬──────────────┬───────┘
                        │               │              │
            On-chain codegen     Off-chain codegen   Infra glue
          (JuLC @MultiValidator  (Typed client:      (Yaci Store plugin:
           MINT+SPEND+WITHDRAW;   QuickTx + TxFlow,   locate thread UTxO,
           invariants 1–6;        JVM execution of    MPF mirror, intent
           MPF / linked-list      the same actions,   watcher / batcher,
           libs)                  dry-run evaluator)  event stream)
```

Design rules:

1. **Transitions are pure functions** `(State, Args, CtxView) → (State', Obligations)`. Obligations
   cover payments, mints, required signers and time bounds. Off-chain fulfils them and on-chain checks them.
2. **Same Java code on both sides.** That's JuLC's differentiator. Keep the action language inside
   the JuLC-compilable subset, and let the annotation processor reject anything that isn't.
3. **Concurrency is a declared property** (storage plus mode), with linting, not something the developer
   finds out about in production.
4. **Verify, don't compute, where possible.** For expensive steps (sorting, searching), off-chain
   supplies hints (indices, proofs) in the redeemer and on-chain only checks them. The framework
   generates the hint plumbing.

## 7. Suggested spike plan

| Step | Deliverable | Proves |
|---|---|---|
| 1 | `@StateContract` + `@Init/@Action/@View` → generated `@MultiValidator` + client, for **Counter** and **Escrow** on Yaci DevKit | Option A end-to-end; single-source on/off chain; invariants 1–6 |
| 2 | `StateMap` with `INLINE` and `MERKLE` → **ERC-20-style token ledger** using the existing JuLC MPF verifier + CCL MPF RocksDB mirror | Option B; measure CPU/mem per proof, max keys touched per tx |
| 3 | `SHARDED` strategy → **name registry / allow-list** using LinkedListLib | Parallel writes; insert/delete uniqueness |
| 4 | `mode = BATCHED` → **crowdfund/deposit pool** with a generated batcher | Option D throughput and batch-size limits |
| 5 | Prototype `.jsc` syntax that lowers to the same State IR (via JRL infra) | Option E ergonomics |

Open questions to settle in the spike:

- How is `caller()` defined for script-held funds and multi-sig? Proposal: required signer by
  default, plus `callerCredential()` for script or stake credentials via withdraw-zero.
- What's the upgrade story? Proxy pattern (the `CfProxyValidator` example exists) vs migrations that
  move the thread token to a new script.
- Who pays min-ADA for new map entries (SHARDED/OWNED), and how is it refunded on delete?
- Determinism: forbid anything in actions that doesn't compile to UPLC, so off-chain and on-chain
  results can't diverge. The processor enforces this.
- Mutation sugar in Java: JuLC currently allows reassignment only for loop accumulators. Option B's
  `balances.put(...)` style needs either an SSA rewrite in the processor or an explicit
  `balances = balances.put(...)` immutable style.

---

## 8. Ledger roadmap: design for V3 now, V4/Dijkstra next

*Status as found by web research on 2026-11-06. Some items come from secondary sources; re-check them before committing.*

| Feature | Status | Impact on the state layer |
|---|---|---|
| PV11 / van Rossem (new builtins, arrays CIP-138) | Live on mainnet since July 2026; JuLC's only target profile is `plutus-v3-pv11` | Cheaper indexed redeemers and larger datums: use it now |
| Withdraw-zero "transaction-level validation" | Works today (V3) | Default way to run the global logic once per tx (Options B, C, D) |
| CIP-112 observe/guard scripts | Dijkstra scope; not on mainnet | First-class replacement for withdraw-zero. Hide it behind one backend switch |
| CIP-118 nested transactions (unbalanced sub-txs) | Dijkstra scope, CIP still "Proposed"; TSC flagged MEV/rebundling risk | **Native intent primitive for Option D**: the user's sub-tx says "apply action X to state T", and the contract's guard validates the whole batch once. Requires Plutus V4 |
| CIP-159 account-address balances, Phase 1 | Dijkstra scope; ADA only, **no datum** | Contention-free fungible ADA balances (treasury, deposits). Not a general state store |
| Leios | Dijkstra scope | More txs per block for batchers; does **not** remove one-spend-per-UTxO contention; makes auto-retry more important |
| Dijkstra mainnet | Code-complete target Q4 2026; mainnet reported Dec 2026 – Mar 2027, governance pending | Plan the V4 backend as phase 2 |

Implication: keep the State IR independent of the V3 or V4 mechanism. Generated code goes through
a `TxLevelValidation` strategy (withdraw-zero today, guard later) and an `IntentTransport`
strategy (request UTxOs today, CIP-118 sub-txs later). Contracts recompile without source changes.

---

## 9. Prior art (summary)

The full survey is in Appendix A. The main lessons:

1. **Plutus `StateMachine` (plutus-apps)** was exactly "transition function → validator + off-chain
   code" with a thread token. It is now archived (Dec 2024). It failed on **single-UTxO contention, validator size,
   and the heavy PAB runtime**, not on the idea. Keep the typed transition and thread-token identity. Drop the
   single-UTxO default and the monolithic runtime. IOG's own guidance is to avoid single-threaded state machines.
2. **Every production protocol hand-mixes the same five storage classes**: per-user/owned UTxOs
   (Indigo CDPs, CIP-113), singleton thread-token UTxO (config), sharded aggregates (Liqwid's 8
   market UTxOs), Merkleised maps (MPF), and intent+batcher (SundaeSwap, Minswap). This confirms
   Options B, C and D as the core of the design, with the compiler choosing per field.
3. **Sui's owned vs shared objects** is the closest mental model: ownership declared in the type system,
   fast path for owned, sequenced path for shared. Cardano has no consensus sequencer, so "shared" must be
   batched or guarded, which is all the more reason to make the declaration explicit. **CKB's type scripts**
   (invariants checked on inputs and outputs of a cell type) map directly to a "state class + invariants" compiled to spend+mint.
4. **Bolting a full account VM onto a UTxO chain did not pay off**: Milkomeda's status is unclear, and Godwoken on CKB
   was sunset in 2025–26. Native UTxO abstractions are the better path.
5. **Structured Contracts in the EUTxO Ledger Model** (Vinogradova, Melkonian, Wadler, FMBC 2024) formalises
   "an abstract state machine is correctly implemented by ledger transitions" in Agda. Use its definition
   as the **correctness criterion** for the code generator. That gives a verification story the old StateMachine never had,
   and fits JuLC's existing `julc-verification` work.
6. **Nearest live peers**: **Scalus** (Scala→UPLC) is moving toward an off-chain app runtime and typed UTxO indexing,
   but has no declarative state model. **Tx3** (TxPipe) is a transaction-template DSL with multi-language client codegen.
   No maintained framework was found that compiles a declarative state model into on-chain and off-chain code with
   storage-class selection. **That is the gap.** Consider emitting CIP-57 blueprints and Tx3 templates so non-Java
   clients can call JuLC state contracts.
7. **Kadena Pact capabilities** (scoped rights such as `TRANSFER(from,to,amt)`) are a good model for expressing
   authorisation in the state API instead of raw `signedBy` checks.

### Adjustments to the recommendation after the prior-art review

- Make **per-user (owned) state the default** for `StateMap<Address/PKH, V>`. Singleton global state is opt-in,
  with a contention lint, rather than the starting point.
- Build the **transaction-level validation (withdraw-zero) path into Option A from day one**, so multi-instance
  txs stay O(1) and double-satisfaction or "missed input" checks are generated centrally.
- Keep generated validators small (shared library code, reference scripts). Size is what killed the PAB StateMachine.
- Avoid a monolithic runtime: generate a thin client plus optional services (indexer plugin, batcher) as separate artifacts.

---

## Appendix A: Prior-art survey

| Item | State mapping | Status | Lesson |
|---|---|---|---|
| Plutus `StateMachine` / PAB | 1 UTxO + thread NFT; transition fn → validator + off-chain | plutus-apps archived 2 Dec 2024 | Right abstraction; single UTxO, size and runtime were the problems |
| Structured Contracts (FMBC 2024) | Formal simulation: abstract transition system → ledger transitions (Agda) | Paper | Use as the compiler correctness spec |
| Marlowe | Single UTxO, interpreter validator, role tokens, merkleised continuations | Community-maintained, funding uncertain | Closed DSL is safe but adoption was small; still single-UTxO |
| Hydra / Hydrozoa / Midgard | L2s that are still eUTxO-isomorphic | Hydra live; Hydrozoa MVP; Midgard path to mainnet targeted end of 2026 | Cheap contention resolution; a deployment target, not an account model |
| Milkomeda C1 | EVM sidechain | Current status unclear | Leaving the ledger isn't the answer |
| Scalus | Scala→UPLC, blueprints, off-chain builder; 2026 plans for app runtime and indexing | Active | Nearest peer; no declarative state model |
| Aiken / Plutarch / Helios / plu-ts / OpShin | Raw datum/redeemer; patterns in libraries (Anastasia design-patterns) | Aiken dominant | No language ships a state abstraction |
| Tx3 (TxPipe) | Tx-template DSL; typed clients in several languages | Active | Off-chain interop target |
| Withdraw-zero, UTxO indexers | Global logic once per tx; index hints in redeemer | Widely used | Generate it; indexer libs don't stop double satisfaction by themselves |
| MPF (aiken-lang) | 32-byte root + proofs | Production | Good for big maps; still single-root contention |
| Linked list (Anastasia) | UTxO per key, sorted, NFT nodes | Production | Parallel per key, uniqueness proofs, min-ADA per entry |
| Batchers (SundaeSwap V3, Minswap), sharding (Liqwid), per-user positions (Indigo) | Intents + batcher; N shards; CDP per user plus global pool | Production | The five storage classes in practice; global pools stay hotspots |
| CIP-118 / CIP-112 / CIP-159 / Plutus V4 / Leios | Native sub-tx intents, guards, account balances | Dijkstra; not on mainnet yet | Future backend for intents and tx-level validation |
| Sui objects | Owned (fast path) vs shared (consensus) vs immutable vs wrapped | Production | Model for explicit storage classes |
| Nervos CKB | Cells with lock and type scripts; Godwoken account L2 sunset | Production / sunset | Type scripts ≈ state invariants; account-VM L2 didn't pay off |
| Fuel | Contract UTxOs with storage; users sign over contract ID | Production | Intents should name the thread-token ID, not an outref |
| Ergo | Multi-stage protocols via script hash chaining | Production | Same as the thread-token pattern, same contention |
| Kadena Pact | Tables + capabilities | Org shut down Oct 2025 | Capabilities as an authorisation model |

Links for every row are in `prior-art-survey.md` (same folder). The key ones:
plutus-apps repo (github.com/IntersectMBO/plutus-apps), Structured Contracts (doi 10.4230/OASIcs.FMBC.2024.10),
CIP-118/112/159 (cips.cardano.org), Dijkstra plan (product.cardano.intersectmbo.org/hardfork-planning/dijkstra),
Anastasia aiken-design-patterns, aiken-lang/merkle-patricia-forestry, SundaeSwap sundae-contracts,
Sui object-ownership docs, Nervos lock-vs-type docs, Fuel book, Scalus (developers.cardano.org), Tx3 (developers.cardano.org/tools/tx3).
