# ADR-0008: Option H — lower fees: smaller scripts, cheaper invariant checks

**Status:** Accepted (r2): a security reviewer approved and Codex approved with fixes; resolved in §6
**Date:** 2026-11-07
**Builds on:** ADR-0001 (I1–I14), ADR-0007 (I26–I31).
**Stack:** #1–#8, then this.

## 1. Context

A JOUST call costs three things:
- **a size-based fee:** 155,381 lovelace plus 44 per byte of the transaction;
- **execution:** 0.0577 lovelace per memory unit and 0.0000721 per CPU step;
- **reference scripts:** 15 lovelace per byte of every reference script the transaction reads (the first 25,600-byte
  tier; larger totals cost more per byte).

Before this ADR the generated checks dominated execution. A counter increment cost 166M CPU and 554k memory, and
almost none of that was the contract's logic: an increment and a reset cost the same. The validator itself was
8.0 KB, so reading it by reference cost 0.12 ADA per call.

The expensive parts came from `ValuesLib` on Data values:
- `exactValue` built the expected value with `ValuesLib.add`/`singleton` and compared it with `ValuesLib.eq`, which
  looks up every key of each side in the other: O(P²);
- `instanceIdOf` and `mintsExactlyOne` flattened whole values into lists of records (`flattenTyped`) to find one
  policy's tokens;
- `uniqueOutputWith` built a filtered list of outputs and then took its size;
- in asset-aware contracts, the value of every method was flattened in the same way.

## 2. Decision

Every change keeps the invariant it implements and the generated code's structure. The attack suite and all tests
are unchanged, and pass.

| # | Check | Before | After |
|---|---|---|---|
| H1 | I3 exact value (lovelace-only contracts) | `ValuesLib.eq(v, add(singleton(ada), singleton(token)))` | One `equalsData` against the canonical encoding, built directly. Data equality implies value equality, so this is at least as strict. Canonical form is needed only for liveness, and the ledger provides it: lovelace first under the empty policy, sorted, no zeros |
| H2 | Token map of one policy | `flattenTyped` of the whole value | `ledgerTokensOf`: one pass over the policies (`ValuesLib._tokensOf`), returning that policy's token map. It takes the first entry of the policy, so it is used only on ledger values (`tx.mint()` and input and output values), which never repeat a policy |
| H3 | I1 `mintsExactlyOne` | Flatten, count, compare | `equalsData(tokensOf(mint, policy), {id: 1})` |
| H4 | I9 `onlyBurns` | Flatten the whole mint | Walk only the policy's token map |
| H5 | I10 `instanceIdOf` | Flatten, count | The policy's token map has exactly one pair, with quantity 1 |
| H6 | I4 `uniqueOutputWith` | Filter to a list, reverse it, take its size | One pass that counts and remembers the hit |
| H7 | I27 `lifecycleTokenOf` (asset-aware) | Flatten the whole value | Walk the policy's token map |
| H8 | I26 values computed by a method | `flattenTyped` and a fold over records | A direct recursive fold over the value's Data (policy pairs, then token pairs) into a native value, with the same sign and kind checks |
| H9 | Settlement of asset-aware transitions | Repeated in every method | One shared `settle` helper (value equation, payouts, sends, mint, accepted). It saves about 400 B in the DEX for about 2 % more CPU |

**Measured and rejected:**
- **JuLC `pv11-costed`:** compared with the default `pv11-safe`, it gives byte-identical scripts for these
  validators.
- **Stripping `Require` messages on-chain:** it saves about 100–300 B per contract, and it gives up the failure
  reason in node errors. Not worth it now; it could become an opt-in release bundle later.

## 3. Results

### Script size

Exact bytes of the compiled script's `cborHex`, as reference scripts are stored.

| Validator | Before | After | Change |
|---|---|---|---|
| Counter | 8,011 | 5,631 | −30 % |
| Vault | 8,653 | 6,273 | −28 % |
| Escrow | 7,380 | 5,688 | −23 % |
| Crowdfund | 10,356 | 7,976 | −23 % |
| Bank | 10,285 | 7,920 | −23 % |
| Pool (intents) | 11,652 | 9,586 | −18 % |
| Token (MerkleMap) | 14,095 | 11,718 | −17 % |
| Rollup | 12,825 | 10,447 | −19 % |
| Jar (.jsc) | 11,572 | 9,507 | −18 % |
| Dex (assets) | 13,019 | 12,008 | −8 % |
| Market (assets) | 12,103 | 11,325 | −6 % |

