# ADR-0011: Option K — composition: several contracts in one transaction, reads, deposits, migration

**Status:** Accepted with changes (r2): a security reviewer and Codex both returned "request changes"; resolved in §5. D1, D2 and D4 are implemented; D3 (deposits) is designed but not yet implemented (§6)
**Date:** 2026-11-07
**Builds on:** ADR-0003 (`@Shared` reads), ADR-0007 (sends, the output registry, I29/I30), ADR-0009 (`@Params`).
**Stack:** #1–#11, then this.

## 1. Context

Contracts compose. A marketplace pays into an escrow; a lending pool reads an oracle; a contract is upgraded to a new
version. On Cardano composition happens at the transaction level: one transaction spends several scripts' UTxOs, and
each script checks its own part.

JOUST's validators already allow this. Each transition validates only its own input, and payout tags and send claims
keep the obligations of different inputs apart. What is missing is:
- **(a)** a runtime that builds one transaction from several calls;
- **(b)** a typed way to read another contract's state;
- **(c)** a safe way to pay into another contract's instance. A `Send` cannot do it: I30 refuses claims on a spent
  script's continuing output, and rightly, because that output's value need not have come from the sender;
- **(d)** moving an instance to a new contract version.

## 2. Decision

### D1. Atomic multi-call transactions (runtime)

```java
var tx = Compose.of(runtime, payer)
        .add(market.listing(id).buyCall(buyer, price))
        .add(vault.depositCall(buyer, amount))
        .submit();            // one transaction: every call's checks, or none
```

- **Calls.** Every generated call method gets a `…Call(...)` twin that prepares the call without submitting it.
- **Building one transaction (r2):**
  - **The validity window is chosen first,** as the intersection of what every call needs, and every call's method
    then runs on the JVM with that window. A call whose result depends on time is never reused with a different
    window.
  - **Refused before building:**
    - two calls on the same UTxO;
    - a seed reused;
    - an instance both spent and referenced;
    - two minting calls under one policy (the ledger allows one mint redeemer per policy).
  - **The output registry** is rebuilt after all outputs are placed. The indices come from the final order, and are
    re-checked after balancing.
  - **Each verifier withdrawal** appears once, with every call's claims merged.
  - **Signers.** Every call's required signers and co-signers are collected, and `Compose.signers()` shows each
    signer which calls its signature counts for. A key that is a required signer of one call also counts for
    another's `@SignedBy`/`@Quorum`. That is how the ledger works, so `Compose` refuses it unless
    `allowSharedSigners()` is called.
- **On-chain nothing changes:** each validator still checks only its own transition.

### D2. `@Reads`: another contract's state (I39)

```java
@Action
static State borrow(State s, Config cfg, @Reads("params.oracle") Oracle.State price, BigInteger amount, Ctx ctx)
```

- **Only params (r2).** `@Reads(path)` names a `ForeignInstance(byte[] policy, byte[] instance)` field of the
  `@Params` record. The full applied script hash is fixed at deploy, so it can never be a state field that an `@Init`
  or an action could set.
- **The generated check.** The reference input must:
  - hold exactly one token of `policy`, named `instance`, which is 32 bytes (the instance kind);
  - sit at an address whose payment credential is the script `policy`;
  - carry an inline datum.

  The datum is decoded as the foreign state record after a shape check: constructor 0 and the record's number of
  fields. JuLC casts are unchecked, so this check is what refuses another version's layout.
- **Not allowed:** foreign state with MerkleMaps (it has no proof path).
- **Staleness is the reader's rule.** A reference input does not run its validator, so a stale oracle stays
  readable. Oracle states should carry a timestamp, which readers check against `Ctx` with `Clock`.
- **Migration.** A reader whose params pin an oracle that later migrates must itself migrate (D4).

### D3. Deposits into another instance: accepted by the receiver (I40)

The r1 depositor-side check was unsound. A receiver's raw value delta can be credited to someone else, or come from
its own mint. In r2 the receiver owns the check:
- **The depositor** returns `deposits: JulcList<Deposit>`, where `Deposit(byte[] policy, byte[] instance, Value value,
  PlutusData memo)`. Its validator:
  - subtracts each deposit in its own value equation;
  - requires a `DepositClaim(output, by = own input, value, memo)` in the output registry, where `output` is the
    target's continuing output;
  - requires the target's token to be spent in the transaction, so that its validator runs.
- **The receiver** declares `Funds.deposits`: the deposit claims on its continuing output, each with its `by`, value
  and memo. Its value equation becomes `out = in + received + accepted + Σ deposits + mint − payouts − sends`. Its
  method sees each deposit and decides what to credit, for example to the beneficiary named in the memo. A receiver
  that does not take deposits requires that no deposit claim names its output.
- **The registry** (`OutputVerifier`) carries deposit claims separately from send claims. It requires each value to
  be canonical, non-negative and free of reserved-kind tokens, and allows several claims per output. Each claim is
  counted once by the receiver.

### D4. `@Migrate`: a two-sided handshake (I41)

**The old contract** declares the successor:

```java
@Destroy @SignedBy("admin") @MigrateTo("params.next")
static JulcList<Payout> upgrade(State s, Ctx ctx) { return Payouts.none(); }
```

Its validator requires:
- one token minted under `params.next`, named `blake2b_256(own input)`;
- that token's output at the `params.next` script address;
- that output's value at least the instance's value less its own token, less the method's payouts.

