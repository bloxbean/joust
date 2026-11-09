# ADR-0007: Option G — native assets, minting and general outputs

**Status:** Accepted with changes (r2): a security reviewer and Codex both returned "request changes"; resolved in §6
**Date:** 2026-11-07
**Builds on:** ADR-0001 (A: I3 exact value, I7 tagged payouts), ADR-0003 (C: token kinds), ADR-0004 (D).
**Stack:** #1–#7, then this.

## 1. Context

Today a JOUST state UTxO holds exactly `{lovelace, thread token}` (I3), and an action can only pay lovelace to key
hashes, tagged with its own input reference (I7). That rules out most real contracts:
- a DEX pool holds a token reserve and mints LP tokens;
- an NFT marketplace holds the NFT it sells and delivers it to the buyer;
- a treasury holds tokens, pays royalties to a script, or deposits into another protocol with a datum.

A hand-written validator (JuLC or Aiken) can do all of this. This ADR closes the gap while keeping JOUST's
guarantees: the value of every state UTxO stays exact, and no output can pay for two obligations.

## 2. Decision

### D1. Developer API

```java
public record Funds(byte[] policy, byte[] self, Value received, Value held) {}   // julc-state-api
public record Send(Address to, Value value, OutputDatum datum) {}

@Action @Payable
static Swap swap(Pool p, BigInteger minOut, Ctx ctx, Funds funds) { ... }
public record Swap(Pool state, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted) {}
```

- **`Funds`** is a trailing parameter, after `Ctx` if there is one. Its fields:
  - `policy`: this contract's policy id (its script hash), which a method needs to name its own tokens;
  - `self`: the token name of what the method acts on (an instance id, or an object id for owned methods);
  - `received`: native assets the caller offers in this call, with no lovelace entry (lovelace stays in
    `Ctx.received`);
  - `held`: the native assets the UTxO holds before the call, without lovelace and without its own thread token.

  Only `received` travels in the redeemer. The validator computes `policy`, `self` and `held` from the transaction
  (r2, finding S11).
- **A result record** may have any of the components `state`, `payouts`, `sends`, `mint` and `accepted`, recognised
  by name and type. `state` is required for actions and inits, and forbidden for destroys. A destroy may still return
  a bare `JulcList<Payout>`.
- **`Send`** is an output to any address (key or script), with any datum (none, hash or inline) and any value,
  native assets included. `Payout` stays as the cheap form for lovelace to a key.
- **`mint`** is a `Value` of user tokens this contract's policy mints (positive amounts) or burns (negative).
- **`accepted`** is the value of native assets the method takes in. The validator requires
  `funds.received == accepted` exactly (r2, S3).
  - A `@Payable` method with `Funds` must return `accepted`.
  - A method that will take anything says so by returning `funds.received`.
  - This stops a caller pushing junk tokens into a UTxO, which would brick it by min-UTxO or by the size of its value.
- `@Payable` governs both `Ctx.received` and `Funds.received`. Without it, both must be zero.
- `@Payable` destroys are now allowed: a buyer pays a price that the destroy pays on to the seller.
- `@Init` and `@OwnedInit` may take `Funds` and return `(state, accepted)`. They cannot mint or send.
- `@Batched` cannot be combined with an asset-aware contract (processor error; r2, S10/C5).

### D2. Asset-aware contracts and the generalised exact value (I3 → I26)

A contract is **asset-aware** if any method takes `Funds` or returns `sends` or `mint`. Other contracts keep today's
cheaper lovelace-only check. For an asset-aware contract every transition checks, over full multi-asset values:

```
out.value == in.value + lovelace(ctx.received) + accepted + mint − Σ payouts − Σ sends.value
```

- `mint` here is the declared user mint. The lifecycle burn is not part of it.
- **For creation**, the input value is `MIN_ADA` plus the minted thread token.
- **For a destroy**, there is no output. The right-hand side must equal exactly `{policy.self: 1}`, the token that
  the lifecycle burn removes, which is checked separately (r2, C3).