### Execution (harness, per script)

| Call | Before CPU / Mem | After CPU / Mem | Change (mem) |
|---|---|---|---|
| counter increment | 165.7M / 553k | 73.4M / 213k | −61 % |
| counter create | 167.4M / 568k | 86.2M / 271k | −52 % |
| vault withdraw | 232.0M / 782k | 140.6M / 445k | −43 % |
| crowdfund refund, 27 backers | 378.0M / 895k | 286.6M / 559k | −38 % |
| bank withdraw (@Shared read) | 263.1M / 885k | 171.4M / 547k | −38 % |
| token transfer (SPEND only) | 310.7M / 1,046k | 218.4M / 707k | −32 % |
| pool batch of 3 (instance spend) | 607.1M / 1,950k | 517.6M / 1,619k | −17 % |
| pool batch of 10, all scripts | 4,990.6M / 15,537k | 4,629.8M / 13,976k | −10 % |
| rollup worst-case challenge | 2,735.2M / 8,117k | 2,644.1M / 7,781k | −4 % |
| dex swapAdaForToken (SPEND) | 260.5M / 876k | 221.6M / 688k | −21 % |
| market buy (SPEND) | 405.0M / 1,389k | 345.7M / 1,127k | −19 % |

### What a call costs now

Mainnet parameters; transaction of about 1.2 KB.

| Call | Size fee | Execution | Reference script | Total |
|---|---|---|---|---|
| counter increment, before | 0.208 | 0.044 | 0.120 | **0.372 ADA** |
| counter increment, after | 0.208 | 0.018 | 0.084 | **0.310 ADA** (−17 %) |
| market buy, before | about 0.25 | 0.134 | 0.182 + 0.011 | **about 0.58 ADA** |
| market buy, after | about 0.25 | 0.106 | 0.170 + 0.011 | **about 0.54 ADA** |

The size-based fee is now the largest part, and it is set by the transaction: inputs, outputs, the inline datum, the
redeemer and the collateral. Further savings would come from slimmer redeemers and datums, and from merging several
calls into one transaction, which the composition work (ADR-0011) makes possible.

## 4. Consequences

- Every existing contract gets cheaper with no source change. Script hashes change, so instances deployed before this
  ADR belong to the old scripts.
- **The batch limit grows:** 10 intents now fit in 14M memory (13.98M, measured over all scripts in the harness),
  where they needed 15.5M before. That leaves almost no headroom at 14M, so `Batcher.DEFAULT_MAX` stays 8. The
  batcher's halving retry (ADR-0004) still finds the size that fits.
- No new invariants, no API change.

## 5. Out of scope and next steps

- **A JuLC-side profiler** reporting budget per function. Without one, these numbers come from change-by-change
  experiments.
- **The MPF verifier** (`MapVerifier`): its cost is hashing, which is intrinsic.
- **Release bundles without trace messages.**
- **Slimmer `Ctx`:** `balance` could be computed on-chain. It is kept so that the JVM and the chain see the same value.

## 6. Review r1 and its resolution

| Finding | Source | Resolution |
|---|---|---|
| The batch-capacity claim ("about 12 at 16.5M") was not measured | Codex 1 | Removed. Only the measured 10-intent figure is stated, with its headroom |
| `_tokensOf` takes the first match, so it is sound only on ledger values | Codex 2, security 2 | Renamed `ledgerTokensOf` and documented. Every call site passes `tx.mint()` or an input's or output's value |
| A placeholder `next` could hide a future return kind | Security 1 | The generator throws if a non-destroy transition has no next state |
| `methodValue` rejected empty token maps that it used to accept | Security 3 | Empty token maps are skipped, as before |
| Cost tables need scope labels | Codex 6 | Byte encoding and the reference-fee tier are stated |
| The H1 argument | Security | "Data equality implies value equality"; canonical form is needed only for liveness |
