# ADR-0006: Option F — off-chain execution with on-chain commitments (optimistic L2 spike)

**Status:** Accepted as a spike (r2). A security reviewer and Codex both returned "request changes"; their findings
are resolved in §6 and implemented. This is a design and a **spike**, not a product.
**Date:** 2026-11-07
**Builds on:** ADR-0001 to ADR-0005. Research doc §4 Option F: "an infrastructure project, not a language feature.
Keep the State IR compatible with it."
**Stack:** #1 to #6, then this.

## 1. Context

Options A–E keep every state transition on L1, so throughput is bounded by blocks: one write per instance per block
in A, and about 8 per batch in D. Account-like throughput needs execution off-chain, with L1 holding only commitments
and a way to dispute them. The building blocks are Hydra heads, optimistic rollups (Midgard) and validity rollups.

JOUST has one property that makes the optimistic variant unusually cheap to build. **A contract's transition is a
pure Java method that already compiles to UPLC.** A fraud proof for one disputed step is just that method, run inside
an L1 validator. Nothing needs to be re-implemented for the dispute game.

## 2. Decision (the spike)

### D1. The L2 program

The program is a JuLC-subset library (`@OnchainLibrary`) with pure static functions over records:
- `step(state, order, sig) -> state`: **total**. An invalid order is a **no-op**. A fraud proof must be able to show
  that an invalid order was applied, and a step that fails on-chain can't prove anything, so validity is a predicate
  (`valid(...)`) rather than `require`. `valid` checks lengths and signs before anything that would fail on malformed
  input, such as the ed25519 builtin with a key that isn't 32 bytes.
- **Encodings are total and injective**, and the JVM and on-chain compute them identically without a Data serialiser:
  - every byte field is length-prefixed (`I2OSP(len, 2) ‖ b`);
  - every integer is a sign byte and its length-prefixed minimal magnitude, so negative and huge values still encode;
  - each commitment starts with its own domain tag (`joust-l2/order`, `joust-l2/state`, `joust-l2/log`);
  - the state hash includes the account count.

  So even a malformed log can be hashed, and disputed, and no two values share an encoding.