So the funds move only into the named successor.

**The new contract** adopts:

```java
@Migrate(from = "params.previous")
static State adopt(Config cfg, Old.State old, Ctx ctx) { ... }
```

Its creation requires:
- the seed is a state UTxO of `params.previous`: 32 bytes, with an inline datum, shape-checked as in D2;
- that seed's old token is burned (−1) in the same transaction, so the old instance ends;
- the new output's value is at least the old UTxO's value less the old token.

Both sides must agree: the old one names the new policy, and the new one names the old.

**Not allowed yet:**
- contracts with MerkleMaps, whose map claims of the old destroy and the new creation would share an owner key;
- contracts with owned objects, whose objects would be stranded.

## 3. Invariants added
## 3. Invariants added

| ID | Invariant |
|---|---|
| I39 | A `@Reads` value is the inline datum of the unique reference input holding the named foreign instance token at that policy's script address |
| I40 | A deposit is counted only by its receiver, which runs in the same transaction and counts each claimed deposit exactly once in its value equation |
| I41 | A migration needs both sides: the old instance burns its token and moves its value into an instance of exactly the policy it names, which in turn names the old policy |

## 4. Consequences and limits

- **Atomic multi-contract flows** without new trust: each script still checks only its own transition.
- **Foreign validators** that ignore JOUST's tags and registry can still be double-satisfied by a JOUST output
  (ADR-0007 D5). `Deposit` covers JOUST targets only.
- **Transaction limits.** Composition is bounded by the 16 KB size limit and the 10,000M CPU / 14M memory budget.
  Each contract adds its reference script's size fee and its execution.
- **Calls that both mint under the same policy** cannot be composed (the ledger's one redeemer per policy).

## 5. Review r1 and its resolution

S = security reviewer, C = Codex.

| Finding | Source | Resolution |
|---|---|---|
| **Critical.** A depositor-side delta can be credited to someone else, or come from a mint | S K1, C 1 | The receiver owns the check: `Funds.deposits` and the extended value equation (D3) |
| **High.** Deposit claims: negative or phantom amounts, multiplicity | C 2 | The registry requires canonical, non-negative values; the receiver counts each claim once |
| **High.** Migration did not require the old instance to end, nor that old side's consent | S K2, C 3 | A two-sided handshake: `@MigrateTo` on the old side and `@Migrate` on the new; the old token is burned (D4) |
| **High.** No funds path for migration | S K3 | The old side moves its value into the successor's output; the new side requires value at least the old (D4) |
| **High.** Foreign reads: token kind, a policy set in state, shape | S K5, C 4 | Params only, 32-byte kind, a single token, inline datum, a shape check; staleness documented (D2) |
| Medium. Claim keys collide when the seed is a spent input | S K4 | `@Migrate` is refused for MerkleMap contracts |
| Medium. `Compose` merges signers | S K6 | Signer summary; refused unless `allowSharedSigners()` |
| Medium/Low. Windows, duplicate inputs, registry indices, owned objects | S K7, C 5 | D1 rules; `@Migrate` refused with owned objects |

## 6. Implementation notes and measurements

**What is implemented:**
- **D1 (`Compose`)** captures ordinary client calls into one shared transaction, in two passes: first the windows,
  then every call in their intersection. It refuses:
  - a UTxO spent twice;
  - two mints under one policy;
  - an empty window;
  - shared signers, unless `allowSharedSigners()` is called.

  It merges the output-registry claims at their final indices and verifies every state output, payout and send after
  balancing.
- **D2 (`@Reads`):** the reference input must hold a single 32-byte token at the policy's script address, with an
  inline datum shape-checked as constructor 0 with the record's number of fields. The generated code now imports
  same-package types, because JuLC follows imports only.
- **D4 (`@Migrate`/`@MigrateTo`).** Pinning both sides through params is circular: v1's hash would depend on v2's and
  v2's on v1's. So the old side names its successor in a state field that its admin sets (`setSuccessor`), and the
  new side pins its predecessor in params. Migration is implemented for plain contracts. It is refused with
  MerkleMaps, owned objects or native assets.

**Not implemented:**
- **D3 (deposits into another instance).** It needs the output registry to carry deposit claims and the receiving
  contract's value equation to count them, which means a new `OutputVerifier` and a `Funds.deposits` view. That is a
  follow-up.
- **Value flows between contracts today** go through keys within one composed transaction: a contract pays a key
  (`Payout`/`Send`), and that key's payable call funds the other contract.

**Sizes:**
- Shop (with `@Reads`): 6.2 KB.
- Oracle: 5.6 KB.
- CounterV1: 5.9 KB; CounterV2 (with `Adopt`): 6.9 KB.

**Harness:**
- `@Reads`: a missing oracle, the token at a key address, another policy's token, two tokens of the policy, a datum
  of another shape, a stale price.
- Migration: a successor the admin did not name, value not conserved (both sides refuse), no successor minted, the
  old instance not ending.

**On DevKit:**
- a shop buys at an oracle's price through `@Reads`;
- `Compose` puts a shop purchase and a second oracle's post in one transaction, and refuses the same instance spent
  twice;
- a v1 counter migrates into v2 in one composed transaction, with its count and funds, after which v1 is gone and
  v2 continues.