- The comparison is Data equality of the canonical encodings (`valueData` of the computed native value against the
  ledger's output value). `valueContains` never sees a value that may hold negative quantities (r2, C4).
- The output's lovelace is at least `MIN_ADA` (I14).
- `MIN_ADA` covers `@StateContract(assetSlots)` assets (default 4 for asset-aware contracts) on top of the datum
  (r2, S9).

The generated code uses the PV11 native Value builtins:
- **Ledger values and the redeemer's `received`:** converted with the strict `unValueData`. The runtime makes
  `received` canonical.
- **Values a method computes** (`sends.value`, `mint`, `accepted`): converted with an `insertCoin` fold over their
  entries. The fold accepts any order and any zero entries, and it checks signs explicitly: send values strictly
  positive, accepted strictly positive, mint non-zero (r2, S6/C4).
- **Contract code** uses the new `Assets` helper. It has the same API on the JVM and on-chain (native builtins), and
  always returns canonical values.

### D2b. Reserved tokens never move (r2, S4)

- `received`, and every value a method computes, must hold no reserved-kind name under the own policy.
- With the accounting above, a state or object UTxO therefore always holds exactly one reserved token, its own.
- Two instances can never be merged, and no lifecycle token can be smuggled into another UTxO.

### D3. Token kinds and user tokens under the contract's policy (I27)

The contract's policy mints both lifecycle tokens and **user tokens**. A token name's kind decides which it is:
- **reserved:** 32 bytes (an instance), or 29 bytes starting with `0x4F` (an owned object) or `0x49` (an intent);
- **user:** any other name, 0–31 bytes. This covers CIP-68 names up to label + 27 bytes. The common CIP-68 shape,
  label + a 28-byte hash, is 32 bytes and therefore reserved. Such tokens need their own minting policy.

Every check that looks for "the token" of a state UTxO now looks only at reserved kinds. So a state UTxO may hold
user tokens of its own policy, such as LP tokens waiting to be claimed. `onlyBurns` (BurnInstance) only accepts
reserved names, so a burn redeemer cannot destroy user tokens.

### D4. Minting by an action (I28)

New MINT redeemer: `ByAction(TxOutRef by)`.
- **MINT (`ByAction`)** checks that input `by`:
  - sits at the state address and holds a reserved token of this policy;
  - is spent with a redeemer whose constructor tag is one of the methods that can mint (known when the validator is
    generated).
- **SPEND (the minting method)** checks:
  - the transaction's mint redeemer for this policy is `ByAction(own.outRef)`, so only one transition per transaction
    owns the policy's mint;
  - every entry of the declared `mint` is under the own policy, has a user-kind name and a non-zero amount;
  - the whole mint under the policy equals the declared mint exactly, plus the own thread token's −1 for a destroy.
- **A method with a `mint` component that mints nothing** (an empty Value) needs no MINT redeemer. Its SPEND checks
  that the transaction mints nothing under the policy, or only the burn of its own token for a destroy.
- **Creation, owned-object creation, intents and BurnInstance** keep `mintsExactlyOne` / `onlyBurns`, so a
  transaction cannot mix lifecycle minting with user minting under one policy. This is the existing one-mint-redeemer
  limit.

### D5. General outputs and the output-claim registry (I29)

A `Send` cannot be tagged with the sender's input reference: its datum belongs to the recipient. Without a tag, one
output could satisfy two obligations: two JOUST instances owing Bob the same 10 ADA with the same datum would both
accept one 10 ADA output (double satisfaction).

**The registry.** A new shared withdraw-zero script, `OutputVerifier` (in `julc-state-verifier`, beside the map
verifier), carries a transaction-wide registry of output claims:
- **Redeemer:** `OutputClaims(JulcList<OutputClaim>)`, where `OutputClaim(BigInteger output, TxOutRef by)`.
- **Script:** checks that it runs as a withdrawal (purpose `Rewarding`), and that the indices are ≥ 0, strictly
  increasing and below the number of outputs (r2, S5/S7). So each output is claimed at most once.

**Each JOUST transition with sends:**
- reads the registry from `tx.redeemers()` (its presence proves the verifier ran);
- takes its own claims, those with `by == own.outRef`, in order;
- requires exactly one claim per send, matched in order;
- finds each claimed output by walking the output list with an explicit index.

**A claimed output must:**
- have `address == send.to`;
- have `datum == send.datum`;
- have no reference script;
- have `value ≥ send.value`, multi-asset (both operands non-negative);
- **not** be at a payment credential that any spent input of the transaction is at (r2, S2/C1). A continuing output
  of the own contract, of another JOUST contract or of any spent script can therefore never pass as a send. This
  includes sends to the own state address.
- **not** have an inline datum equal to the reference of any input of the transaction, or to `IntentRefund(ref)` of
  one (r2, S1/C1). JOUST payout and refund tags always take that form, so a send output can never be a payout or a
  refund, of this contract or of any other JOUST contract, including contracts built before G.

**Exclusivity.** Sends are unique through the registry. Payouts and refunds are unique through their tags, which name
distinct inputs. Continuations are excluded by credential. So every output counts for at most one JOUST obligation.

**Scope.** The guarantee covers cooperating validators: JOUST, and any script that tags with its own input reference
or reads this registry. A foreign spending validator that accepts any output paying Bob 10 ADA can still be
double-satisfied together with a JOUST send. That is a weakness of that validator, not one JOUST can fix from
outside; composing with such scripts needs an adapter (ADR-0011) (r2, C2).

### D6. Client and runtime

- **Generated client:** methods accept `Value received` (assets) beside the lovelace argument.
- **Runtime:**
  - adds the user-token mint with `ByAction(own input)`;
  - adds each send as an output, records its index, and adds the `OutputVerifier` withdrawal (with its reference
    input) with the claims, sorted by output index;
  - publishes and registers the `OutputVerifier` once.
- **After balancing, the runtime re-checks** every send: address, datum, value ≥ `send.value` and claim index. QuickTx
  takes the fee from outputs at the fee payer's address, so a send there is refused before submission with a clear
  error. Use the recipient's enterprise address, as payouts do (r2, S8/C6).
- **Warnings:**
  - a send with `NoOutputDatum` to a script address (its funds would be locked);
  - a state output whose real min-UTxO exceeds its lovelace.

### D7. Testkit

- **`StateHarness`** models multi-asset inputs and outputs, user-token mints, the `ByAction` redeemer and the
  verifier withdrawal.
- **New attacks in `Attacks`:**
  - a foreign token added to, or one removed from, the state output;
  - a send's output under-filled by one unit of one token, or moved to another address;
  - a send's datum changed;
  - a send's claim missing, a duplicate claim, or a claim by another input;
  - one user token too many minted, or one too few;
  - `ByAction` naming another input;
  - a reserved-kind name in a declared mint.

### D8. Examples

- **`dex`:** a constant-product AMM for one ADA/token pair, with a 0.3 % fee.
  - LP token name = `blake2b_224(self)`, so it is unique per pool. It is minted on add and burned on remove.
  - Swaps in both directions, with a minimum-out slippage bound.
  - On DevKit: create a token, open a pool, add liquidity, swap both ways, remove liquidity.
- **`market`:** an NFT marketplace.
  - Each listing is an owned object holding the NFT (`@OwnedInit list` with `Funds`).
  - `buy` (`@OwnedDestroy`, `@Payable`):
    - pays the seller with a `Payout`;
    - delivers the NFT with a `Send` to the buyer;
    - pays a royalty with a `Send` to a script address with an inline datum.
  - `cancel` (`@SignedBy("seller")`) returns the NFT.

## 3. Invariants added

| ID | Invariant |
|---|---|
| I26 | In an asset-aware contract the state output's value is exactly the input value plus what was received and minted, minus what was paid and sent; a destroy allocates everything |
| I27 | Only reserved-kind names are lifecycle tokens. User tokens never satisfy a "the token" check, and lifecycle redeemers never mint or burn them |
| I28 | User tokens are minted or burned only through `ByAction(by)`, and only by the transition at `by`, whose method returned exactly that mint |
| I29 | Every send is matched by its own claim in the shared output registry, and each output is claimed by at most one input |
| I30 | A claimed output is never at a spent input's payment credential and never carries a JOUST payout or refund tag |
| I31 | `funds.received` equals the method's `accepted`; no reserved-kind token of the own policy is ever received, sent or minted |

## 4. Consequences

**Positive:**
- DEXes, marketplaces, treasuries and vaults that hold tokens become expressible.
- Contracts can pay into other protocols with a datum, and mint fungible tokens or NFTs.
- The same Java method still computes everything on the JVM.

**Costs:**
- Asset-aware contracts pay for full-Value accounting; native builtins keep that small.
- A transaction with sends runs one more shared script (the output verifier) and pays for its reference script.
- One more shared stake credential has to be registered (a 2 ADA deposit, once per network).

**Limits:**
- One minting transition per transaction per policy.
- No minting at init.
- An asset-aware contract cannot have `@Batched` actions.
- Foreign minting policies validate themselves. Composing with them is ADR-0011.

## 5. Out of scope

- Minting at `@Init`.
- Sends in `@Batched` actions.
- Reference scripts in sent outputs.
- Datum-hash witnesses beyond equality of the hash.
- Staking rewards of state UTxOs, which are still enterprise addresses (I12; see ADR-0010).

## 6. Review r1 and its resolution

S = security reviewer, C = Codex.

| Finding | Source | Resolution |
|---|---|---|
| **High.** A send's datum chosen to equal another instance's payout tag or `IntentRefund(ref)` satisfies both | S1, C1 | A claimed output's inline datum may not equal any input reference, or `IntentRefund` of one (I30). Attack tests cover it, including a payout of another instance in the same transaction |
| **High.** A claim lands on a continuing output of a spent script (own, JOUST or foreign) whose value is already there | S2, C1 | A claimed output may not sit at the payment credential of any spent input (I30) |
| **High.** Junk assets pushed in through `Funds.received` brick a UTxO (min-UTxO, value size) | S3 | `accepted` result component; `received == accepted` exactly (I31) |
| **High.** Own-policy lifecycle tokens in `received` merge two instances | S4 | No reserved-kind own-policy names in `received`, sends or mint (D2b, I31) |
| **High.** Wrong residual for destroys | C3 | The residual is `{policy.self: 1}`, excluding the lifecycle burn, which is checked separately (D2) |
| **High.** Foreign validators are outside the registry | C2 | Scope stated in D5; adapters in ADR-0011 |
| Medium. Claim indices without a lower bound | S5 | The verifier requires ≥ 0; lookup is an explicit walk |
| Medium. Strict `unValueData` on values a method computes; `valueContains` on signed values | S6, C4 | An `insertCoin` fold with explicit sign checks for method values; equality by canonical Data; containment only on non-negative values (D2) |
| Medium. Verifiers may run for other purposes (stake deregistration) | S7 | Both verifiers check `ScriptInfo` is `RewardingScript` |
| Medium. QuickTx takes the fee from a send at the fee payer's address | S8, C6 | Refused before submission; sends re-checked after balancing (D6) |
| Medium. Batches with assets unspecified | S10, C5 | `@Batched` is forbidden in asset-aware contracts |
| Low. `MIN_ADA` too low once assets are held | S9 | `assetSlots` in the deposit; runtime warning |
| Low. `policy`, `self` and `held` need not be in the redeemer | S11 | Computed on-chain; only `received` is in the redeemer |
| Low. CIP-68 32-byte names; `NoOutputDatum` to a script; non-JOUST scope; send order | S12 | Documented (D3, D5); runtime warning |

## 7. Implementation notes and measurements

**Script sizes.**
- Dex validator: 13 KB.
- Market validator: 12 KB.
- `OutputVerifier`: 697 B. Its hash changed in r2 for the purpose check, and so did the `MapVerifier`'s.

**Budgets** (harness, per script; transaction limit 10,000M CPU / 16.5M memory on DevKit):

| Call | Purpose | CPU | Mem |
|---|---|---|---|
| dex open | MINT | 112.9M | 365k |
| dex addLiquidity (LP minted and sent) | SPEND + MINT (ByAction) + VERIFIER | 303.9M + 57.8M + 21.2M | 1,007k + 214k + 86k |
| dex swapAdaForToken | SPEND + VERIFIER | 260.5M + 21.2M | 876k + 86k |
| dex swapTokenForAda | SPEND | 222.6M | 739k |
| dex removeLiquidity (LP burned) | SPEND + MINT + VERIFIER | 350.3M + 69.5M + 22.1M | 1,158k + 260k + 90k |
| market offer (NFT in) | MINT | 181.7M | 601k |
| market buy (payout + NFT send + royalty send) | SPEND + MINT + VERIFIER | 405.0M + 24.0M + 35.3M | 1,389k + 100k + 145k |
| market cancel | SPEND + MINT + VERIFIER | 227.8M + 24.0M + 21.2M | 801k + 100k + 86k |

**On DevKit:**
- **dex:** mint a native token, open a pool, add liquidity (LP tokens arrive at the provider), swap ADA → token
  with a slippage bound, swap token → ADA, remove all liquidity (every LP token burned), close.
- **market:** mint two NFTs, open the market, list, buy:
  - the seller receives 92.5 % plus the deposit;
  - the NFT goes to the buyer;
  - 5 % goes to a script address with an inline `Royalty` datum.

  Then list again, pause the market, and cancel through the escape hatch.

**Found while implementing:**
- **JuLC: a redeemer field typed `Value` is decoded as a constructor.**
  - Values are maps, so the decode fails (`UnConstrData`).
  - Workaround: the redeemer carries `PlutusData assetsIn`, cast on use. Values a method builds, such as `Send.value`
    or `mint`, are unaffected.
  - This is a JuLC follow-up.
- **JuLC: a native Value cannot be a loop accumulator.** Loops pack accumulators as Data. The generated folds use
  recursion instead.
- **JuLC: library methods cannot take `Object` or be generic.** So a record cannot be passed as data in shared code;
  `(PlutusData) (Object) r` works on-chain but throws on the JVM. Contracts build send datums with the JVM-runnable
  `Builtins.constrData` / `bData` / `iData`, and `Datum.inline` wraps the result.
- **QuickTx: a `ScriptTx` balances only lovelace from the fee payer.** The runtime collects the caller's UTxOs that
  hold the offered assets explicitly, without collecting a creation's seed twice.
- **Debugging aid:** `-Ajoust.traces=true` (Gradle `-Pjoust.traces`) makes every failing validator check trace its
  name. The harness reports the VM's error message.