- **Orders** are signed with **ed25519** by the L2 account key over `"joust-l2/order" ‖ domain ‖ order`. The `domain`
  is part of the genesis state and unique per rollup, so an order can't replay across rollups or networks. Each account
  has its own **nonce** (an order must carry the sender's next one), so users sign independently of each other.

The spike's program is a payments ledger: `Book(domain, accounts: [Acct(key, balance, nonce)])` and
`Order(from, to, amount, nonce)`. Its semantics, stated and tested:
- an order needs a positive amount, the sender's nonce, enough balance and a valid signature;
- `from == to` moves nothing but uses up the nonce;
- an unknown `to` becomes an account, up to `MAX_ACCOUNTS = 32`, which bounds the pre-state a proof carries;
- an invalid order changes nothing, not even a nonce.

### D2. The L1 contract: checkpoints with a challenge window

`Rollup` is an ordinary JOUST `@StateContract` (Option A), holding:
- `operator` (key hash) and a bond (the instance's lovelace above min-ADA), at least `minBond`;
- `finalHash`: the hash of the last finalised L2 state;
- the pending checkpoint: `pendingLog = logHash(steps)`, `pendingCount`, `pendingHash` (the claimed new state), and
  `challengeUntil`.

`logHash` folds over the steps: `h₀ = blake2b("joust-l2/log")`, then `h ← blake2b(h ‖ order ‖ sig ‖ post)`, with the
encodings above. Each step records the order, its signature, and the claimed post-state hash.

| Action | Who | Rule |
|---|---|---|
| `commit(steps)` | operator | **The steps are in the redeemer.** The validator derives `pendingLog = logHash(steps)`, `pendingCount = len(steps)` (1–16) and `pendingHash` (the last step's post-hash). Requires nothing pending and bond ≥ `minBond`; sets `challengeUntil = validTo + window`. So every commitment is opened on-chain and challengeable, and the steps are on-chain for watchers |
| `finalize()` | anyone (`open`) | Pending, and the validity window lies entirely after `challengeUntil`. Then `finalHash = pendingHash` |
| `challenge(steps, index, pre)` | anyone (`open`) | Pending; the window lies entirely before `challengeUntil`; `0 ≤ index < pendingCount`; `logHash(steps) == pendingLog`; and **fraud** (below). The pending checkpoint is discarded. **Half the bond goes to the challenger and half is burned** (paid to an all-zero key nobody holds), so an operator gains nothing by challenging its own fraud |
| `rebond()` | operator | Payable; **only when nothing is pending**, so rebonding can't keep spending the state UTxO and crowd out challenges |
| `close()` | operator | Nothing pending; pays everything out |

**Fraud**, decided by `Ledger.fraud(finalHash, steps, index, pre)`: `hash(pre)` equals step *i*'s pre-hash
(`finalHash` for *i* = 0, otherwise step *i*−1's post-hash), and `hash(step(pre, order_i, sig_i))` differs from step
*i*'s post-hash. **This re-runs the L2 program on-chain.** No "tail" condition is needed, because the new state hash
is derived from the committed steps.

`challenge` requires the validity window to end by `challengeUntil` (`Clock.before`), and `finalize` requires it to
start at or after `challengeUntil` (`Clock.after`). So the two are disjoint; a window that straddles `challengeUntil`
allows neither, and both boundaries are tested. Since `challengeUntil = commit.validTo + window` and the window is at
least a minute, a checkpoint is always open to challenge for at least `window`.

**After a challenge:**
- the whole checkpoint is discarded, honest steps included, and L2 goes back to the last final state;
- soft confirmations from the sequencer mean nothing until finalisation;
- the bond is gone, so `commit` fails until the operator rebonds;
- orders from the discarded checkpoint stay valid (their nonces were never used), so the operator can include them
  again.

### D3. Off-chain roles

- **Operator (sequencer):** applies signed orders with the same `Ledger.step` on the JVM, records the steps, and
  commits checkpoints.
- **Watcher:** anyone. It reads the committed steps from the commit transaction's redeemer, re-executes them, and on
  the first mismatch submits `challenge` with the pre-state.

## 3. What the spike shows, and what it does not

**Assumptions:** watchers are online within the window and can reach the chain; the program has no bugs (the same
code runs on both sides, so a fraud proof can never catch a bug in the program itself); and the JVM's ed25519
verifier agrees with the Plutus builtin. That last point matters: if the JVM accepted an order the builtin rejects,
an honest operator would be slashed. A production operator should admit an order only after evaluating the step with
the UPLC evaluator, and the two verifiers should be tested against shared edge-case vectors.

**Shows:**
- the full optimistic loop on L1, built from JOUST parts: commit, a challenge window, finalisation, and a one-step
  fraud proof with slashing;
- the on-chain re-execution of the same Java method as the off-chain sequencer;
- fraud-proof costs measured on the harness and on Yaci DevKit, with an honest operator and a cheating one.

**Not built (each is a project in itself):**
1. **Data availability at scale.** The spike puts every step in the commit's redeemer: about 180 bytes a step, so
   about 3 KB for 16 steps. That's simple and enforced, but it bounds throughput by L1 space. Real options: a DA layer
   with an on-chain availability rule, or a Hydra head's snapshot log.
2. **Deposits and exits** between L1 and L2. An exit needs an account proof against a finalised `finalHash`, so the
   state should be an MPF (ADR-0002) instead of a hashed list. The spike's genesis book is given at init.
3. **Large states.** The fraud proof carries the whole pre-state, so it is bounded by the transaction size. With an
   MPF state, a step proves only the accounts it touches, reusing ADR-0002's proofs.
4. **Large checkpoints.** The proof carries all `count` steps (`MAX_STEPS = 16`). An MPF over step records would make
   a proof O(log n).
5. **Liveness and censorship:** a single operator, with no forced inclusion. If the operator disappears, L2 freezes
   (there are no exits). Challenge-window economics are also open: bond size against challenge costs, watcher
   incentives, and front-running of a published proof, which commit-reveal would fix.
7. **No user funds.** The spike's `close()` returns the bond to the operator. With deposits, closing would be a rug
   pull and would need the exit design.
6. **Generation.** The program and the `Rollup` contract are hand-written. A generator (`@Rollup` on a
   `@StateContract`) would derive `step` and validity from the contract's actions. That needs the "no throw in a
   step" discipline of D1, which conflicts with `Require`-style contracts.

## 4. Consequences

The State IR choices made in A–E are the right ones for F:
- transitions are pure and deterministic;
- the JVM and on-chain are equivalent;
- state can be Merkle-ised (B).

The one new requirement is a **total step** (D1): validity as a predicate, invalid input as a no-op. That is a
different programming discipline from `Require`, and it would be a generator mode, not a change to existing contracts.

## 5. Measured (harness)

| Call | CPU | Memory |
|---|---|---|
| commit, 4 steps | 0.35 B | 1.09 M |
| commit, 16 steps | 0.74 B | 2.23 M |
| finalize | 0.21 B | 0.68 M |
| challenge, 2 accounts, 3 steps (fraud at step 1) | 0.64 B | 1.83 M |
| challenge, **worst case: 32 accounts, 16 steps, fraud at step 15** | **2.74 B** | **8.12 M** |

The worst case fits the transaction limits (10 B CPU, 14 M memory), and its redeemer (about 5.5 KB) fits the 16 KB
size limit with the script read by reference. The validator is 12 KB. On Yaci DevKit:
1. an honest checkpoint of three orders finalises after a 60 s window;
2. a forged checkpoint is caught by a watcher reading the committed steps;
3. the bond is slashed (20 ADA to the watcher, 20 ADA burned) and L2 reverts;
4. the operator must rebond before committing again.

## 6. Review r1 and its resolution

| Finding | Source | Resolution |
|---|---|---|
| **Critical.** Ambiguous concatenation lets an honest checkpoint be "proven" fraudulent | Security 1, Codex 3 | Length-prefixed, sign-aware, domain-tagged encodings. Test `encodingsAreInjective` |
| **Critical.** A commit is not bound to its steps (garbage log, wrong count, too many steps): fraud escapes | Security 2, Codex 1 | `commit(steps)`: log, count and new state are derived on-chain. Test: a datum claiming another count is rejected |
| **Critical.** `step`, `logHash` and proofs not total (ed25519 lengths, I2OSP range) | Security 3, Codex 2 | `valid` checks shapes first, and the encodings are total. Tests: malformed and huge orders are no-ops, a malformed log is hashable, and `challenge_ofAMalformedOrderAppliedAsValid_slashes` |
| **Critical.** `rebond` during a checkpoint blocks challenges by contention | Security 4, Codex 5 | Only when nothing is pending. Tested |
| **High.** Operator self-challenge recovers the bond | Security 5, Codex 6 | Half the bond is burned. Test: paying the whole bond to the challenger is rejected |
| **High.** State hash canonicality; unbounded pre-state | Security 6 | Account count in the hash; `MAX_ACCOUNTS = 32` enforced by `step`; worst case measured (§5) |
| **High.** JVM vs on-chain ed25519 disagreement | Security 7 | Stated as an assumption, with the mitigation (§3) |
| Replay across rollups; global sequence; reverted orders | Security 8, Codex 4 | Per-rollup domain in the signed message; per-account nonces; reverted orders can be re-included (§2) |
| Ledger semantics undefined | Security 9 | Specified in D1 and tested (`ledger_semantics`, `ledger_boundsTheNumberOfAccounts`) |
| Post-challenge behaviour | Security 10 | Specified in D2 |
| Index bounds | Security 11 | `0 ≤ index < pendingCount` is required. Tested |
| Window boundaries | Security 12, Codex 5 | Disjoint by construction, with a minimum window of 60 s. Test `finalize_andChallenge_haveDisjointWindows` |
| Non-goals and assumptions not fenced | Security 13, Codex 7 | §3: the assumptions, DA at scale, exits (custody, replay), the operator vanishing, no user funds |

## 7. Open questions

The items in §3. Plus Hydra as the execution layer instead of a custom sequencer: JOUST contracts already run inside a
Hydra head unchanged, because a head is L1-isomorphic. The bridge, commit and fanout, is the part to design.
